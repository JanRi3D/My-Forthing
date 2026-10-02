package me.ri3d.dashcam.dashcam

import android.net.Network
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class LatestNetworkCallbackTest {
    private val emitted = mutableListOf<Network?>()
    private val callback = LatestNetworkCallback { emitted += it }
    private val a = ShadowNetwork.newInstance(1)
    private val b = ShadowNetwork.newInstance(2)

    @Test
    fun `make-before-break switch emits the new network and ignores the old loss`() {
        callback.onAvailable(a)
        callback.onAvailable(b) // Android 12+: the replacement arrives first
        callback.onLost(a)

        assertThat(emitted).containsExactly(a, b).inOrder()
    }

    @Test
    fun `losing the current network emits null`() {
        callback.onAvailable(a)
        callback.onLost(a)
        callback.onLost(a) // a repeated loss changes nothing

        assertThat(emitted).containsExactly(a, null).inOrder()
    }
}
