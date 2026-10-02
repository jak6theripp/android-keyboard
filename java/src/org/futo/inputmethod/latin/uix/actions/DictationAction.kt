package org.futo.inputmethod.latin.uix.actions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.dictation.DictationController
import org.futo.inputmethod.latin.dictation.DictationEngine
import org.futo.inputmethod.latin.dictation.DictationState
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction

private val HotRed = Color(0xFFD32F2F)
private val WarnAmber = Color(0xFFFFA000)
private val WaitGrey = Color(0xFF757575)

/**
 * Full-panel mic indicator for streaming dictation. Purely a view: live text goes into the
 * editor, and the session lives in DictationEngine / DictationController, surviving this window
 * being closed and recreated (fold, rotation). Tap anywhere on the panel to stop.
 */
private class DictationWindow(val manager: KeyboardManagerForAction, val controller: DictationController) : ActionWindow() {
    init {
        if (DictationEngine.isActive) {
            if (!DictationEngine.consumeToggleSuppression()) controller.stop("user_toggle")
        } else {
            controller.start("keyboard_action")
        }
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_dictation_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val state by controller.stateForUi
        val secondsLeft by controller.idleSecondsLeft
        val level by controller.level
        val error by controller.lastError

        val hot = state == DictationState.Listening
        val warning = hot && secondsLeft in 0..5
        val waiting = state == DictationState.Starting || state == DictationState.Reconnecting || state == DictationState.Stopping

        val pulse = rememberInfiniteTransition(label = "pulse")
        val blink by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "blink")
        // Voice-reactive size: speech RMS is roughly 0.02..0.15.
        val voiceScale by animateFloatAsState(1f + (level * 3.5f).coerceIn(0f, 0.28f), tween(120), label = "voice")
        val circleColor by animateColorAsState(
            when {
                warning -> WarnAmber
                hot -> HotRed
                waiting -> WaitGrey
                state == DictationState.Paused -> WaitGrey
                else -> MaterialTheme.colorScheme.primaryContainer
            }, label = "color"
        )

        BoxWithConstraints(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                if (DictationEngine.isActive) controller.stop("panel_tap") else manager.closeActionWindow()
            }
        ) {
            val diameter = min(maxHeight * 0.62f, min(maxWidth * 0.5f, 220.dp))
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(diameter)
                        .scale(if (hot && !warning) voiceScale else 1f)
                        .background(circleColor.copy(alpha = if (warning || waiting) blink else 1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (warning) {
                        Text("$secondsLeft", color = Color.White, fontSize = (diameter.value * 0.5f).sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(painterResource(R.drawable.mic_fill), contentDescription = null, tint = Color.White, modifier = Modifier.size(diameter * 0.45f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                val label = when {
                    error != null && state == DictationState.Idle -> error!!
                    warning -> stringResource(R.string.dictation_idle_warning, secondsLeft)
                    hot -> stringResource(R.string.dictation_tap_to_stop)
                    else -> stringResource(when (state) {
                        DictationState.Idle -> R.string.dictation_state_idle
                        DictationState.Starting -> R.string.dictation_state_starting
                        DictationState.Listening -> R.string.dictation_state_listening
                        DictationState.Reconnecting -> R.string.dictation_state_reconnecting
                        DictationState.Paused -> R.string.dictation_state_paused
                        DictationState.Stopping -> R.string.dictation_state_stopping
                    })
                }
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }

            // Backspace works while the mic is hot: tap = one character, hold = accelerating repeat.
            val scope = rememberCoroutineScope()
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            controller.backspace()
                            // Like the stock keyboard: letters first, then whole words, speeding up to a cap.
                            val repeat = scope.launch {
                                delay(380)
                                repeat(12) { controller.backspace(); delay(50) }
                                var interval = 230L
                                while (true) {
                                    controller.backspaceWord()
                                    delay(interval)
                                    interval = (interval * 0.87f).toLong().coerceAtLeast(80L)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.delete), contentDescription = stringResource(R.string.dictation_backspace),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
            }
        }
    }

    /**
     * The back arrow closes the window = stop. A close caused by the input view being torn down
     * (fold, rotation, field change) must not stop the session; the controller decides that.
     */
    override fun close(): CloseResult {
        if (controller.isInputViewActive && DictationEngine.isActive) controller.stop("window_closed")
        return CloseResult.Default
    }
}

val DictationAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_dictation_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    windowImpl = { manager, _ ->
        DictationWindow(manager, manager.getLatinIMEForDebug().dictationController)
    }
)
