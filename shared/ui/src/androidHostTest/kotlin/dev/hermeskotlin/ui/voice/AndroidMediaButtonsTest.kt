package dev.hermeskotlin.ui.voice

import dev.hermeskotlin.core.voice.AndroidMediaButtons
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidMediaButtonsTest {
    private val buttons = AndroidMediaButtons(RuntimeEnvironment.getApplication())

    @Test
    fun heldUntilLetGo() {
        val close = assertNotNull(buttons.hold {})
        assertTrue(buttons.on)
        close()
        close()
        assertFalse(buttons.on)
    }

    /** End, then Start right away: the old chat lets go while the new one already has them. */
    @Test
    fun anOldChatLettingGoLateKeepsTheNewChatsButtons() {
        val old = assertNotNull(buttons.hold {})
        val new = assertNotNull(buttons.hold {})
        old()
        assertTrue(buttons.on)
        new()
        assertFalse(buttons.on)
    }
}
