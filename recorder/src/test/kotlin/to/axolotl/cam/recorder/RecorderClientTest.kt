package to.axolotl.cam.recorder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import to.axolotl.cam.recorder.RecorderCommand.GetDeviceInfo
import to.axolotl.cam.recorder.RecorderSimulator.Received
import java.util.concurrent.CopyOnWriteArrayList

/** [SIM] End-to-end over the simulator: real framing and crypto, virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderClientTest {
    private val token = 987654

    private class Harness(
        val sim: RecorderSimulator,
        val client: RecorderClient,
        val states: List<SessionState>,
        val notes: List<RecorderNotification>,
        val diags: List<RecorderDiagnostic>,
    ) {
        val state get() = client.state.value
        fun keepAliveTimes() = diags.filterIsInstance<RecorderDiagnostic.FrameSent>().filter { it.json.contains("\"msgId\":3}") }.map { it.atMs }
    }

    private fun TestScope.harness(sim: RecorderSimulator = RecorderSimulator(token = token), collect: Boolean = true): Harness {
        val diags = CopyOnWriteArrayList<RecorderDiagnostic>()
        val client = RecorderClient({ sim }, sim.privateKeyPkcs8, backgroundScope, { testScheduler.currentTime }, { diags += it })
        val states = CopyOnWriteArrayList<SessionState>()
        val notes = CopyOnWriteArrayList<RecorderNotification>()
        if (collect) {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.state.toList(states) }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.notifications.toList(notes) }
        }
        return Harness(sim, client, states, notes, diags)
    }

    @Test
    fun fullSimulatedSession_connectNegotiateCommandNotificationStop() = runTest {
        val h = harness()
        h.sim.replies[4098] = fixture("device-info.json")

        h.client.start()
        assertThat(h.states).containsExactly(
            SessionState.Idle, SessionState.Connecting, SessionState.TcpConnected, SessionState.Negotiating,
            SessionState.Ready(token, "SIM", 0, 10),
        ).inOrder()
        assertThat(h.sim.received.first()).isEqualTo(Received(1, """{"token":0,"msgId":1,"param":{"clientType":1}}""", encrypted = false))

        val result = h.client.request(GetDeviceInfo, ::parseDeviceInfo)
        assertThat((result as RecorderResult.Ok).value.productModel).isEqualTo("EXAMPLE-MODEL")
        assertThat(h.sim.received.last()).isEqualTo(Received(2, """{"token":$token,"msgId":4098}""", encrypted = true))

        h.sim.inject(fixture("notify-sd-status.json"))
        runCurrent()
        assertThat((h.notes.single() as RecorderNotification.Normal).info).isEqualTo(NormalInfo.SdStatus(1, SdCardStatus.NORMAL, 2))

        h.client.stop()
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()
        assertThat(h.sim.received.mapNotNull { it.msgId }).doesNotContain(2) // direct disconnect, as the app does
    }

    @Test
    fun tcpConnectedIsNotReady_untilSessionReply() = runTest {
        val h = harness()
        h.sim.replyDelayMs = 4_000
        launch { h.client.start() }
        advanceTimeBy(3_999); runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Negotiating)
        assertThat(h.states).contains(SessionState.TcpConnected)
        val notReady = runCatching { h.client.send(GetDeviceInfo) }.exceptionOrNull() as RecorderException
        assertThat(notReady.error.code).isEqualTo(ErrorCodes.SEND_FAILED)
        advanceTimeBy(1); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        // the reply at 4 s cancelled the 5 s session deadline
        h.sim.replyDelayMs = 0
        advanceTimeBy(20_000); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
    }

    @Test
    fun regression_sessionDeadline_result4096AndDisconnectAt5sNot10s() = runTest {
        val h = harness()
        h.sim.silentMsgIds += 1
        val start = launch { h.client.start() }
        advanceTimeBy(4_999); runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Negotiating)
        assertThat(h.sim.closed).isFalse()
        advanceTimeBy(1); runCurrent()
        val reason = (h.state as SessionState.Failed).reason
        assertThat(reason.code).isEqualTo(ErrorCodes.SESSION_TIMEOUT) // local 4096, not a recorder rval
        assertThat(reason.source).isEqualTo(RecorderError.Source.TIMEOUT)
        assertThat(h.sim.closed).isTrue() // DashcamApi monitor effect: disconnect
        assertThat(start.isCompleted).isTrue() // SessionApi monitor effect: result 4096 released start()
    }

    @Test
    fun sessionRejected_orKeyUndecryptable_fails() = runTest {
        val rejected = harness()
        rejected.sim.rvalOverrides[1] = 101
        rejected.client.start()
        assertThat((rejected.state as SessionState.Failed).reason.code).isEqualTo(101)
        assertThat(rejected.sim.closed).isTrue()

        val sim = RecorderSimulator(token = token)
        val wrongKey = RecorderSimulator().privateKeyPkcs8
        val client = RecorderClient({ sim }, wrongKey, backgroundScope, { testScheduler.currentTime })
        client.start()
        val reason = (client.state.value as SessionState.Failed).reason
        assertThat(reason.code).isEqualTo(ErrorCodes.SESSION_KEY_INVALID)
        assertThat(reason.rawJson).contains("\"aescode\":\"***\"")
        assertThat(sim.closed).isTrue()
    }

    @Test
    fun invalidRsaKey_failsWithoutConnecting() = runTest {
        val sim = RecorderSimulator()
        val client = RecorderClient({ sim }, "not a key".toByteArray(), backgroundScope, { testScheduler.currentTime })
        client.start()
        assertThat((client.state.value as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.SESSION_KEY_INVALID)
        assertThat(sim.connectAttempts).isEqualTo(0)
    }

    @Test
    fun regression_heartbeatLoss_disconnectsAfterAbout11s() = runTest {
        val h = harness()
        h.sim.silentMsgIds += 3
        h.client.start()
        val t0 = currentTime
        advanceTimeBy(10_999); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        assertThat(h.keepAliveTimes()).containsExactly(t0 + 4_000, t0 + 8_000).inOrder()
        advanceTimeBy(1); runCurrent()
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.HEARTBEAT_LOST)
        assertThat(h.sim.closed).isTrue()
    }

    @Test
    fun heartbeat_successfulKeepalivesKeepSessionAlive() = runTest {
        val h = harness()
        h.client.start()
        advanceTimeBy(60_000); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        assertThat(h.keepAliveTimes()).hasSize(15) // 4 s, 8 s, ... 60 s
        assertThat(h.keepAliveTimes().zipWithNext { a, b -> b - a }.toSet()).containsExactly(4_000L)
    }

    @Test
    fun heartbeat_onlySuccessfulKeepaliveResetsBeatTime() = runTest {
        val h = harness()
        h.sim.rvalOverrides[3] = 1 // answered, but not successful
        h.client.start()
        advanceTimeBy(10_999); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        advanceTimeBy(1); runCurrent()
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.HEARTBEAT_LOST)
    }

    @Test
    fun regression_eventAutoAck_freshSequence_sentBeforeDispatch() = runTest {
        val h = harness()
        h.client.start() // seq 1
        h.client.request(GetDeviceInfo, { }) // seq 2
        var seenAtDispatch: List<Received>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.client.notifications.first { it is RecorderNotification.Event }
            seenAtDispatch = h.sim.received.toList()
        }
        h.sim.inject(fixture("event-manual.json"), seq = 77)
        runCurrent()
        val ack = Received(3, """{"rval":0,"msgId":16385,"token":$token}""", encrypted = true)
        assertThat(h.sim.received.last()).isEqualTo(ack)
        assertThat(seenAtDispatch!!.last()).isEqualTo(ack)
        val event = h.notes.filterIsInstance<RecorderNotification.Event>().single()
        assertThat(event.list.first().type).isEqualTo(EventType.MANUAL_RECORD)
    }

    @Test
    fun eventAutoAck_sentEvenWithoutListeners() = runTest {
        val h = harness(collect = false)
        h.client.start()
        h.sim.inject(fixture("event-manual.json"))
        runCurrent()
        assertThat(h.sim.received.last().json).isEqualTo("""{"rval":0,"msgId":16385,"token":$token}""")
        assertThat(h.sim.received.last().seq).isEqualTo(2)
    }

    @Test
    fun connect_oneImmediateRetry_thenFailWithMinus101() = runTest {
        val retried = harness()
        retried.sim.failConnectAttempts = 1
        retried.client.start()
        assertThat(retried.sim.connectAttempts).isEqualTo(2)
        assertThat(retried.state).isInstanceOf(SessionState.Ready::class.java)
        assertThat(currentTime).isEqualTo(0) // no backoff

        val failed = harness()
        failed.sim.failConnectAttempts = 2
        failed.client.start()
        assertThat(failed.sim.connectAttempts).isEqualTo(2)
        assertThat((failed.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.CONNECT_FAILED)
    }

    @Test
    fun duplicateStart_ignoredWhileConnectingOrConnected() = runTest {
        val h = harness()
        h.sim.connectDelayMs = 500
        launch { h.client.start() }
        launch { h.client.start() }
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Connecting)
        advanceTimeBy(500); runCurrent()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        h.client.start()
        assertThat(h.sim.connectAttempts).isEqualTo(1)
    }

    @Test
    fun request_failureCodesAndRawJsonKept() = runTest {
        val h = harness()
        h.client.start()
        h.sim.replies[12292] = fixture("error-no-sd-card.json")
        val noCard = (h.client.request(RecorderCommand.TakePhoto(), ::parseCaptureResult) as RecorderResult.Failed).error
        assertThat(noCard.code).isEqualTo(201)
        assertThat(noCard.source).isEqualTo(RecorderError.Source.RECORDER)
        assertThat(noCard.rawJson).isEqualTo(fixture("error-no-sd-card.json"))
        assertThat(ErrorCodes.appMeaning(noCard.code)).isEqualTo(ErrorCodes.AppMeaning.NO_SD_CARD)

        h.sim.replies[4098] = fixture("reply-missing-rval.json")
        val missing = (h.client.request(GetDeviceInfo, ::parseDeviceInfo) as RecorderResult.Failed).error
        assertThat(missing.code).isEqualTo(ErrorCodes.MISSING_RVAL)

        val unparsable = h.client.request(RecorderCommand.GetStorageInfo(), { error("boom") }) as RecorderResult.Failed
        assertThat(unparsable.error.code).isEqualTo(ErrorCodes.BAD_REPLY)
    }

    @Test
    fun receivedStopSession_disconnects() = runTest {
        val h = harness()
        h.client.start()
        val reply = h.client.send(RecorderCommand.StopSession)
        assertThat(reply.msgId).isEqualTo(2)
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()

        h.client.start() // the simulator accepts a new connection
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)
        h.sim.inject("""{"msgId":2,"rval":0}""", seq = 55) // unsolicited
        runCurrent()
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.DISCONNECTED)
    }

    @Test
    fun peerClose_failsPendingRequests() = runTest {
        val h = harness()
        h.client.start()
        h.sim.silentMsgIds += 4098
        val pending = launch {
            val r = h.client.request(GetDeviceInfo, ::parseDeviceInfo) as RecorderResult.Failed
            assertThat(r.error.code).isEqualTo(ErrorCodes.DISCONNECTED)
        }
        runCurrent()
        h.sim.close()
        runCurrent()
        assertThat(pending.isCompleted).isTrue()
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.DISCONNECTED)
    }

    @Test
    fun fragmentedCombinedAndGarbledStream_endToEnd() = runTest {
        val h = harness()
        h.sim.fragmentSize = 3
        h.client.start()
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java)

        h.sim.injectRaw("garbage!".toByteArray())
        val n1 = SessionCrypto.encrypt(fixture("notify-heartbeat-start.json"), h.sim.sessionKeyHex).toByteArray()
        val n2 = SessionCrypto.encrypt(fixture("notify-rec-status.json"), h.sim.sessionKeyHex).toByteArray()
        h.sim.injectRaw(FrameCodec.encode(0, n1) + FrameCodec.encode(0, n2)) // two frames in one read
        h.sim.replies[4099] = fixture("storage-info.json")
        val storage = h.client.request(RecorderCommand.GetStorageInfo(), ::parseStorageInfo) as RecorderResult.Ok
        assertThat(storage.value.totalSpace).isEqualTo(30528L)
        runCurrent()

        assertThat(h.notes.map { (it as RecorderNotification.Normal).info })
            .containsExactly(NormalInfo.HeartBeatStart, NormalInfo.RecStatus(1, 7)).inOrder()
        assertThat(h.diags.filterIsInstance<RecorderDiagnostic.Garbage>()).isNotEmpty()
    }

    @Test
    fun unmatchedReply_isKeptAsNotification() = runTest {
        val h = harness()
        h.client.start()
        h.sim.inject(fixture("photo-reply.json"), seq = 999) // e.g. a second burst reply
        runCurrent()
        val u = h.notes.single() as RecorderNotification.Unmatched
        assertThat(u.seq).isEqualTo(999)
        assertThat(parseCaptureResult(u.reply).filePath).isEqualTo("/example/photo.jpg")
    }

    @Test
    fun diagnosticsAndToString_neverLeakSessionSecrets() = runTest {
        val h = harness()
        h.client.start()
        h.client.request(RecorderCommand.SetSettings(SettingsPatch(wifi = WifiParam(0, "EXAMPLE", "Secret987", 0))), { })
        h.sim.replies[4097] = fixture("all-settings-wifi-mode0.json").replace("Example123", "Secret987")
        val settings = h.client.request(RecorderCommand.GetAllSettings, ::parseSettings) as RecorderResult.Ok
        assertThat(settings.value.global.wifi?.passwd).isEqualTo("Secret987") // usable for resubmission
        h.sim.inject(fixture("event-manual.json"))
        runCurrent()

        val frames = h.diags.mapNotNull {
            when (it) {
                is RecorderDiagnostic.FrameSent -> it.hex
                is RecorderDiagnostic.FrameReceived -> it.hex
                else -> null
            }
        }
        assertThat(frames).isNotEmpty()
        val texts = h.diags.map { it.toString() } + frames.map { String(unhex(it.substringAfter(' ')), Charsets.ISO_8859_1) } +
            h.states.map { it.toString() } + h.notes.map { it.toString() } +
            listOf(settings.toString(), settings.value.toString(), settings.value.global.toString(), settings.reply.toString())
        val all = texts.joinToString("\n")
        assertThat(all).doesNotContain(token.toString())
        assertThat(all).doesNotContain(h.sim.sessionKeyHex)
        assertThat(all).doesNotContain("Secret987")
        assertThat(all).contains("\"aescode\":\"***\"")
        assertThat(Regex("\"aescode\":\"(?!\\*\\*\\*\")").containsMatchIn(all)).isFalse()
    }

    @Test
    fun regression_ignoredCommand_timesOutWhileKeepalivesSucceed() = runTest {
        val h = harness()
        h.client.start()
        h.sim.silentMsgIds += 4098
        var result: RecorderResult<DeviceInfo>? = null
        launch { result = h.client.request(GetDeviceInfo, ::parseDeviceInfo) }
        advanceTimeBy(9_999); runCurrent()
        assertThat(result).isNull()
        advanceTimeBy(1); runCurrent()
        val error = (result as RecorderResult.Failed).error
        assertThat(error.code).isEqualTo(ErrorCodes.REQUEST_TIMEOUT)
        assertThat(error.source).isEqualTo(RecorderError.Source.TIMEOUT)
        assertThat(h.state).isInstanceOf(SessionState.Ready::class.java) // keepalives at 4 s and 8 s succeeded

        // the pending entry is gone: a late reply is surfaced, not matched
        val seq = h.sim.received.last { it.msgId == 4098 }.seq
        h.sim.inject("""{"msgId":4098,"rval":0}""", seq = seq)
        runCurrent()
        assertThat((h.notes.single() as RecorderNotification.Unmatched).seq).isEqualTo(seq)

        val quick = async { h.client.request(GetDeviceInfo, ::parseDeviceInfo, timeoutMs = 500) }
        advanceTimeBy(500); runCurrent()
        assertThat((quick.await() as RecorderResult.Failed).error.code).isEqualTo(ErrorCodes.REQUEST_TIMEOUT)
    }

    @Test
    fun stopSessionIgnoredByRecorder_stillClosesAfterTimeout() = runTest {
        val h = harness()
        h.client.start()
        h.sim.silentMsgIds += 2
        val sent = async { runCatching { h.client.send(RecorderCommand.StopSession) } }
        advanceTimeBy(10_000); runCurrent()
        assertThat((sent.await().exceptionOrNull() as RecorderException).error.code).isEqualTo(ErrorCodes.REQUEST_TIMEOUT)
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()
        assertThat(h.keepAliveTimes()).isEmpty() // heartbeat stopped before 2 was sent, as traced
    }

    @Test
    fun cancellingClientScope_closesSocketAndFailsPending() = runTest {
        val sim = RecorderSimulator(token = token)
        val parent = Job(backgroundScope.coroutineContext[Job])
        val client = RecorderClient({ sim }, sim.privateKeyPkcs8, CoroutineScope(backgroundScope.coroutineContext + parent), { testScheduler.currentTime })
        client.start()
        sim.silentMsgIds += 4098
        val pending = async { client.request(GetDeviceInfo, ::parseDeviceInfo) }
        runCurrent()
        parent.cancel()
        runCurrent()
        assertThat(sim.closed).isTrue()
        assertThat(client.state.value).isEqualTo(SessionState.Idle)
        assertThat((pending.await() as RecorderResult.Failed).error.code).isEqualTo(ErrorCodes.DISCONNECTED)
    }

    @Test
    fun stopWhileConnecting_endsIdle_socketClosedNoSession() = runTest {
        val h = harness()
        h.sim.connectDelayMs = 500
        val start = launch { h.client.start() }
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Connecting)
        h.client.stop()
        assertThat(h.state).isEqualTo(SessionState.Idle)
        advanceTimeBy(500); runCurrent()
        assertThat(start.isCompleted).isTrue()
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()
        assertThat(h.sim.received).isEmpty()
    }

    @Test
    fun stopWhileNegotiating_endsIdle_deadlineCancelled() = runTest {
        val h = harness()
        h.sim.silentMsgIds += 1
        val start = launch { h.client.start() }
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Negotiating)
        h.client.stop()
        runCurrent()
        assertThat(start.isCompleted).isTrue()
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()
        advanceTimeBy(10_000); runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Idle) // no late 4096
    }

    @Test
    fun cancellingStartWhileNegotiating_closesAndEndsIdle() = runTest {
        val h = harness()
        h.sim.silentMsgIds += 1
        val start = launch { h.client.start() }
        runCurrent()
        start.cancel()
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Idle)
        assertThat(h.sim.closed).isTrue()
        advanceTimeBy(10_000); runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Idle)
    }

    @Test
    fun writeFailure_failsRequestAndSession() = runTest {
        val h = harness()
        h.client.start()
        h.sim.failWrites = true
        val r = h.client.request(GetDeviceInfo, ::parseDeviceInfo) as RecorderResult.Failed
        assertThat(r.error.code).isEqualTo(ErrorCodes.SEND_FAILED)
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.DISCONNECTED)
        assertThat(h.sim.closed).isTrue()
    }

    @Test
    fun connectTimeout_3sPerAttempt_oneImmediateRetry() = runTest {
        val h = harness()
        h.sim.connectDelayMs = 60_000
        launch { h.client.start() }
        advanceTimeBy(2_999); runCurrent()
        assertThat(h.sim.connectAttempts).isEqualTo(1)
        advanceTimeBy(1); runCurrent()
        assertThat(h.sim.connectAttempts).isEqualTo(2) // retry right after the first 3 s timeout
        assertThat(h.state).isEqualTo(SessionState.Connecting)
        advanceTimeBy(3_000); runCurrent()
        assertThat((h.state as SessionState.Failed).reason.code).isEqualTo(ErrorCodes.CONNECT_FAILED)
        assertThat(currentTime).isEqualTo(6_000)
    }

    @Test
    fun trailingNonNulPaddingAfterJson_isIgnored() = runTest {
        val h = harness()
        h.client.start()
        h.sim.inject(fixture("notify-heartbeat-start.json") + "\u0003\u0003\u0003") // e.g. PKCS#7-style bytes
        runCurrent()
        assertThat((h.notes.single() as RecorderNotification.Normal).info).isEqualTo(NormalInfo.HeartBeatStart)
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
