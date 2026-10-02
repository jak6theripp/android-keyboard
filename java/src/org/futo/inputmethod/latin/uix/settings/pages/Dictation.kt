package org.futo.inputmethod.latin.uix.settings.pages

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.dictation.DICTATION_CLEANUP_ENABLED
import org.futo.inputmethod.latin.dictation.DICTATION_DEBUG_LOGGING
import org.futo.inputmethod.latin.dictation.DICTATION_EOU_SILENCE_S
import org.futo.inputmethod.latin.dictation.DICTATION_FALLBACK_MIC_KEY
import org.futo.inputmethod.latin.dictation.DICTATION_NAVBAR_MIC
import org.futo.inputmethod.latin.dictation.DICTATION_HOLD_TO_TALK
import org.futo.inputmethod.latin.dictation.DICTATION_IDLE_TIMEOUT_S
import org.futo.inputmethod.latin.dictation.DICTATION_LAST_TRIGGER_PATH
import org.futo.inputmethod.latin.dictation.DICTATION_MAX_DELAY
import org.futo.inputmethod.latin.dictation.DICTATION_MAX_DELAY_MAX
import org.futo.inputmethod.latin.dictation.DICTATION_MAX_DELAY_MIN
import org.futo.inputmethod.latin.dictation.DICTATION_SAVE_AUDIO
import org.futo.inputmethod.latin.dictation.DICTATION_STT_URL
import org.futo.inputmethod.latin.dictation.DICTATION_VOCAB
import org.futo.inputmethod.latin.dictation.DictationLog
import org.futo.inputmethod.latin.dictation.SecureKeys
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingSlider
import org.futo.inputmethod.latin.uix.settings.SettingTextField
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import java.util.Locale

@Composable
private fun ApiKeysSection() {
    val context = LocalContext.current
    var present by remember { mutableStateOf(SecureKeys.presentKeys(context)) }
    ScreenTitle(stringResource(R.string.dictation_settings_keys_title))
    Text(
        if (present.isEmpty()) stringResource(R.string.dictation_settings_keys_missing)
        else stringResource(R.string.dictation_settings_keys_present, present.joinToString()),
        modifier = Modifier.padding(16.dp, 4.dp)
    )
    Tip(stringResource(R.string.dictation_settings_keys_import_hint) + "\n" + SecureKeys.importFile(context).absolutePath)
    Column(Modifier.padding(16.dp, 4.dp)) {
        Button(onClick = {
            val msg = when (val r = SecureKeys.importFromFile(context)) {
                is SecureKeys.ImportResult.Ok -> context.getString(R.string.dictation_settings_keys_import_ok, r.imported.joinToString())
                is SecureKeys.ImportResult.Failed -> context.getString(R.string.dictation_settings_keys_import_fail, r.reason)
            }
            present = SecureKeys.presentKeys(context)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }) { Text(stringResource(R.string.dictation_settings_keys_import)) }
        OutlinedButton(onClick = {
            SecureKeys.clear(context); present = SecureKeys.presentKeys(context)
        }) { Text(stringResource(R.string.dictation_settings_keys_clear)) }
    }
}

@Composable
private fun VocabEditor() {
    val context = LocalContext.current
    val setting = useDataStore(DICTATION_VOCAB)
    var text by remember { mutableStateOf(context.getSettingBlocking(DICTATION_VOCAB.key, DICTATION_VOCAB.default)) }
    LaunchedEffect(text) { setting.setValue(text) }
    ScreenTitle(stringResource(R.string.dictation_settings_vocab))
    Text(stringResource(R.string.dictation_settings_vocab_subtitle), modifier = Modifier.padding(16.dp, 0.dp))
    TextField(
        value = text, onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(8.dp, 4.dp), minLines = 6
    )
}

@Composable
private fun SetupSection() {
    val context = LocalContext.current
    ScreenTitle(stringResource(R.string.dictation_settings_setup_title))
    Column(Modifier.padding(16.dp, 4.dp)) {
        Text(stringResource(
            if (org.futo.inputmethod.latin.dictation.assist.ScreenReaderService.isEnabled) R.string.dictation_settings_setup_accessibility_on
            else R.string.dictation_settings_setup_accessibility_off))
        Text(stringResource(R.string.dictation_settings_setup_accessibility_help), modifier = Modifier.padding(0.dp, 6.dp))
        Text(stringResource(R.string.dictation_settings_setup_battery_help), modifier = Modifier.padding(0.dp, 6.dp))
        OutlinedButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(stringResource(R.string.dictation_settings_setup_open_app_info)) }
        OutlinedButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text(stringResource(R.string.dictation_settings_setup_open_accessibility)) }
    }
}

@Composable
private fun TranslateLanguageSetting() {
    val setting = useDataStore(org.futo.inputmethod.latin.dictation.DICTATION_TRANSLATE_LANG)
    ScreenTitle(stringResource(R.string.dictation_settings_translate_language))
    Column(Modifier.padding(16.dp, 4.dp)) {
        org.futo.inputmethod.latin.uix.actions.LanguageChips(setting.value) { setting.setValue(it) }
    }
}

val DictationMenu = UserSettingsMenu(
    title = R.string.dictation_settings_title,
    navPath = "dictation", registerNavPath = true,
    settings = listOf(
        UserSetting(name = R.string.dictation_settings_keys_title) { ApiKeysSection() },
        UserSetting(name = R.string.dictation_settings_setup_title) { SetupSection() },
        UserSetting(name = R.string.dictation_settings_translate_language) { TranslateLanguageSetting() },

        UserSetting(name = R.string.dictation_settings_max_delay) {
            SettingSlider(
                title = stringResource(R.string.dictation_settings_max_delay),
                subtitle = stringResource(R.string.dictation_settings_max_delay_subtitle),
                setting = DICTATION_MAX_DELAY,
                range = DICTATION_MAX_DELAY_MIN..DICTATION_MAX_DELAY_MAX,
                transform = { (Math.round(it * 10f) / 10f) },
                indicator = { String.format(Locale.US, "%.1f s", it) }
            )
        },
        UserSetting(name = R.string.dictation_settings_idle_timeout) {
            SettingSlider(
                title = stringResource(R.string.dictation_settings_idle_timeout),
                subtitle = stringResource(R.string.dictation_settings_idle_timeout_subtitle),
                setting = DICTATION_IDLE_TIMEOUT_S,
                range = 5f..180f, hardRange = 5f..3600f,
                transform = { it.toInt() },
                indicator = { "$it s" }
            )
        },
        UserSetting(name = R.string.dictation_settings_eou_silence) {
            SettingSlider(
                title = stringResource(R.string.dictation_settings_eou_silence),
                subtitle = stringResource(R.string.dictation_settings_eou_silence_subtitle),
                setting = DICTATION_EOU_SILENCE_S,
                range = 0f..2f,
                transform = { (Math.round(it * 10f) / 10f) },
                indicator = { if (it <= 0f) "off" else String.format(Locale.US, "%.1f s", it) }
            )
        },
        UserSetting(name = R.string.dictation_settings_punct) {
            SettingSlider(
                title = stringResource(R.string.dictation_settings_punct),
                subtitle = stringResource(R.string.dictation_settings_punct_subtitle),
                setting = org.futo.inputmethod.latin.dictation.DICTATION_PUNCT_SENSITIVITY,
                range = 0f..1f,
                transform = { (Math.round(it * 20f) / 20f) },
                indicator = { String.format(Locale.US, "%.2f", it) }
            )
        },
        userSettingToggleDataStore(
            title = R.string.dictation_settings_cleanup,
            subtitle = R.string.dictation_settings_cleanup_subtitle,
            setting = DICTATION_CLEANUP_ENABLED
        ),
        userSettingToggleDataStore(
            title = R.string.dictation_settings_hold_to_talk,
            subtitle = R.string.dictation_settings_hold_to_talk_subtitle,
            setting = DICTATION_HOLD_TO_TALK
        ),
        userSettingToggleDataStore(
            title = R.string.dictation_settings_navbar_mic,
            subtitle = R.string.dictation_settings_navbar_mic_subtitle,
            setting = DICTATION_NAVBAR_MIC
        ),
        userSettingToggleDataStore(
            title = R.string.dictation_settings_fallback_mic_key,
            subtitle = R.string.dictation_settings_fallback_mic_key_subtitle,
            setting = DICTATION_FALLBACK_MIC_KEY
        ),
        UserSetting(name = R.string.dictation_settings_vocab) { VocabEditor() },

        userSettingToggleDataStore(
            title = R.string.dictation_settings_debug_logging,
            subtitle = R.string.dictation_settings_debug_logging_subtitle,
            setting = DICTATION_DEBUG_LOGGING
        ),
        userSettingToggleDataStore(
            title = R.string.dictation_settings_save_audio,
            subtitle = R.string.dictation_settings_save_audio_subtitle,
            setting = DICTATION_SAVE_AUDIO
        ),
        UserSetting(name = R.string.dictation_settings_export_logs) {
            val context = LocalContext.current
            val lastPath = useDataStoreValue(DICTATION_LAST_TRIGGER_PATH)
            Column(Modifier.padding(16.dp, 4.dp)) {
                OutlinedButton(onClick = {
                    val (n, dir) = DictationLog.export(context)
                    Toast.makeText(context, context.getString(R.string.dictation_settings_export_logs_done, n, dir.absolutePath), Toast.LENGTH_LONG).show()
                }) { Text(stringResource(R.string.dictation_settings_export_logs)) }
                Text(stringResource(R.string.dictation_settings_last_path, lastPath), modifier = Modifier.padding(0.dp, 8.dp))
            }
        },
        UserSetting(name = R.string.dictation_settings_stt_url) {
            SettingTextField(stringResource(R.string.dictation_settings_stt_url), DICTATION_STT_URL.default, DICTATION_STT_URL)
        },
    )
)
