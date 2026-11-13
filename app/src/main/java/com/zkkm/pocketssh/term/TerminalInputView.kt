package com.zkkm.pocketssh.term

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * Invisible [android.widget.EditText] that owns the soft-keyboard session for a
 * [TerminalView].
 *
 * A custom View that returns its own `InputConnection` is unreliable across
 * IMEs: some bypass `commitText` entirely and send raw key events, and the
 * framework refuses `showSoftInput()` for views whose `onCheckIsTextEditor()`
 * is false. Delegating to a real EditText keeps every input path working; this
 * subclass only intercepts the two events a text buffer cannot express on an
 * empty field: Backspace and Enter.
 */
class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Return true when the backspace was forwarded to the session. */
    var onBackspace: (() -> Boolean)? = null

    /** Return true when the enter was forwarded to the session. */
    var onEnter: (() -> Boolean)? = null

    /** Fallback for arrow keys, Tab, Esc and hardware characters. */
    var onKeyFallback: ((KeyEvent) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv()
        return object : InputConnectionWrapper(base, true) {

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && onBackspace?.invoke() == true) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && onBackspace?.invoke() == true) return true
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL ->
                            if (onBackspace?.invoke() == true) return true
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                            if (onEnter?.invoke() == true) return true
                        else ->
                            if (onKeyFallback?.invoke(event) == true) return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onKeyFallback?.invoke(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}
