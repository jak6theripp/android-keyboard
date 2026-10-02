package org.futo.inputmethod.latin.uix.actions

import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.dictation.DICTATION_TRANSLATE_LANG
import org.futo.inputmethod.latin.dictation.SecureKeys
import org.futo.inputmethod.latin.dictation.assist.AssistClient
import org.futo.inputmethod.latin.dictation.assist.AssistResult
import org.futo.inputmethod.latin.dictation.assist.ScreenReaderService
import org.futo.inputmethod.latin.dictation.assist.ScreenSnapshot
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionInputTransaction
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting

val TRANSLATE_LANGUAGES = listOf(
    "Spanish", "English", "French", "German", "Italian", "Portuguese", "Vietnamese",
    "Chinese (Simplified)", "Japanese", "Korean", "Tagalog", "Arabic", "Russian", "Hindi"
)

@Composable
fun LanguageChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        items(TRANSLATE_LANGUAGES) { lang ->
            FilterChip(selected = lang == selected, onClick = { onSelect(lang) }, label = { Text(lang) })
        }
    }
}

// =========================================================================================
// AI reply
// =========================================================================================

private enum class ReplyStage { Listening, Drafting, Review, Error }

/**
 * AI reply mode. Opening it reads the conversation on screen once (if the user enabled the screen
 * reader), then listens for a spoken instruction. The instruction is shown here, not typed into
 * the field. The draft goes into the field as composing text for review: Keep commits it, Discard
 * removes it. Nothing here can send a message; sending is always the user's own tap in the app.
 */
private class AiReplyWindow(val manager: KeyboardManagerForAction) : ActionWindow() {
    private val context = manager.getContext()
    private val controller = manager.getLatinIMEForDebug().dictationController

    private val stage = mutableStateOf(ReplyStage.Listening)
    private val instruction = mutableStateOf("")
    private val draft = mutableStateOf("")
    private val error = mutableStateOf("")
    private val snapshot: ScreenSnapshot? = ScreenReaderService.snapshot()
    private var transaction: ActionInputTransaction? = null
    private var job: Job? = null
    private var closed = false

    init {
        if (snapshot == null || snapshot.messages.isEmpty()) {
            Toast.makeText(context, R.string.ai_reply_no_screen, Toast.LENGTH_SHORT).show()
        }
        listen()
    }

    private fun fail(message: String) { error.value = message; stage.value = ReplyStage.Error }

    private fun listen() {
        instruction.value = ""
        stage.value = ReplyStage.Listening
        val started = controller.startCapture(
            triggerPath = "ai_reply_instruction",
            onUpdate = { if (!closed) instruction.value = it },
            onDone = { text, _ -> if (!closed) onInstruction(text) }
        )
        if (!started) fail(context.getString(R.string.ai_reply_busy))
    }

    private fun onInstruction(text: String) {
        instruction.value = text
        if (text.isBlank()) { fail(context.getString(R.string.ai_reply_no_instruction)); return }
        val key = SecureKeys.get(context, SecureKeys.KEY_ANTHROPIC)
        if (key == null) { fail(context.getString(R.string.ai_no_key)); return }
        stage.value = ReplyStage.Drafting
        job = manager.getLifecycleScope().launch {
            val result = withContext(Dispatchers.IO) { AssistClient(key).draftReply(snapshot, text) }
            if (closed) return@launch
            when (result) {
                is AssistResult.Ok -> {
                    draft.value = result.text
                    // Composing text: visible in the field for review, not committed, never sent.
                    val tx = transaction ?: manager.createInputTransaction().also { transaction = it }
                    tx.updatePartial(result.text)
                    stage.value = ReplyStage.Review
                }
                is AssistResult.Failed -> fail(result.reason)
            }
        }
    }

    private fun keep() {
        transaction?.commit(draft.value); transaction = null
        manager.closeActionWindow()
    }

    private fun discard() {
        transaction?.let { it.updatePartial(""); it.cancel() }; transaction = null
        manager.closeActionWindow()
    }

    private fun redo() {
        transaction?.updatePartial("")
        draft.value = ""
        listen()
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_ai_reply_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val s by stage
        val instr by instruction
        val d by draft
        val err by error
        val level by controller.level
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                when {
                    snapshot == null -> stringResource(R.string.ai_reply_context_off)
                    snapshot.messages.isEmpty() -> stringResource(R.string.ai_reply_context_empty)
                    else -> stringResource(R.string.ai_reply_context_read, snapshot.messages.size, snapshot.title ?: "")
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Spacer(Modifier.size(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                val body = when (s) {
                    ReplyStage.Listening -> instr.ifBlank { stringResource(R.string.ai_reply_prompt) }
                    ReplyStage.Drafting -> instr
                    ReplyStage.Review -> d
                    ReplyStage.Error -> err
                }
                Text(body, style = MaterialTheme.typography.titleMedium,
                    color = if (s == ReplyStage.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    ReplyStage.Listening -> {
                        Canvas(Modifier.size(16.dp)) { drawCircle(Color(0xFFD32F2F), radius = size.minDimension / 2f * (0.7f + (level * 3f).coerceIn(0f, 0.3f))) }
                        Text(stringResource(R.string.dictation_state_listening), color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { controller.stop("ai_reply_instruction_done") }, enabled = instr.isNotBlank()) {
                            Text(stringResource(R.string.ai_reply_draft))
                        }
                    }
                    ReplyStage.Drafting -> {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ai_reply_drafting), color = MaterialTheme.colorScheme.onBackground)
                    }
                    ReplyStage.Review -> {
                        OutlinedButton(onClick = { discard() }) { Text(stringResource(R.string.ai_discard)) }
                        OutlinedButton(onClick = { redo() }) { Text(stringResource(R.string.ai_redo)) }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { keep() }) { Text(stringResource(R.string.ai_keep)) }
                    }
                    ReplyStage.Error -> {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { listen() }) { Text(stringResource(R.string.ai_try_again)) }
                    }
                }
            }
        }
    }

    override fun close(): CloseResult {
        closed = true
        job?.cancel()
        if (stage.value == ReplyStage.Listening) controller.cancelCapture()
        // Closing while a draft is under review keeps it as ordinary text: losing it would be worse,
        // and it is still only text in the field.
        transaction?.let { if (stage.value == ReplyStage.Review) it.commit(draft.value) else it.cancel() }
        transaction = null
        return CloseResult.Default
    }
}

val AiReplyAction = Action(
    icon = R.drawable.sparkles,
    name = R.string.action_ai_reply_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    windowImpl = { manager, _ -> AiReplyWindow(manager) },
)

// =========================================================================================
// Translate
// =========================================================================================

/** Translates the selection, or the whole field if nothing is selected. Replaces only on confirm. */
private class TranslateWindow(val manager: KeyboardManagerForAction) : ActionWindow() {
    private val context = manager.getContext()
    private val latinIME = manager.getLatinIMEForDebug()
    private val ic get() = latinIME.currentInputConnection

    private val selection: String? = ic?.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() }
    private val whole: String = if (selection != null) "" else (ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: "")
    private val source: String = selection ?: whole

    private val language = mutableStateOf(context.getSetting(DICTATION_TRANSLATE_LANG))
    private val result = mutableStateOf<AssistResult?>(null)
    private var job: Job? = null

    init { translate() }

    private fun translate() {
        job?.cancel()
        result.value = null
        if (source.isBlank()) { result.value = AssistResult.Failed(context.getString(R.string.translate_nothing)); return }
        val key = SecureKeys.get(context, SecureKeys.KEY_ANTHROPIC)
        if (key == null) { result.value = AssistResult.Failed(context.getString(R.string.ai_no_key)); return }
        val lang = language.value
        job = manager.getLifecycleScope().launch {
            result.value = withContext(Dispatchers.IO) { AssistClient(key).translate(source, lang) }
        }
    }

    private fun setLanguage(lang: String) {
        if (lang == language.value) return
        language.value = lang
        manager.getLifecycleScope().launch { context.setSetting(DICTATION_TRANSLATE_LANG, lang) }
        translate()
    }

    private fun replaceWith(text: String) {
        val c = ic ?: return
        c.beginBatchEdit()
        if (selection == null) c.setSelection(0, whole.length) // no selection: the whole field is replaced
        c.commitText(text, 1)
        c.endBatchEdit()
        manager.closeActionWindow()
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_translate_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val lang by language
        val r by result
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            LanguageChips(lang) { setLanguage(it) }
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(if (selection != null) R.string.translate_scope_selection else R.string.translate_scope_field),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (val res = r) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.translate_working), color = MaterialTheme.colorScheme.onBackground)
                    }
                    is AssistResult.Ok -> Text(res.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    is AssistResult.Failed -> Text(res.reason, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { manager.closeActionWindow() }) { Text(stringResource(R.string.ai_cancel)) }
                Spacer(Modifier.weight(1f))
                val ok = r as? AssistResult.Ok
                Button(onClick = { ok?.let { replaceWith(it.text) } }, enabled = ok != null) { Text(stringResource(R.string.translate_replace)) }
            }
        }
    }

    override fun close(): CloseResult { job?.cancel(); return CloseResult.Default }
}

val TranslateAction = Action(
    icon = R.drawable.translate,
    name = R.string.action_translate_title,
    simplePressImpl = null,
    windowImpl = { manager, _ -> TranslateWindow(manager) },
)
