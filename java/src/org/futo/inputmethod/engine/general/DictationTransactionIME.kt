package org.futo.inputmethod.engine.general

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.engine.IMEHelper
import org.futo.inputmethod.engine.TransactionIME
import org.futo.inputmethod.event.Event
import org.futo.inputmethod.latin.common.InputPointers
import org.futo.inputmethod.v2keyboard.KeyboardLayoutSetV2

/**
 * Input transaction for streaming dictation. Unlike [ActionInputTransactionIME] it survives many
 * commits: segments are committed as they finalize while a composing region shows the pending
 * tail. Only the dictation controller touches this; it is the single text-mutation path.
 */
class DictationTransactionIME(
    val helper: IMEHelper,
    private val onSelectionUpdate: (oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingStart: Int, composingEnd: Int) -> Unit,
) : TransactionIME {
    val ic get() = helper.getCurrentInputConnection()

    val supportsComposing: Boolean = run {
        val inputType = helper.getCurrentEditorInfo()?.inputType ?: 0
        (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT
    }

    private var finished = false
    val isFinished get() = finished

    fun setComposing(text: CharSequence) {
        if (finished || ic == null) return
        helper.requestCursorUpdate()
        if (supportsComposing) ic?.setComposingText(text, 1)
    }

    fun commitText(text: CharSequence) {
        if (finished || ic == null) return
        helper.requestCursorUpdate()
        ic?.commitText(text, 1)
    }

    fun finishComposing() {
        if (finished) return
        ic?.finishComposingText()
    }

    /** A real DEL key event, so the editor handles selections, emoji and its own undo correctly. */
    fun sendBackspace() {
        val c = ic ?: return
        if (finished) return
        val now = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        c.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
    }

    fun deleteBefore(n: Int) {
        if (finished || n <= 0) return
        ic?.deleteSurroundingText(n, 0)
    }

    fun beginBatch() { ic?.beginBatchEdit() }
    fun endBatch() { ic?.endBatchEdit() }

    fun textBeforeCursor(n: Int): CharSequence? = ic?.getTextBeforeCursor(n, 0)

    /** Ends the transaction and returns control to the regular engine. */
    fun end() {
        if (finished) return
        finished = true
        helper.endInputTransaction(this)
    }

    override fun ensureFinished() { finished = true }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingSpanStart: Int, composingSpanEnd: Int) {
        onSelectionUpdate(oldSelStart, oldSelEnd, newSelStart, newSelEnd, composingSpanStart, composingSpanEnd)
    }

    // No-op engine surface: dictation swallows key events while active.
    override fun onCreate() {}
    override fun onDestroy() {}
    override fun onDeviceUnlocked() {}
    override fun onStartInput() {}
    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {}
    override fun onOrientationChanged() {}
    override fun onFinishInput() {}
    override fun isGestureHandlingAvailable(): Boolean = false
    override fun onEvent(event: Event) {}
    override fun onStartBatchInput() {}
    override fun onUpdateBatchInput(batchPointers: InputPointers?) {}
    override fun onEndBatchInput(batchPointers: InputPointers?) {}
    override fun onCancelBatchInput() {}
    override fun onCancelInput() {}
    override fun onFinishSlidingInput() {}
    override fun onCustomRequest(requestCode: Int): Boolean = false
    override fun onMovePointer(steps: Int, stepOverWords: Boolean, select: Boolean?) {}
    override fun onMoveDeletePointer(steps: Int) {}
    override fun onUpWithDeletePointerActive() {}
    override fun onUpWithPointerActive() {}
    override fun onSwipeLanguage(direction: Int) {}
    override fun onMovingCursorLockEvent(canMoveCursor: Boolean) {}
    override fun clearUserHistoryDictionaries() {}
    override fun requestSuggestionRefresh() {}
}
