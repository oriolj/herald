package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.WatchGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GatewayNoteTest {

    @Test
    fun saysWhichIsInUseAndWhichNeedsTheSignInOnThePhone() {
        assertEquals(R.string.wear_gateway_in_use, gatewayNote(WatchGateway("https://a.example", "Home", inUse = true)))
        assertEquals(R.string.wear_gateway_sign_in, gatewayNote(WatchGateway("https://b.example", "Lab", signedIn = false)))
        assertNull(gatewayNote(WatchGateway("https://c.example", "Work")))
    }
}
