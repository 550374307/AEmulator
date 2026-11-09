package app.aemu

import app.aemu.core.HostServices
import app.aemu.core.Kernel64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings sheet shows one switch per capability only where the guest can actually reach the
 * bridge, and it decides that from [Kernel64.bridgeV1]. If a kernel tag were flipped without the
 * shim gaining a client (or the reverse), the sheet would lie in one direction or the other, so the
 * boundary is pinned here rather than left to a comment.
 */
class HostCapsUiTest {

    @Test
    fun bridgeIsOffBeforeAndroidNine() {
        for (api in 1..27) {
            assertFalse("api $api must not offer the bridge", Kernel64.forApi(api).bridgeV1)
        }
    }

    @Test
    fun bridgeIsOnFromAndroidNineThroughSeventeen() {
        for (api in 28..37) {
            assertTrue("api $api must offer the bridge", Kernel64.forApi(api).bridgeV1)
        }
    }

    @Test
    fun apiBeyondTheTableStillLandsOnATagWithTheBridge() {
        // forApi falls back to the last tag whose range ends below the asked api; 40 has no tag of
        // its own, and the answer must not be an old kernel that cannot speak the protocol.
        assertTrue(Kernel64.forApi(40).bridgeV1)
    }

    /**
     * The switch does set arithmetic on the persisted `hostCaps`, so one flipped switch must not
     * disturb the other three — the whole point of granting per instance.
     */
    @Test
    fun flippingOneSwitchLeavesTheOthersAlone() {
        val start = HostServices.DEFAULT_CAPS
        val without = start - HostServices.CLIPBOARD
        assertEquals(setOf(HostServices.PROPS, HostServices.POWER, HostServices.LMKD), without)

        val back = without + HostServices.CLIPBOARD
        assertEquals(start, back)

        // removing a capability that was already off changes nothing
        assertEquals(without, without - HostServices.CLIPBOARD)
        // adding one twice does not duplicate it
        assertEquals(start, start + HostServices.PROPS)
    }

    @Test
    fun everyDefaultCapabilityCanBeGranted() {
        // The sheet offers a switch only for capabilities it can actually grant; a name that fell out
        // of the table and out of the reserved set would render a switch that does nothing.
        val services = HostServices { }
        services.builtins(NoopHost())
        services.grant(HostServices.DEFAULT_CAPS)
        for (cap in HostServices.DEFAULT_CAPS) {
            assertTrue("$cap was not granted", services.granted(cap))
        }
    }

    private class NoopHost : HostServices.Host {
        override fun propGet(key: String): String? = null
        override fun propSet(key: String, value: String): Boolean = false
        override fun propList(): List<Pair<String, String>> = emptyList()
        override fun power(op: Int): Boolean = true
        override fun clipGet(): String? = null
        override fun clipSet(text: String): Boolean = true
    }
}
