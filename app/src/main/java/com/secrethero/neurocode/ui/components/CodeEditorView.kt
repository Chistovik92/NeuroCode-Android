package com.secrethero.neurocode.ui.components

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

@Composable
fun CodeEditorView(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTextSize(14f)
                setLineNumberEnabled(true)
                setWordwrap(false)
                setText(text)
                subscribeAlways<ContentChangeEvent> {
                    if (it.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                        onTextChange(this.text.toString())
                    }
                }
            }
        },
        update = { editor ->
            if (editor.text.toString() != text) {
                val line = editor.cursor.leftLine
                val column = editor.cursor.leftColumn
                editor.setText(text)
                runCatching { editor.setSelection(line, column) }
            }
        },
        onRelease = { it.release() },
    )
}
