package to.axolotl.cam.live

import android.content.pm.ActivityInfo
import android.graphics.RenderEffect
import android.os.Build
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.delay
import to.axolotl.cam.R
import to.axolotl.cam.core.navigation.Connection
import to.axolotl.cam.core.navigation.Live
import to.axolotl.cam.core.theme.LocalAxoColors
import to.axolotl.cam.core.ui.AxoTopBar
import to.axolotl.cam.core.ui.LocalSnackbarHostState
import to.axolotl.cam.core.ui.SectionHeader
import to.axolotl.cam.dashcam.RecorderConnectionState
import to.axolotl.cam.dashcam.errorText
import to.axolotl.cam.recorder.RecorderResult
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Route [Live]. Phase 4 slots: [extraControls] (Plates / Upscale buttons, next to Screenshot), [overlay] (drawn over
 * the video; `videoRect` is the letterboxed video in the overlay's coordinates, px) and [renderEffect] (applied to
 * the video surface, API 31+). The owners compute them from their own state.
 */
fun NavGraphBuilder.liveGraph(
    navController: NavController,
    extraControls: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.(videoRect: Rect) -> Unit = {},
    renderEffect: @Composable () -> RenderEffect? = { null },
) {
    composable<Live> {
        LiveScreen(
            onBack = { navController.navigateUp() },
            onConnect = { navController.navigate(Connection) { launchSingleTop = true } },
            extraControls = extraControls,
            overlay = overlay,
            renderEffect = renderEffect(),
        )
    }
}

@Composable
fun LiveScreen(
    onBack: () -> Unit,
    onConnect: () -> Unit,
    extraControls: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.(videoRect: Rect) -> Unit = {},
    renderEffect: RenderEffect? = null,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val videoSize by viewModel.videoSize.collectAsStateWithLifecycle()
    val command by viewModel.command.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHostState.current
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val ready = connection is RecorderConnectionState.Ready

    // The stream runs while this screen is started; the rotation into full screen is not a stop.
    LifecycleStartEffect(viewModel) {
        viewModel.onForeground(true)
        onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.onForeground(false) }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbar.showSnackbar(resources.getString(it.message)) }
    }

    val video: @Composable (frame: Modifier, box: Modifier) -> Unit = { frame, box ->
        VideoArea(connection, stream, videoSize, viewModel, onConnect, overlay, renderEffect, frame, box)
    }

    if (fullscreen) {
        val exit = { fullscreen = false }
        ImmersiveLandscape()
        BackHandler(onBack = exit)
        val focus = remember { FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent {
                    if (it.key != Key.Escape) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyUp) exit()
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            video(Modifier.fillMaxSize(), Modifier.fillMaxSize())
            IconButton(onClick = exit, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                Icon(painterResource(R.drawable.ic_live_fullscreen_exit), stringResource(R.string.live_fullscreen_exit), tint = Color.White)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        return
    }

    Scaffold(
        topBar = {
            AxoTopBar(stringResource(R.string.live_title), onBack = onBack) {
                IconButton(onClick = { fullscreen = true }, enabled = ready) {
                    Icon(painterResource(R.drawable.ic_live_fullscreen), stringResource(R.string.live_fullscreen))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 16:9 for the video; a state message that does not fit at large font sizes may make it taller.
                video(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxWidth * 9 / 16)
                        .clip(RoundedCornerShape(24.dp)),
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                ScreenshotButton(enabled = stream == StreamState.Playing || stream == StreamState.Buffering, onClick = viewModel::screenshot)
                extraControls()
            }
            RecorderControls(ready, command, viewModel::takePhoto, viewModel::record)
        }
    }
}

@Composable
private fun VideoArea(
    connection: RecorderConnectionState,
    stream: StreamState,
    videoSize: IntSize?,
    viewModel: LiveViewModel,
    onConnect: () -> Unit,
    overlay: @Composable BoxScope.(Rect) -> Unit,
    renderEffect: RenderEffect?,
    modifier: Modifier,
    videoModifier: Modifier,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (connection is RecorderConnectionState.Ready) {
                Box(videoModifier) {
                    var videoRect by remember { mutableStateOf(Rect.Zero) }
                    val aspect = videoSize?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
                    AndroidView(
                        factory = { TextureView(it) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .aspectRatio(aspect)
                            .onPlaced { videoRect = it.boundsInParent() },
                        update = { view ->
                            viewModel.attach(view)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(renderEffect)
                        },
                        onRelease = viewModel::detach,
                    )
                    if (stream == StreamState.Playing || stream == StreamState.Buffering) {
                        Badges(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(12.dp))
                    }
                    overlay(videoRect)
                }
            }
            StateMessage(connection, stream, viewModel::retry, onConnect)
        }
    }
}

@Composable
private fun StateMessage(connection: RecorderConnectionState, stream: StreamState, onRetry: () -> Unit, onConnect: () -> Unit) {
    when (connection) {
        is RecorderConnectionState.Ready -> when (stream) {
            StreamState.Off, StreamState.Loading -> Progress(stringResource(R.string.live_loading))
            StreamState.Buffering -> Progress(stringResource(R.string.live_buffering))
            StreamState.Playing -> Unit
            is StreamState.Failed -> Message(
                stringResource(R.string.live_failed),
                stringResource(R.string.live_failed_code, stream.code),
                stringResource(R.string.action_retry),
                onRetry,
            )
        }
        RecorderConnectionState.Connecting -> Progress(stringResource(R.string.dashcam_state_connecting))
        RecorderConnectionState.TcpConnected -> Progress(stringResource(R.string.dashcam_state_tcp))
        RecorderConnectionState.Negotiating -> Progress(stringResource(R.string.dashcam_state_negotiating))
        else -> Message(
            stringResource(R.string.live_connect_first),
            when (connection) {
                RecorderConnectionState.NoWifi -> stringResource(R.string.dashcam_state_no_wifi)
                is RecorderConnectionState.WrongWifi -> stringResource(R.string.dashcam_state_wrong_wifi)
                is RecorderConnectionState.Error -> errorText(connection.error)
                else -> stringResource(R.string.home_dashcam_disconnected)
            },
            stringResource(R.string.dashcam_open_connection),
            onConnect,
        )
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        Modifier
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Badge { Text(text, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun Message(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Badges(modifier: Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge {
            Box(Modifier.size(8.dp).background(LocalAxoColors.current.recording, CircleShape))
            Text(stringResource(R.string.live_badge))
        }
        PhoneClock()
    }
}

/** The phone's clock, labelled as such: the recorder's own time is not known here. */
@Composable
private fun PhoneClock() {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val format = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM) }
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1_000L - now.nano / 1_000_000)
        }
    }
    Badge { Text(stringResource(R.string.live_phone_time, format.format(now)), fontFamily = FontFamily.Monospace) }
}

@Composable
private fun Badge(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White, LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            content()
        }
    }
}

@Composable
private fun ScreenshotButton(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(80.dp), enabled = enabled, shape = RoundedCornerShape(28.dp)) {
            Icon(
                painterResource(R.drawable.ic_live_screenshot),
                contentDescription = stringResource(R.string.live_screenshot_action),
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            stringResource(R.string.live_screenshot),
            modifier = Modifier.clearAndSetSemantics { }, // the button already says it
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Commands that act on the recorder's SD card (not the phone), only with a Ready session. */
@Composable
private fun RecorderControls(ready: Boolean, command: CommandUi, onPhoto: (burst: Boolean) -> Unit, onRecord: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.live_recorder_controls))
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { onPhoto(false) }, enabled = ready && !command.photoBusy) { Text(stringResource(R.string.live_photo)) }
            FilledTonalButton(onClick = { onPhoto(true) }, enabled = ready && !command.photoBusy) { Text(stringResource(R.string.live_burst)) }
            val left = command.recordSecondsLeft
            FilledTonalButton(onClick = onRecord, enabled = ready && left == null) {
                Text(if (left == null) stringResource(R.string.live_record) else stringResource(R.string.live_record_running, left))
            }
        }
        val note = Modifier.padding(horizontal = 16.dp)
        if (command.recordSecondsLeft != null) Hint(R.string.live_record_note, note)
        if (command.photoBusy) Hint(R.string.live_waiting, note)
        val resources = LocalResources.current
        command.last?.let { outcome ->
            Text(
                commandMessage(outcome) { id, args -> resources.getString(id, *args) },
                modifier = note.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = if (outcome.result is RecorderResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (command.extraReplies > 0) {
            Text(
                pluralStringResource(
                    R.plurals.live_extra_replies,
                    command.extraReplies,
                    command.extraReplies,
                    command.lastExtraPath ?: stringResource(R.string.live_extra_no_path),
                ),
                modifier = note.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Hint(@StringRes text: Int, modifier: Modifier) {
    Text(stringResource(text), modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Landscape with hidden system bars (swipe shows them briefly). Restored only when full screen ends, not on rotation. */
@Composable
private fun ImmersiveLandscape() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@get:StringRes
private val LiveEvent.message: Int
    get() = when (this) {
        LiveEvent.NoFrame -> R.string.live_screenshot_no_frame
        is LiveEvent.ScreenshotSaved -> if (inGallery) R.string.live_screenshot_saved else R.string.live_screenshot_saved_app
        LiveEvent.ScreenshotFailed -> R.string.live_screenshot_failed
    }
