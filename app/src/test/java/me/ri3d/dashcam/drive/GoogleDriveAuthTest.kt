package me.ri3d.dashcam.drive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleDriveAuthTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("drive_test", Context.MODE_PRIVATE)
    private var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var keyDropped = false
    private val store = DriveAccountStore(prefs, key = { key }, dropKey = { keyDropped = true })
    private val authorizer = FakeAuthorizer()
    private val pendingIntent = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
    private var now = 1_000L

    /** Unconfined: the stored record is loaded synchronously, so the state is final right after construction. */
    private fun TestScope.newAuth() = GoogleDriveAuth(
        authorizer, store, reasonText = { it.name }, scope = backgroundScope,
        io = UnconfinedTestDispatcher(testScheduler), clock = { now },
    )

    private val granted = Authorization.Granted("t1", setOf(DRIVE_FILE_SCOPE))
    private val noConsentScreen: suspend (PendingIntent) -> ActivityResult = { error("no consent screen expected") }

    @Test
    fun `connects silently and remembers the account across restarts`() = runTest {
        val auth = newAuth()
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)

        assertThat(auth.connectWith(chooseAccount = false, noConsentScreen).isSuccess).isTrue()

        val connected = DriveAuthState.Connected("a@example.com", setOf(DRIVE_FILE_SCOPE))
        assertThat(auth.state.value).isEqualTo(connected)
        assertThat(newAuth().state.value).isEqualTo(connected)
        assertThat(store.load()).isEqualTo(StoredDriveAccount("a@example.com", setOf(DRIVE_FILE_SCOPE), connectedAt = 1_000))
    }

    @Test
    fun `connects through the consent screen`() = runTest {
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }
        authorizer.fromIntent = { granted }
        val auth = newAuth()

        val result = auth.connectWith(false) { intent ->
            assertThat(intent).isSameInstanceAs(pendingIntent)
            ActivityResult(Activity.RESULT_OK, Intent())
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(auth.state.value).isInstanceOf(DriveAuthState.Connected::class.java)
    }

    @Test
    fun `missing Cloud configuration is reported with its status code`() = runTest {
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }
        authorizer.fromIntent = { throw ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR)) }
        val auth = newAuth()

        val error = auth.connectWith(false) { ActivityResult(Activity.RESULT_CANCELED, Intent()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(DriveError.Authorization::class.java)
        assertThat((error as DriveError.Authorization).configurationMissing).isTrue()
        assertThat(error.driveMessage()).isEqualTo(UiText.Res(R.string.drive_error_configuration, listOf(10)))
        assertThat(DriveError.Http(403, "accessNotConfigured").driveMessage()).isEqualTo(UiText.Res(R.string.drive_error_api_disabled))
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
    }

    @Test
    fun `closing the consent screen is a cancellation`() = runTest {
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }
        val auth = newAuth()

        val error = auth.connectWith(false) { ActivityResult(Activity.RESULT_CANCELED, null) }.exceptionOrNull()

        assertThat(error).isInstanceOf(DriveError.Cancelled::class.java)
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
    }

    @Test
    fun `an unticked Drive permission does not connect`() = runTest {
        authorizer.authorize = { _, _ -> Authorization.Granted("t1", emptySet()) }
        val auth = newAuth()

        assertThat(auth.connectWith(false, noConsentScreen).exceptionOrNull()).isInstanceOf(DriveError.ScopeNotGranted::class.java)
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
    }

    @Test
    fun `an unreadable account e-mail fails the connect instead of connecting half`() = runTest {
        authorizer.emailError = DriveError.Offline(IOException("no network"))
        val auth = newAuth()

        assertThat(auth.connectWith(false, noConsentScreen).exceptionOrNull()).isInstanceOf(DriveError.Offline::class.java)
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
        assertThat(store.load()).isNull()
    }

    @Test
    fun `unexpected failures become a Result, never a crash`() = runTest {
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }
        val notAComponentActivity = Robolectric.buildActivity(Activity::class.java).get()

        assertThat(newAuth().connect(notAComponentActivity).exceptionOrNull()).isInstanceOf(ClassCastException::class.java)
    }

    @Test
    fun `a failing Keystore keeps the connection in memory`() = runTest {
        val brokenStore = DriveAccountStore(prefs, key = { throw GeneralSecurityException("keystore") })
        val auth = GoogleDriveAuth(
            authorizer, brokenStore, { it.name }, backgroundScope, UnconfinedTestDispatcher(testScheduler), { now },
        )

        assertThat(auth.connectWith(false, noConsentScreen).isSuccess).isTrue()
        assertThat(auth.state.value).isInstanceOf(DriveAuthState.Connected::class.java)
        assertThat(brokenStore.load()).isNull()
    }

    @Test
    fun `accessToken re-authorises silently for the stored account`() = runTest {
        val auth = connected()
        authorizer.authorize = { _, _ -> Authorization.Granted("fresh", setOf(DRIVE_FILE_SCOPE)) }

        assertThat(auth.accessToken().getOrThrow()).isEqualTo("fresh")
        assertThat(authorizer.calls.last()).isEqualTo("authorize a@example.com false")
    }

    @Test
    fun `accessToken without an account is NotConnected`() = runTest {
        assertThat(newAuth().accessToken().exceptionOrNull()).isInstanceOf(DriveError.NotConnected::class.java)
    }

    @Test
    fun `silent re-authorisation that needs the user switches to NeedsReconnect`() = runTest {
        val auth = connected()
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }

        assertThat(auth.accessToken().exceptionOrNull()).isInstanceOf(DriveError.NeedsReconnect::class.java)

        val reconnect = DriveAuthState.NeedsReconnect(ReconnectReason.CONSENT_REQUIRED.name, "a@example.com")
        assertThat(auth.state.value).isEqualTo(reconnect)
        assertThat(newAuth().state.value).isEqualTo(reconnect) // survives a restart
        val calls = authorizer.calls.size
        assertThat(auth.accessToken().exceptionOrNull()).isInstanceOf(DriveError.NeedsReconnect::class.java)
        assertThat(authorizer.calls).hasSize(calls) // no silent retry until the user reconnects
    }

    @Test
    fun `SIGN_IN_REQUIRED from the silent path means revoked`() = runTest {
        val auth = connected()
        authorizer.authorize = { _, _ -> throw ApiException(Status(CommonStatusCodes.SIGN_IN_REQUIRED)) }

        assertThat(auth.accessToken().exceptionOrNull()).isInstanceOf(DriveError.NeedsReconnect::class.java)
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NeedsReconnect(ReconnectReason.REVOKED.name, "a@example.com"))
    }

    @Test
    fun `a 401 after refresh marks the access revoked, reconnecting restores it`() = runTest {
        val auth = connected()
        val t1 = auth.accessToken().getOrThrow()
        auth.invalidate(t1)
        assertThat(auth.state.value).isInstanceOf(DriveAuthState.Connected::class.java)
        authorizer.authorize = { _, _ -> Authorization.Granted("t2", setOf(DRIVE_FILE_SCOPE)) }
        val t2 = auth.accessToken().getOrThrow()

        auth.invalidate(t2, revoked = true)

        assertThat(auth.state.value).isEqualTo(DriveAuthState.NeedsReconnect(ReconnectReason.REVOKED.name, "a@example.com"))
        assertThat(authorizer.calls).containsAtLeast("clear t1", "clear t2").inOrder()
        assertThat(auth.connectWith(false, noConsentScreen).isSuccess).isTrue()
        assertThat(auth.state.value).isInstanceOf(DriveAuthState.Connected::class.java)
        assertThat(authorizer.calls.last { it.startsWith("authorize") }).isEqualTo("authorize a@example.com false")
    }

    @Test
    fun `a late 401 from before a reconnect does not flag the new connection`() = runTest {
        val auth = connected()
        val stale = auth.accessToken().getOrThrow()
        now = 2_000
        auth.connectWith(false, noConsentScreen).getOrThrow()

        auth.invalidate(stale, revoked = true)

        assertThat(auth.state.value).isInstanceOf(DriveAuthState.Connected::class.java)
    }

    @Test
    fun `switching to another account revokes the previous one`() = runTest {
        val auth = connected()
        authorizer.authorize = { _, _ -> Authorization.NeedsUi(pendingIntent) }
        authorizer.fromIntent = { Authorization.Granted("t9", setOf(DRIVE_FILE_SCOPE)) }
        authorizer.email = "b@example.com"

        assertThat(auth.connectWith(chooseAccount = true) { ActivityResult(Activity.RESULT_OK, Intent()) }.isSuccess).isTrue()

        assertThat(authorizer.calls).contains("authorize null true")
        assertThat(authorizer.calls).contains("revoke a@example.com")
        assertThat(auth.state.value).isEqualTo(DriveAuthState.Connected("b@example.com", setOf(DRIVE_FILE_SCOPE)))
    }

    @Test
    fun `disconnect forgets locally first and finishes the revoke even when cancelled`() = runTest {
        val auth = connected()
        val revokeGate = CompletableDeferred<Unit>()
        authorizer.revoke = { revokeGate.await() }

        val job = launch { auth.disconnect() }
        runCurrent()
        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
        assertThat(store.load()).isNull()
        assertThat(authorizer.calls).contains("revoke a@example.com")

        job.cancel()
        revokeGate.complete(Unit)
        job.join()
        assertThat(authorizer.revoked).containsExactly("a@example.com")
        assertThat(newAuth().state.value).isEqualTo(DriveAuthState.NotConnected)
    }

    @Test
    fun `disconnect offline still forgets`() = runTest {
        val auth = connected()
        authorizer.revoke = { throw IOException("offline") }

        auth.disconnect()

        assertThat(auth.state.value).isEqualTo(DriveAuthState.NotConnected)
        assertThat(newAuth().state.value).isEqualTo(DriveAuthState.NotConnected)
    }

    @Test
    fun `an unreadable stored record is dropped together with its key`() = runTest {
        prefs.edit().putString("account", "garbage").commit()

        assertThat(newAuth().state.value).isEqualTo(DriveAuthState.NotConnected)
        assertThat(keyDropped).isTrue()
        assertThat(prefs.contains("account")).isFalse()
    }

    private suspend fun TestScope.connected(): GoogleDriveAuth = newAuth().also { it.connectWith(false, noConsentScreen).getOrThrow() }

    private inner class FakeAuthorizer : DriveAuthorizer {
        var authorize: suspend (String?, Boolean) -> Authorization = { _, _ -> granted }
        var fromIntent: (Intent) -> Authorization = { error("no consent result expected") }
        var revoke: suspend () -> Unit = {}
        var email = "a@example.com"
        var emailError: Exception? = null
        val calls = mutableListOf<String>()
        val revoked = mutableListOf<String>()

        override suspend fun authorize(accountEmail: String?, chooseAccount: Boolean): Authorization {
            calls += "authorize $accountEmail $chooseAccount"
            return authorize.invoke(accountEmail, chooseAccount)
        }

        override fun fromIntent(data: Intent) = fromIntent.invoke(data)

        override suspend fun clearToken(token: String) {
            calls += "clear $token"
        }

        override suspend fun revoke(accountEmail: String) {
            calls += "revoke $accountEmail"
            revoke.invoke()
            revoked += accountEmail
        }

        override suspend fun accountEmail(token: String): String = emailError?.let { throw it } ?: email
    }
}
