package chat.donzi.localtavern.ui.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PinInputTest {

    @Test
    fun typingSixDigits_showsEachDigit_andSubmitsOnce() = runComposeUiTest {
        var value by mutableStateOf("")
        var completions = 0
        setContent {
            PinInput(value = value, onValueChange = { value = it }, onComplete = { completions++ }, enabled = true)
        }
        onNode(hasSetTextAction()).performTextInput("123456")
        waitForIdle()
        assertEquals("123456", value)
        for (digit in "123456") onNodeWithText(digit.toString()).assertIsDisplayed()
        assertEquals(1, completions, "The sixth digit must submit exactly once")
    }

    @Test
    fun partialEntry_doesNotSubmit() = runComposeUiTest {
        var value by mutableStateOf("")
        var completions = 0
        setContent {
            PinInput(value = value, onValueChange = { value = it }, onComplete = { completions++ }, enabled = true)
        }
        onNode(hasSetTextAction()).performTextInput("123")
        waitForIdle()
        assertEquals("123", value)
        assertEquals(0, completions, "Three digits must not submit")
        onNodeWithText("1").assertIsDisplayed()
        onNodeWithText("2").assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun nonDigits_areFiltered_andPastingFillsAllBoxes() = runComposeUiTest {
        var value by mutableStateOf("")
        var completions = 0
        setContent {
            PinInput(value = value, onValueChange = { value = it }, onComplete = { completions++ }, enabled = true)
        }
        onNode(hasSetTextAction()).performTextInput("12a456")
        waitForIdle()
        assertEquals("12456", value, "Non-digits must be dropped")
        assertEquals(0, completions, "Five digits must not submit")
        onNode(hasSetTextAction()).performTextInput("7")
        waitForIdle()
        assertEquals("124567", value)
        assertEquals(1, completions, "Completing the code must submit")
    }
}
