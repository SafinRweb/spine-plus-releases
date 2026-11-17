package app.spine.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * What a text box holds, for a box whose words are kept somewhere else: the words and
 * where the cursor is. The cursor starts after the last letter, and goes back there
 * whenever the words are changed from outside (a pasted link, Clear, coming back to a
 * screen that kept what was typed), so typing always carries on from the end and never
 * from the start.
 */
@Composable
fun rememberTextBox(text: String): MutableState<TextFieldValue> {
    val box = remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (box.value.text != text) box.value = TextFieldValue(text, TextRange(text.length))
    return box
}
