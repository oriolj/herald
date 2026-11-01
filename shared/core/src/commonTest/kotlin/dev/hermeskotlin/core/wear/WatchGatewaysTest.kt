package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.NativeSession
import dev.hermeskotlin.core.auth.NativeTokens
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.bots.BotChats
import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.rpc.FakeGateway
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.voice.AudioApi
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.WatchGateway
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The watch lists the phone's saved gateways and moves the phone to one of them. */
class WatchGatewaysTest {
    private class Outbox : WatchOutbox {
        val messages = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun send(node: String, path: String, data: ByteArray): Boolean {
            messages += path to WearJson.decode<JsonObject>(data)!!
            return true
        }
        override suspend fun stream(node: String, path: String, data: ByteArray) = true
    }

    private val home = SavedGateway(FakeGateway.URL.value)
    private val work = SavedGateway("https://work.example.ts.net", name = "Work")
    /** Saved, but not signed in to on the phone. */
    private val lab = SavedGateway("https://lab.example.ts.net", name = "Lab")

    private class Setup(val relay: WatchRelay, val outbox: Outbox, val gateways: GatewayRepository, val links: ChatLinks)

    private suspend fun TestScope.setup(): Setup {
        val gateway = FakeGateway(backgroundScope).apply { ready = { FakeGateway.QUIET_READY } }
        gateway.start()
        val store = InMemoryKeyValueStore()
        val tokens = NativeTokens(store).apply {
            set(FakeGateway.URL, NativeSession("home-access", "home-refresh"))
            set(GatewayUrl.parse(work.url), NativeSession("work-access", "work-refresh"))
        }
        val auth = AuthApi(gateway.client, PersistentCookiesStorage(store), tokens)
        val gateways = GatewayRepository(store).apply {
            save(lab)
            save(work)
            save(home)
            setPrimary(home.url)
        }
        val sessions = SessionsApi(gateway.client)
        val bots = BotsApi(gateway.connection)
        val outbox = Outbox()
        val links = ChatLinks()
        val relay = WatchRelay(gateways, auth, ProfileStore(store), gateway.connection, sessions, AudioApi(gateway.client),
            ChatHost(gateway.connection, sessions, backgroundScope), backgroundScope, outbox, bots, BotChats(bots), SettingsStore(store, backgroundScope), links)
        return Setup(relay, outbox, gateways, links)
    }

    private suspend fun Setup.answer(requestId: String): PhoneStatus = withTimeout(10_000) {
        while (outbox.messages.none { it.first == WearPaths.STATUS && it.second["requestId"]?.jsonPrimitive?.content == requestId }) delay(10)
        val sent = outbox.messages.last { it.first == WearPaths.STATUS && it.second["requestId"]?.jsonPrimitive?.content == requestId }
        WearJson.decode<PhoneStatus>(WearJson.encode(sent.second))!!
    }

    @Test
    fun theStatusListsTheSavedGatewaysPrimaryFirst() = runTest {
        val s = setup()
        s.relay.status("watch", requestId = "r1")

        assertEquals(
            listOf(
                WatchGateway(home.url, home.label, inUse = true, signedIn = true),
                WatchGateway(lab.url, "Lab", inUse = false, signedIn = false),
                WatchGateway(work.url, "Work", inUse = false, signedIn = true),
            ),
            s.answer("r1").gateways,
        )
    }

    @Test
    fun theWatchMovesThePhoneToAnotherGateway() = runTest {
        val s = setup()
        s.relay.switchGateway("watch", work.url, "r2", accent = null)

        val status = s.answer("r2")
        assertEquals(work.url, s.gateways.current()?.url)
        assertEquals(work.url, status.gateways?.single { it.inUse }?.url)
        // The app's screens follow, through the same link the home-screen widget uses.
        assertEquals(work.url, s.links.pendingGateway.value)
    }

    @Test
    fun oneNotSignedInToStaysOnThePhoneToSignIn() = runTest {
        val s = setup()
        s.relay.switchGateway("watch", lab.url, "r3", accent = null)

        assertEquals(home.url, s.answer("r3").gateways?.single { it.inUse }?.url)
        assertEquals(home.url, s.gateways.current()?.url)
        assertNull(s.links.pendingGateway.value)
    }
}
