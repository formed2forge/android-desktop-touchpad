package com.pixeltouchpad.app

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible view that exists only to grab a real InputMethodManager session so the phone's own
 * software keyboard can be summoned even though there's no actual text field anywhere in this
 * app - the keystrokes are relayed to the external display instead (see [onTextCommitted] /
 * [onDeleteBefore] / [onSpecialKey]), for apps there that never trigger Android's own IME focus
 * (e.g. remote desktop/VNC clients rendering their own content).
 *
 * Diffs [setComposingText] updates against what was last seen rather than forwarding each one
 * verbatim, since predictive keyboards (Gboard etc.) stream the in-progress word through this
 * repeatedly as you type/autocorrect - forwarding every update raw would retype the whole word
 * each keystroke instead of just the new character.
 */
class KeyboardCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onTextCommitted: ((String) -> Unit)? = null
    var onDeleteBefore: ((Int) -> Unit)? = null
    var onSpecialKey: ((Int) -> Unit)? = null // KeyEvent.KEYCODE_*

    private var lastComposingText = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val newText = text?.toString() ?: ""
                relayAgainstComposing(newText)
                lastComposingText = ""
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val newText = text?.toString() ?: ""
                relayAgainstComposing(newText)
                lastComposingText = newText
                return true
            }

            override fun finishComposingText(): Boolean {
                lastComposingText = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) onDeleteBefore?.invoke(beforeLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DEL -> onDeleteBefore?.invoke(1)
                        else -> onSpecialKey?.invoke(event.keyCode)
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onSpecialKey?.invoke(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    /** Forwards [newText] as the minimal edit relative to [lastComposingText]. */
    private fun relayAgainstComposing(newText: String) {
        val old = lastComposingText
        when {
            old.isEmpty() -> if (newText.isNotEmpty()) onTextCommitted?.invoke(newText)
            newText.startsWith(old) -> {
                val added = newText.substring(old.length)
                if (added.isNotEmpty()) onTextCommitted?.invoke(added)
            }
            old.startsWith(newText) -> {
                val removed = old.length - newText.length
                if (removed > 0) onDeleteBefore?.invoke(removed)
            }
            else -> {
                // Not a simple append/trim (e.g. autocorrect swapped the whole word) - replace it.
                onDeleteBefore?.invoke(old.length)
                if (newText.isNotEmpty()) onTextCommitted?.invoke(newText)
            }
        }
    }
}
