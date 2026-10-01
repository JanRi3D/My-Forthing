package me.ri3d.cam.enhance.ui

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.Resolution
import me.ri3d.cam.enhance.UpscaleError
import me.ri3d.cam.enhance.UpscaleProgress
import me.ri3d.cam.enhance.UpscaleResult
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.eventually
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.recorder.RecorderSimulator
import java.io.File

/**
 * Upscale screen logic (target filtering by TargetNotLarger and encoder capability, estimate display, engines) and the
 * WorkManager job with a fake [me.ri3d.cam.enhance.ClipUpscaler] (progress, cancel, done → derived item, failures).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpscaleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val upscaler = FakeClipUpscaler(1920, 1080)
    private val enhancer = FakeFrameEnhancer()
    private val jobs = UpscaleJobs(context)
    private lateinit var repository: MediaRepository
    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            UpscaleWorker(appContext, workerParameters, upscaler, repository)
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "enhance", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    /** A 1080p30, 60 s clip on the phone (as MediaExtractor reports it). */
    private suspend fun TestScope.clip(name: String = "clip"): MediaItem {
        if (!::repository.isInitialized) repository = MediaRepository(context, db, managerFor(RecorderSimulator()).apply { setSimulator(true) })
        val file = File(context.filesDir, "media/$name/$name.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1000)) }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setLong(MediaFormat.KEY_DURATION, 60_000_000L)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.path), format, ByteArray(0))
        return localItem(MediaKind.ORIGINAL_VIDEO, file).also { db.mediaDao().insert(it) }
    }

    /** An H.264 encoder up to level 5 (22 080 macroblocks per frame): 2560×1440 fits, 3840×2160 does not. */
    private fun level5Encoder() {
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC) }
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(true)
            .setProfileLevels(arrayOf(CodecProfileLevel().apply { profile = CodecProfileLevel.AVCProfileHigh; level = CodecProfileLevel.AVCLevel5 }))
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatSurface))
            .build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.avc.encoder").setIsEncoder(true).setCapabilities(caps).build())
    }

    private suspend fun TestScope.viewModel(item: MediaItem): UpscaleViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = PreferencesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "${item.id}.preferences_pb") })
        val vm = UpscaleViewModel(SavedStateHandle(mapOf("mediaId" to item.id)), repository, upscaler, enhancer, jobs, prefs)
        eventually { !vm.state.value.loading }
        return vm
    }

    private suspend fun job() = jobs.current.first()

    @Test
    fun `targets not larger than the source are left out, targets without an encoder are disabled`() = runTest {
        level5Encoder()
        val vm = viewModel(clip())
        val s = vm.state.value
        assertThat(s.source).isEqualTo(ClipFacts(1920, 1080, 60_000, 30, 1000))
        assertThat(s.options.map { it.target }).containsExactly(Resolution.P1440, Resolution.P2160).inOrder()
        assertThat(s.options.map { it.encoder }).containsExactly(true, false).inOrder()
        assertThat(s.options.map { it.width to it.height }).containsExactly(2560 to 1440, 3840 to 2160).inOrder()
        assertThat(s.target).isEqualTo(Resolution.P1440) // the export-quality default
        assertThat(s.canStart).isTrue()

        vm.setTarget(Resolution.P2160)
        testScheduler.runCurrent()
        assertThat(vm.state.value.canStart).isFalse()
    }

    @Test
    fun `without any encoder nothing can start`() = runTest {
        val vm = viewModel(clip())
        assertThat(vm.state.value.options.none { it.encoder }).isTrue()
        assertThat(vm.state.value.target).isNull()
        assertThat(vm.state.value.canStart).isFalse()
    }

    @Test
    fun `size is always shown, time only when measured, the encoder reason otherwise`() {
        val measured = UpscaleOption(Resolution.P1440, 2560, 1440, encoder = true, bytes = 98_000_000, etaMs = 240_000)
        assertThat(optionTextRes(measured)).isEqualTo(R.string.upscale_option_size_time)
        assertThat(optionTextRes(measured.copy(etaMs = null))).isEqualTo(R.string.upscale_option_size)
        assertThat(optionTextRes(measured.copy(encoder = false))).isEqualTo(R.string.upscale_no_encoder)
        assertThat(duration(3 * 3_600_000L + 29 * 60_000L + 1)).isEqualTo(UiText.Res(R.string.enhance_duration_hours, listOf(3, 30)))
        assertThat(duration(59_500)).isEqualTo(UiText.Res(R.string.enhance_duration_minutes, listOf(1)))
        assertThat(duration(1)).isEqualTo(UiText.Res(R.string.enhance_duration_seconds, listOf(1)))
    }

    @Test
    fun `the ML engine is offered only with the model, and its estimate is its own`() = runTest {
        level5Encoder()
        upscaler.etaMs = mapOf(EnhanceEngine.ML to 7_200_000L)
        val vm = viewModel(clip())
        eventually { vm.state.value.mlAvailable }
        assertThat(vm.state.value.options.first().etaMs).isNull()
        vm.setEngine(EnhanceEngine.ML)
        eventually { vm.state.value.options.first().etaMs == 7_200_000L }

        enhancer.caps = caps(engines = listOf(EnhanceEngine.CLASSICAL))
        val other = viewModel(clip("other"))
        eventually { enhancer.capabilityCalls >= 2 }
        testScheduler.runCurrent()
        assertThat(other.state.value.mlAvailable).isFalse()
    }

    @Test
    fun `the job reports progress, survives as work and registers the clip when done`() = runTest {
        level5Encoder()
        val item = clip()
        val vm = viewModel(item)
        upscaler.progress = UpscaleProgress(0.5f, 60_000, 50_000_000)
        val done = CompletableDeferred<UpscaleResult>()
        upscaler.job = { done }

        vm.start()
        eventually { vm.state.value.job?.progress != null }
        val running = vm.state.value.job!!
        assertThat(running.state).isEqualTo(WorkInfo.State.RUNNING)
        assertThat(running.progress).isEqualTo(UpscaleProgress(0.5f, 60_000, 50_000_000))
        assertThat(upscaler.requests.single().target).isEqualTo(Resolution.P1440)
        assertThat(upscaler.requests.single().sourceMediaId).isEqualTo(item.id)
        assertThat(vm.state.value.canStart).isFalse()

        done.complete(upscaledOutput(context, item.id))
        eventually { vm.state.value.job?.state == WorkInfo.State.SUCCEEDED }
        val output = repository.get(vm.state.value.job!!.outputId!!)!!
        assertThat(output.kind).isEqualTo(MediaKind.UPSCALED_CLIP)
        assertThat(output.parentId).isEqualTo(item.id)
        assertThat(output.parentPositionMs).isNull()
        assertThat(output.localFile!!.isFile).isTrue()
    }

    @Test
    fun `cancel stops the pipeline`() = runTest {
        level5Encoder()
        val vm = viewModel(clip())
        vm.start()
        eventually { upscaler.last != null && vm.state.value.job?.state == WorkInfo.State.RUNNING }

        vm.cancel()
        eventually { vm.state.value.job?.state == WorkInfo.State.CANCELLED }
        eventually { upscaler.last!!.isCancelled }
        assertThat(vm.state.value.canStart).isTrue()
    }

    @Test
    fun `typed failures reach the screen and an ML failure offers classical`() = runTest {
        level5Encoder()
        val vm = viewModel(clip())
        upscaler.job = { CompletableDeferred(UpscaleResult.Failed(UpscaleError.Memory("ML frame"))) }
        vm.setEngine(EnhanceEngine.ML)
        testScheduler.runCurrent()
        vm.start()
        eventually { vm.state.value.job?.state == WorkInfo.State.FAILED }
        assertThat(vm.state.value.job!!.failure).isEqualTo(UpscaleFailureKind.MEMORY)
        assertThat(vm.state.value.job!!.engine).isEqualTo(EnhanceEngine.ML)

        upscaler.job = { CompletableDeferred() }
        vm.retryClassical()
        eventually { vm.state.value.job?.state == WorkInfo.State.RUNNING }
        assertThat(upscaler.requests.last().engine).isEqualTo(EnhanceEngine.CLASSICAL)
        assertThat(vm.state.value.job!!.engine).isEqualTo(EnhanceEngine.CLASSICAL)
    }

    @Test
    fun `every pipeline error maps to its German reason`() {
        val kinds = listOf(
            UpscaleError.Decoder(null), UpscaleError.Encoder(null), UpscaleError.Storage(null), UpscaleError.Memory(null),
            UpscaleError.TargetNotLarger(null), UpscaleError.Cancelled,
        ).map(UpscaleFailureKind::of)
        assertThat(kinds).containsExactly(
            UpscaleFailureKind.DECODER, UpscaleFailureKind.ENCODER, UpscaleFailureKind.STORAGE, UpscaleFailureKind.MEMORY,
            UpscaleFailureKind.TARGET_NOT_LARGER, UpscaleFailureKind.CANCELLED,
        ).inOrder()
        assertThat(kinds.map { it.text }.toSet()).hasSize(kinds.size)
    }

    @Test
    fun `only one upscale runs at a time`() = runTest {
        level5Encoder()
        val first = viewModel(clip("a"))
        first.start()
        eventually { first.state.value.job?.active == true }

        val b = clip("b")
        val second = viewModel(b)
        eventually { second.state.value.otherJob != null }
        assertThat(second.state.value.job).isNull()
        assertThat(second.state.value.canStart).isFalse()
        jobs.start(b.id, Resolution.P1440, EnhanceEngine.CLASSICAL) // KEEP: no second work while the first runs
        testScheduler.runCurrent()
        assertThat(job()!!.mediaId).isEqualTo(first.state.value.job!!.mediaId)
        assertThat(upscaler.requests).hasSize(1)
    }

    @Test
    fun `a vanished source or repeated system stops fail typed`() = runTest {
        val item = clip()
        item.localFile!!.delete()
        fun worker(attempts: Int) = TestListenableWorkerBuilder.from(context, UpscaleWorker::class.java)
            .setInputData(workDataOf(UpscaleWorker.KEY_ID to item.id, UpscaleWorker.KEY_TARGET to "P1440", UpscaleWorker.KEY_ENGINE to "CLASSICAL"))
            .setRunAttemptCount(attempts)
            .setWorkerFactory(factory)
            .build()

        assertThat(worker(0).doWork()).isEqualTo(ListenableWorker.Result.failure(workDataOf(UpscaleWorker.KEY_ERROR to "SOURCE_GONE")))
        assertThat(worker(UpscaleWorker.MAX_ATTEMPTS).doWork())
            .isEqualTo(ListenableWorker.Result.failure(workDataOf(UpscaleWorker.KEY_ERROR to "INTERRUPTED")))
        assertThat(upscaler.requests).isEmpty()
    }
}
