package com.zkkm.pocketssh.term

import android.content.Context
import android.text.InputType
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
 * This is the layer that breaks most often on real devices, so the rules are
 * deliberately explicit:
 *
 *  - We delegate to a real EditText because Android refuses to start an IME for
 *    a plain View that is not a text editor.
 *  - All forwarding happens from the [InputConnection], never from a
 *    TextWatcher. A TextWatcher sees composing text (pinyin, kana, ...) as if
 *    it were committed and destroys the IME's composition state.
 *  - Committed text is forwarded and then *discarded from the buffer*. It is
 *    never inserted first: inserting and clearing again makes several Chinese
 *    IMEs (Sogou, Baidu, Samsung) re-send or drop the characters.
 *  - Enter arrives as `commitText("\n")`, `commitText("\r")`, a raw key event,
 *    `performEditorAction` or `onEditorAction`, depending on the IME. All of
 *    them are handled and all of them end up as a single CR.
 */
@Suppress("DEPRECATION") // KeyEvent.getCharacters() for ACTION_MULTIPLE
class TerminalInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** Committed text (may contain LF / CR). Return true when consumed. */
    var onCommitText: ((String) -> Boolean)? = null

    /** Return true when the backspace was forwarded to the session. */
    var onBackspace: (() -> Boolean)? = null

    /** Return true when the enter was forwarded to the session. */
    var onEnter: (() -> Boolean)? = null

    /** Fallback for arrow keys, Tab, Esc and hardware characters. */
    var onKeyFallback: ((KeyEvent) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null

        // Ask the IME for a real Enter key that inserts a newline instead of an
        // "action" key. Leaving IME_MASK_ACTION cleared to UNSPECIFIED made
        // several Chinese keyboards report an editor action and send nothing.
        outAttrs.inputType = outAttrs.inputType or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE
        outAttrs.imeOptions = (outAttrs.imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or
            EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : InputConnectionWrapper(base, true) {

            /** Text the IME is currently composing (pinyin candidates, ...). */
            private fun composing(): Boolean = !this@TerminalInputView.text.isNullOrEmpty()

            private fun clearBuffer() {
                val buffer = this@TerminalInputView.text ?: return
                if (buffer.isNotEmpty()) buffer.clear()
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val committed = text?.toString().orEmpty()
                // Drop any leftover composing text, then forward. Nothing is
                // inserted into the (invisible) buffer, so the IME never sees a
                // phantom character it did not put there.
                clearBuffer()
                if (committed.isNotEmpty()) onCommitText?.invoke(committed)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Candidate text belongs to the IME; it must not reach the PTY.
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // While composing, the IME is deleting its own candidate text.
                if (!composing() && beforeLength > 0 && onBackspace?.invoke() == true) {
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                if (!composing() && beforeLength > 0 && onBackspace?.invoke() == true) {
                    return true
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event == null) return super.sendKeyEvent(event)
                val code = event.keyCode
                val isEnter = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                val isDelete = code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL

                when (event.action) {
                    KeyEvent.ACTION_UP -> {
                        // Swallow the matching UP so the EditText does not insert
                        // a stray newline / delete a character on key release.
                        if (isEnter || isDelete) return true
                    }
                    KeyEvent.ACTION_MULTIPLE -> {
                        val chars = event.characters
                        if (!chars.isNullOrEmpty()) {
                            clearBuffer()
                            onCommitText?.invoke(chars)
                            return true
                        }
                    }
                    KeyEvent.ACTION_DOWN -> {
                        if (isEnter) {
                            if (onEnter?.invoke() == true) {
                                clearBuffer()
                                return true
                            }
                        } else if (isDelete) {
                            if (composing()) {
                                // Composition in progress: let the IME delete
                                // its own candidate text instead of sending a
                                // backspace to the shell.
                                return super.sendKeyEvent(event)
                            }
                            if (onBackspace?.invoke() == true) {
                                clearBuffer()
                                return true
                            }
                        } else if (onKeyFallback?.invoke(event) == true) {
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                // Some IMEs report Enter here even with IME_ACTION_NONE +
                // IME_FLAG_NO_ENTER_ACTION. Treat it as Enter, never as "done".
                if (onEnter?.invoke() == true) {
                    clearBuffer()
                    return true
                }
                return super.performEditorAction(actionCode)
            }
        }
    }

    /** Hardware keyboards (and IMEs with no active InputConnection) land here. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // While the IME owns composing text, DEL belongs to the candidate, not
        // to the shell. Forwarding it as well would delete a server-side
        // character on every pinyin correction.
        val isDelete = keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        if (isDelete && composing()) return super.onKeyDown(keyCode, event)
        if (onKeyFallback?.invoke(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // The DOWN was already turned into terminal bytes; the UP must not also
        // reach the EditText (it would insert a newline or delete a char).
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onEditorAction(actionCode: Int) {
        // Last-chance Enter handler for IMEs that bypass the InputConnection.
        if (onEnter?.invoke() == true) return
        super.onEditorAction(actionCode)
    }

    private fun composing(): Boolean = !text.isNullOrEmpty()
}
