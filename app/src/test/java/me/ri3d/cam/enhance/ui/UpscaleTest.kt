package me.ri3d.cam.enhance.ui

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.model.ExportQuality
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Upscale screen logic (target filtering by TargetNotLarger, encoder size and frame rate, the Android 15 time limit,
 * export-quality default, engines) and the WorkManager job with a fake [me.ri3d.cam.enhance.ClipUpscaler] (progress,
 * cancel, done → derived item, failures, adoption after a restart). Pure display rules: [EnhanceUiLogicTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Android 15 time limit applies; enhanceDir() needs Process.getStartElapsedRealtime (not in Robolectric's 36 jar)
class UpscaleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val upscaler = FakeClipUpscaler(1920, 1080)
    private val enhancer = FakeFrameEnhancer()
    private val jobs = UpscaleJobs(context)
    private lateinit var repository: MediaRepository

    /** doWork calls still running; every test waits for them, so none touches a later test's WorkManager. */
    private val running = AtomicInteger()
    private val direct = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            UpscaleWorker(appContext, workerParameters, upscaler, repository)
    }
    private val recorded = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
            val worker = UpscaleWorker(appContext, workerParameters, upscaler, repository)
            return object : CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result {
                    running.incrementAndGet()
                    try {
                        return worker.doWork()
                    } finally {
                        running.decrementAndGet()
                    }
                }
            }
        }
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(recorded).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val end = System.currentTimeMillis() + 10_000
        while (running.get() > 0) {
            check(System.currentTimeMillis() < end) { "upscale workers did not finish" }
            Thread.sleep(10)
        }
        Dispatchers.resetMain()
        db.close()
        listOf("media", "enhance", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    /** A 1080p, 60 s clip on the phone (as MediaExtractor reports it). */
    private suspend fun TestScope.clip(name: String = "clip", fps: Int = 30, kind: MediaKind = MediaKind.ORIGINAL_VIDEO): MediaItem {
        if (!::repository.isInitialized) repository = MediaRepository(context, db, managerFor(RecorderSimulator()).apply { setSimulator(true) })
        val file = File(context.filesDir, "media/$name/$name.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1000)) }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setLong(MediaFormat.KEY_DURATION, 60_000_000L)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(file.path), format, ByteArray(0))
        return localItem(kind, file).also { db.mediaDao().insert(it) }
    }

    /**
     * An H.264 encoder up to [level]. Level 5: 22 080 macroblocks per frame and 589 824 per second, so 2560×1440 fits
     * at 30 fps but not at 60, and 3840×2160 never. Level 5.2: 36 864 per frame, 2 073 600 per second (4K60).
     */
    private fun encoder(level: Int = CodecProfileLevel.AVCLevel5) {
        val format = MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC) }
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setIsEncoder(true)
            .setProfileLevels(arrayOf(CodecProfileLevel().apply { profile = CodecProfileLevel.AVCProfileHigh; this.level = level }))
            .setColorFormats(intArrayOf(CodecCapabilities.COLOR_FormatSurface))
            .build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.avc.encoder").setIsEncoder(true).setCapabilities(caps).build())
    }

    private suspend fun TestScope.viewModel(item: MediaItem, quality: ExportQuality = ExportQuality.Q1440): UpscaleViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = PreferencesRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "${item.id}.preferences_pb") })
        prefs.update { it.copy(exportQuality = quality) }
        val vm = UpscaleViewModel(SavedStateHandle(mapOf("mediaId" to item.id)), repository, upscaler, enhancer, jobs, prefs)
        eventually { !vm.state.value.loading }
        return vm
    }

    private fun worker(item: MediaItem, attempts: Int = 0, enqueuedAt: Long = 0) = TestListenableWorkerBuilder.from(context, UpscaleWorker::class.java)
        .setInputData(
            workDataOf(
                UpscaleWorker.KEY_ID to item.id, UpscaleWorker.KEY_TARGET to "P1440", UpscaleWorker.KEY_ENGINE to "CLASSICAL",
                UpscaleWorker.KEY_ENQUEUED_AT to enqueuedAt,
            ),
        )
        .setRunAttemptCount(attempts)
        .setWorkerFactory(direct)
        .build()

    private fun failure(kind: UpscaleFailureKind) = ListenableWorker.Result.failure(workDataOf(UpscaleWorker.KEY_ERROR to kind.name))

    @Test
    fun `targets not larger than the source are left out, targets without an encoder are disabled`() = runTest {
        encoder()
        val vm = viewModel(clip())
        val s = vm.state.value
        assertThat(s.source).isEqualTo(ClipFacts(1920, 1080, 60_000, 30, 1000))
        assertThat(s.options.map { it.target }).containsExactly(Resolution.P1440, Resolution.P2160).inOrder()
        assertThat(s.options.map { it.encoder }).containsExactly(true, false).inOrder()
        assertThat(s.options.map { it.width to it.height }).containsExactly(2560 to 1440, 3840 to 2160).inOrder()
        assertThat(s.canStart).isTrue()

        vm.setTarget(Resolution.P2160)
        testScheduler.runCurrent()
        assertThat(vm.state.value.canStart).isFalse()
    }

    @Test
    fun `the encoder check includes the frame rate`() = runTest {
        encoder()
        val vm = viewModel(clip(fps = 60))
        assertThat(vm.state.value.source!!.fps).isEqualTo(60)
        assertThat(vm.state.value.options.map { it.encoder }).containsExactly(false, false).inOrder() // 1440p60 > level 5
        assertThat(vm.state.value.canStart).isFalse()
    }

    @Test
    fun `the export quality preselects its target when the phone can encode it, else the first that works`() = runTest {
        encoder(CodecProfileLevel.AVCLevel52)
        assertThat(viewModel(clip("a"), ExportQuality.Q2160).state.value.target).isEqualTo(Resolution.P2160)
        assertThat(viewModel(clip("b"), ExportQuality.Q1440).state.value.target).isEqualTo(Resolution.P1440)
    }

    @Test
    fun `a preferred target the phone cannot encode falls back to one it can`() = runTest {
        encoder() // level 5: no 4K
        assertThat(viewModel(clip(), ExportQuality.Q2160).state.value.target).isEqualTo(Resolution.P1440)
    }

    @Test
    fun `without any encoder nothing can start`() = runTest {
        val vm = viewModel(clip())
        assertThat(vm.state.value.options.none { it.encoder }).isTrue()
        assertThat(vm.state.value.target).isNull()
        assertThat(vm.state.value.canStart).isFalse()
    }

    @Test
    fun `the ML engine is offered only with the model, with its own estimate and the Android 15 time limit`() = runTest {
        encoder()
        upscaler.etaMs = mapOf(EnhanceEngine.ML to 6 * 3_600_000L)
        val vm = viewModel(clip())
        eventually { vm.state.value.mlAvailable }
        assertThat(vm.state.value.options.first().etaMs).isNull()
        assertThat(vm.state.value.options.first().tooLong).isFalse()

        vm.setEngine(EnhanceEngine.ML)
        eventually { vm.state.value.options.first().etaMs == 6 * 3_600_000L }
        assertThat(vm.state.value.options.first().tooLong).isTrue() // 6 h > the 5 h budget for dataSync work
        assertThat(vm.state.value.canStart).isFalse()

        enhancer.caps = caps(engines = listOf(EnhanceEngine.CLASSICAL))
        val other = viewModel(clip("other"))
        eventually { enhancer.capabilityCalls >= 2 }
        testScheduler.runCurrent()
        assertThat(other.state.value.mlAvailable).isFalse()
    }

    @Test
    fun `outputs are never upscaled`() = runTest {
        encoder()
        val vm = viewModel(clip(kind = MediaKind.UPSCALED_CLIP))
        assertThat(vm.state.value.loadError).isEqualTo(R.string.upscale_failure_not_original)
        assertThat(worker(clip("w", kind = MediaKind.UPSCALED_CLIP)).doWork()).isEqualTo(failure(UpscaleFailureKind.NOT_ORIGINAL))
        assertThat(upscaler.requests).isEmpty()
    }

    @Test
    fun `the job reports progress, survives as work and registers the clip when done`() = runTest {
        encoder()
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
        encoder()
        val vm = viewModel(clip())
        vm.start()
        eventually { upscaler.last != null && vm.state.value.job?.state == WorkInfo.State.RUNNING }

        vm.cancel()
        eventually { vm.state.value.job?.state == WorkInfo.State.CANCELLED }
        eventually { upscaler.last!!.isCancelled && running.get() == 0 }
        assertThat(vm.state.value.canStart).isTrue()
    }

    @Test
    fun `a clip finished just as the work is cancelled is registered, not orphaned`() = runTest {
        encoder()
        val item = clip()
        val vm = viewModel(item)
        val done = CompletableDeferred<UpscaleResult>()
        upscaler.job = { done }
        vm.start()
        eventually { vm.state.value.job?.state == WorkInfo.State.RUNNING }

        val result = upscaledOutput(context, item.id)
        done.complete(result) // the pipeline finished …
        vm.cancel() // … and the cancel arrives at the same moment: whichever wins, the clip is in the library
        eventually { running.get() == 0 }
        assertThat(repository.get(result.output.id)?.parentId).isEqualTo(item.id)
    }

    @Test
    fun `typed failures reach the screen and an ML failure offers classical`() = runTest {
        encoder()
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
    fun `only one upscale runs at a time`() = runTest {
        encoder()
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
        assertThat(jobs.current.first()!!.mediaId).isEqualTo(first.state.value.job!!.mediaId)
        assertThat(upscaler.requests).hasSize(1)
    }

    @Test
    fun `a restarted work adopts the clip its earlier attempt finished instead of running again`() = runTest {
        val item = clip()
        val enqueuedAt = System.currentTimeMillis() - 1_000
        val finished = upscaledOutput(context, item.id) // left behind by a killed process, not registered

        val result = worker(item, attempts = 1, enqueuedAt = enqueuedAt).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success(workDataOf(UpscaleWorker.KEY_OUTPUT_ID to finished.output.id)))
        assertThat(repository.get(finished.output.id)!!.parentId).isEqualTo(item.id)
        assertThat(upscaler.requests).isEmpty()
    }

    @Test
    fun `an older output of the same clip is not adopted`() = runTest {
        val item = clip()
        upscaledOutput(context, item.id) // from an earlier, separate upscale
        upscaler.job = { CompletableDeferred(UpscaleResult.Failed(UpscaleError.Encoder(null))) }

        val result = worker(item, attempts = 1, enqueuedAt = System.currentTimeMillis() + 60_000).doWork()

        assertThat(result).isEqualTo(failure(UpscaleFailureKind.ENCODER))
        assertThat(upscaler.requests).hasSize(1)
    }

    @Test
    fun `a vanished source or repeated system stops fail typed`() = runTest {
        val item = clip()
        item.localFile!!.delete()
        assertThat(worker(item).doWork()).isEqualTo(failure(UpscaleFailureKind.SOURCE_GONE))
        assertThat(worker(item, attempts = UpscaleWorker.MAX_ATTEMPTS).doWork()).isEqualTo(failure(UpscaleFailureKind.INTERRUPTED))
        assertThat(upscaler.requests).isEmpty()
    }
}
