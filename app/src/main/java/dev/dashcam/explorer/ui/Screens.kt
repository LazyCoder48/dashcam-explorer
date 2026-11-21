package dev.dashcam.explorer.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.dashcam.explorer.data.Node
import dev.dashcam.explorer.data.Source
import dev.dashcam.explorer.domain.FileKind
import dev.dashcam.explorer.domain.Fmt
import dev.dashcam.explorer.domain.HealthDay
import dev.dashcam.explorer.domain.HealthEvent
import dev.dashcam.explorer.domain.HealthLogParser
import dev.dashcam.explorer.domain.Match
import dev.dashcam.explorer.domain.clipTimeMs
import dev.dashcam.explorer.media.Outputs
import dev.dashcam.explorer.media.SyncedPlayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun KeepScreenOn() {
    val activity = LocalActivity.current
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

// ----------------------------------------------------------------------------- Viewer

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun ViewerScreen(vm: AppViewModel, screen: Screen.Viewer) {
    val source = vm.source ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pair = remember(source, screen.path) { source.pair(screen.path) }
    if (pair == null) {
        Column { TopBar("File not found", onBack = { vm.back() }); Notice("This file is no longer in the source. Rescan and try again.", Warn) }
        return
    }
    val isVideo = pair.kind == FileKind.VIDEO
    val player = remember {
        SyncedPlayer(context.applicationContext, scope,
            source.camera?.let { dev.dashcam.explorer.media.CameraDataSource.Factory(context.applicationContext, it, source::node) })
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    KeepScreenOn()

    fun goTo(n: Node?, autoPlay: Boolean = false): Boolean {
        n ?: return false
        vm.swap(Screen.Viewer(n.path, autoPlay))
        return true
    }
    // Over Wi-Fi, videos start playing while they download (see CameraDataSource); photos
    // are small and are fetched first, then shown from the copy on this device.
    var pending by remember(screen.path) { mutableStateOf(if (source.isRemote && !isVideo) "Downloading…" else null) }
    var downloading by remember(screen.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(screen.path) {
        val f = pair.front ?: pair.single
        try {
            if (!source.isRemote) {
                if (isVideo) player.load(f?.let(source::uri), pair.rear?.let(source::uri), autoPlay = screen.autoPlay)
                else player.load(null, null, false)
                return@LaunchedEffect
            }
            // keep downloads for this pair and the next one; stop any others
            val nextPair = pair.next?.let { source.pair(it.path) }
            source.camera?.cancelExcept(
                listOfNotNull(f, pair.rear, nextPair?.front ?: nextPair?.single, nextPair?.rear).mapTo(HashSet()) { it.path })
            if (isVideo) player.load(f?.let(source::playUri), pair.rear?.let(source::playUri), autoPlay = screen.autoPlay)
            else player.load(null, null, false)
            // Front and rear come down together; one progress figure covers both.
            val wanted = listOfNotNull(f, pair.rear)
            val done = LongArray(wanted.size)
            val total = LongArray(wanted.size) { i -> if (source.camera?.cached(wanted[i]) != null) 0 else wanted[i].size }
            coroutineScope {
                wanted.forEachIndexed { i, n ->
                    launch {
                        source.fetch(n) { d, t ->
                            done[i] = d; total[i] = t
                            val text = "Downloading… ${done.sum() * 100 / total.sum().coerceAtLeast(1)}%"
                            if (isVideo) downloading = text else pending = text
                        }
                    }
                }
            }
            pending = null
            downloading = null
            // while this one plays, get the next one ready so auto-play has no wait
            if (vm.autoNext) pair.next?.let { nx ->
                source.pair(nx.path)?.let { np ->
                    coroutineScope { listOfNotNull(np.front ?: np.single, np.rear).forEach { launch { source.fetch(it) } } }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (pending != null || downloading != null) { downloading = null; pending = vm.disconnectMessage(e) }
        }
    }
    player.onFinished = { if (vm.autoNext) goTo(pair.next, autoPlay = true) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(screen.path) { runCatching { focus.requestFocus() } }
    var menu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key
            val back = k == Key.DirectionUp || k == Key.PageUp || k == Key.P || (k == Key.DirectionLeft && (e.isShiftPressed || !isVideo))
            val fwd = k == Key.DirectionDown || k == Key.PageDown || k == Key.N || (k == Key.DirectionRight && (e.isShiftPressed || !isVideo))
            when {
                back -> goTo(pair.prev)
                fwd -> goTo(pair.next)
                !isVideo -> return@onPreviewKeyEvent false
                k == Key.Spacebar || k == Key.K -> player.toggle()
                k == Key.DirectionLeft -> player.seekBy(-5000)
                k == Key.DirectionRight -> player.seekBy(5000)
                k == Key.M -> player.toggleMute()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // stacked by default (front on top); side by side when the window is clearly wide
            val sideBySide = maxWidth > maxHeight * 1.9f
            val note = if (pair.match == Match.NEAREST) "closest in time" else null
            val panes: @Composable (Modifier) -> Unit = { m ->
                if (pair.single != null) {
                    Pane(m, "File", pair.single!!, source, player.front.takeIf { isVideo }, null, pending) { player.toggle() }
                } else {
                    Pane(m, "Front", pair.front, source, player.front.takeIf { isVideo }, note.takeIf { pair.side == dev.dashcam.explorer.domain.Camera.REAR }, pending) { player.toggle() }
                    Pane(m, "Rear", pair.rear, source, player.rear.takeIf { isVideo }, note.takeIf { pair.side == dev.dashcam.explorer.domain.Camera.FRONT }, pending) { player.toggle() }
                }
            }
            if (sideBySide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) { panes(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) { panes(Modifier.weight(1f).fillMaxWidth()) }
        }

        // ---- shared control bar ----
        Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (isVideo) {
                SeekRow(player)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                IconButton(onClick = { goTo(pair.prev) }, enabled = pair.prev != null, modifier = Modifier.testTag("prev")) { Icon(Icons.Filled.SkipPrevious, "Previous file") }
                if (isVideo) {
                    IconButton(onClick = { player.seekBy(-5000) }) { Icon(Icons.Filled.Replay5, "Back 5 seconds") }
                    FilledIconButton(onClick = { player.toggle() }, modifier = Modifier.testTag("play")) {
                        Icon(if (player.wantPlay) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.wantPlay) "Pause" else "Play")
                    }
                    IconButton(onClick = { player.seekBy(5000) }) { Icon(Icons.Filled.Forward5, "Forward 5 seconds") }
                }
                IconButton(onClick = { goTo(pair.next) }, enabled = pair.next != null, modifier = Modifier.testTag("next")) { Icon(Icons.Filled.SkipNext, "Next file") }
                if (isVideo) {
                    Box {
                        TextButton(onClick = { speedMenu = true }) { Text("${player.speed.toString().removeSuffix(".0")}×") }
                        DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                            for (s in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)) DropdownMenuItem(
                                text = { Text("${s.toString().removeSuffix(".0")}×") }, onClick = { player.changeSpeed(s); speedMenu = false })
                        }
                    }
                    IconButton(onClick = { player.toggleMute() }) {
                        Icon(if (player.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (player.muted) "Sound off" else "Sound on")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.setAutoNextPref(!vm.autoNext) }) {
                        Checkbox(checked = vm.autoNext, onCheckedChange = { vm.setAutoNextPref(it) }, modifier = Modifier.testTag("autoNext"))
                        Text("Auto-play next", color = Muted, fontSize = 13.sp)
                    }
                    if (pair.front != null && pair.rear != null) {
                        OutlinedButton(onClick = { player.pause(); vm.exportCombined(pair) }, modifier = Modifier.align(Alignment.CenterVertically).testTag("export")) { Text("Export combined") }
                    }
                }
                Spacer(Modifier.weight(1f))
                val whenText = remember(pair.opened.name) {
                    clipTimeMs(pair.opened.name)?.let { SimpleDateFormat("d MMM yyyy, h:mm:ss a", Locale.getDefault()).format(Date(it)) }
                }
                downloading?.let {
                    Text("$it · ", color = Accent, fontSize = 12.5.sp, modifier = Modifier.align(Alignment.CenterVertically).testTag("downloading"))
                }
                InfoLine(player, whenText, "${pair.index} of ${pair.count}", showSync = isVideo && pair.front != null && pair.rear != null,
                    modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = { vm.back() }) { Text("Close") }
            }
        }
    }
    JobDialog(vm)
}

// The player's position changes five times a second. Reading it inside these two small
// composables means only they are redrawn on each tick, not the whole viewer and its videos.
@Composable
private fun SeekRow(player: SyncedPlayer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val max = player.durationMs.toFloat().coerceAtLeast(1f)
        Slider(
            value = player.positionMs.toFloat().coerceIn(0f, max),
            onValueChange = { player.scrub(it.toLong()) },
            onValueChangeFinished = { player.scrubDone() },
            valueRange = 0f..max,
            modifier = Modifier.weight(1f).testTag("seek"),
        )
        Text("  ${Fmt.duration(player.positionMs)} / ${Fmt.duration(player.durationMs)}", color = Muted, fontSize = 13.sp,
            modifier = Modifier.testTag("time"))
    }
}

@Composable
private fun InfoLine(player: SyncedPlayer, whenText: String?, place: String, showSync: Boolean, modifier: Modifier) {
    Text(
        listOfNotNull(whenText, place, "sync ${player.driftMs} ms".takeIf { showSync }).joinToString(" · "),
        color = Muted, fontSize = 12.5.sp, modifier = modifier.testTag("info"),
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun Pane(modifier: Modifier, label: String, node: Node?, source: Source, slot: SyncedPlayer.Slot?, note: String?, pending: String?, onTap: () -> Unit) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        var photoSize by remember(node?.path) { mutableStateOf<String?>(null) }
        when {
            node == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("No matching ${label.lowercase()} file", fontWeight = FontWeight.SemiBold)
                Text("The ${label.lowercase()} camera has no file for this moment.", color = Muted)
            }
            pending != null -> Text(pending, color = Muted, modifier = Modifier.padding(24.dp).testTag("pending"))
            slot != null && slot.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("This video could not be played", color = Warn, fontWeight = FontWeight.SemiBold)
                Text(slot.error ?: "", color = Muted, fontSize = 12.sp)
            }
            slot != null -> {
              AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        player = slot.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
              )
              // The tap target and its screen-reader label sit on a plain layer above the
              // video: semantics put on the embedded view itself are not reported.
              Box(Modifier.matchParentSize().clickable(onClick = onTap).semantics { contentDescription = "$label video" })
            }
            else -> {
                val uri = source.uri(node)
                val context = LocalContext.current
                LaunchedEffect(uri) {
                    photoSize = withContext(Dispatchers.IO) {
                        runCatching {
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                            if (o.outWidth > 0) "${o.outWidth}×${o.outHeight}" else null
                        }.getOrNull()
                    }
                }
                AsyncImage(model = uri, contentDescription = "$label photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        if (slot != null && slot.buffering && node != null && slot.error == null)
            Text("Buffering…", color = Muted, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp))

        // camera, file name, then size on the card and picture size
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(Color(0xA0000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(), color = Accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (node != null) {
                Text("  ${node.name}${note?.let { " · $it" } ?: ""}", color = Muted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val dims = slot?.videoSize?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" } ?: photoSize
                Text("  " + listOfNotNull(Fmt.size(node.size), dims).joinToString(" · "), fontSize = 12.sp,
                    modifier = Modifier.testTag("size$label"))
            }
        }
    }
}

/** Progress, result and errors for an export; shown over whichever screen started it. */
@Composable
fun JobDialog(vm: AppViewModel) {
    val j = vm.job ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!j.running) vm.dismissJob() },
        title = { Text(j.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    j.error != null -> Text(j.error, color = Warn)
                    j.file != null -> {
                        Text("Done: ${Fmt.size(j.file.length())}")
                        j.savedMessage?.let { Text(it, color = Accent) }
                    }
                    else -> {
                        LinearProgressIndicator(progress = { (j.progress.toFloat() / j.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(if (j.total == 100) "${j.progress}%" else "${"%,d".format(j.progress)} of ${"%,d".format(j.total)} photos", color = Muted)
                    }
                }
            }
        },
        confirmButton = {
            if (j.file != null) Row {
                TextButton(onClick = { context.startActivity(Outputs.shareIntent(context, j.file)) }) { Text("Share") }
                Button(onClick = { vm.saveJobToGallery() }, enabled = j.savedMessage == null, modifier = Modifier.testTag("saveGallery")) { Text("Save to gallery") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.dismissJob() }, modifier = Modifier.testTag("jobClose")) { Text(if (j.running) "Cancel" else "Close") } },
    )
}

// ----------------------------------------------------------------------------- Timelapse

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
fun TimelapseScreen(vm: AppViewModel, screen: Screen.Timelapse) {
    val context = LocalContext.current
    var fps by remember { mutableIntStateOf(15) }
    var loop by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(screen.path, fps) { saved = null; vm.buildTimelapse(screen.path, fps) }
    val player = remember { ExoPlayer.Builder(context.applicationContext).build() }
    DisposableEffect(Unit) { onDispose { player.release(); vm.dismissJob() } }
    KeepScreenOn()
    val info = vm.timelapse
    LaunchedEffect(info?.file) {
        if (info != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(info.file)))
            player.prepare(); player.playWhenReady = true
        } else player.stop()
    }
    LaunchedEffect(loop) { player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    val position by produceState(0L, info) {
        while (true) { value = player.currentPosition; kotlinx.coroutines.delay(250) }
    }
    val day = screen.path.substringBeforeLast('/').substringAfterLast('/')

    Column(Modifier.fillMaxSize()) {
        TopBar("Timelapse $day", onBack = { vm.back() })
        val j = vm.job
        if (info != null) {
            val clock = SimpleDateFormat("h:mm a", LocalConfiguration.current.locales[0])
            val span = if (info.firstMs != null && info.lastMs != null) " · covers ${clock.format(Date(info.firstMs))} – ${clock.format(Date(info.lastMs))}" else ""
            Text(
                "${"%,d".format(info.frames)} photos · ${info.cameras}$span · ${Fmt.duration(info.frames * 1000L / info.fps)} long · ${Fmt.size(info.file.length())}",
                color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("tlStats"),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                j?.error != null -> Text(j.error, color = Warn, modifier = Modifier.padding(24.dp))
                info == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp).widthIn(max = 420.dp)) {
                    Text((j?.title ?: "Building the timelapse") + "…", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("tlPhase"))
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { if (j == null) 0f else j.progress.toFloat() / j.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    if (j != null && j.total != 100) Text("${"%,d".format(j.progress)} of ${"%,d".format(j.total)} photos", color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
                else -> AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; setShutterBackgroundColor(android.graphics.Color.BLACK); controllerAutoShow = false } },
                    onRelease = { it.player = null }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        FlowRow(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text("Photos per second", color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
            for (f in listOf(8, 15, 30)) FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f") }, modifier = Modifier.testTag("fps$f"))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { loop = !loop }) {
                Checkbox(loop, { loop = it }); Text("Loop", color = Muted)
            }
            if (info != null) {
                Text("Photo ${"%,d".format((position * info.fps / 1000 + 1).coerceAtMost(info.frames.toLong()))} of ${"%,d".format(info.frames)}",
                    color = Muted, modifier = Modifier.align(Alignment.CenterVertically).testTag("tlNow"))
                Spacer(Modifier.weight(1f))
                saved?.let { Text(it, color = Accent, modifier = Modifier.align(Alignment.CenterVertically)) }
                TextButton(onClick = { context.startActivity(Outputs.shareIntent(context, info.file)) }) { Text("Share") }
                Button(onClick = {
                    scope.launch { saved = runCatching { Outputs.saveToGallery(context, info.file, info.saveName); "Saved to Movies/DashcamExplorer" }.getOrElse { "Could not save: ${it.message}" } }
                }, enabled = saved == null, modifier = Modifier.testTag("tlSave")) { Text("Save to gallery") }
            }
        }
    }
}

// ----------------------------------------------------------------------------- Health

@Composable
fun HealthScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadHealth() }
    val days = vm.health
    var openDay by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Camera health", onBack = { vm.back() })
        when {
            vm.healthError != null -> Notice(vm.healthError!!, Warn)
            days == null -> Text("Reading the camera's logs…", color = Muted, modifier = Modifier.padding(16.dp))
            days.isEmpty() -> Text("No health logs were found in this source.", color = Muted, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("health"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val total = days.sumOf { it.restarts }
                val cold = days.sumOf { it.cold }
                val hard = days.sumOf { it.hard }
                val max = days.maxOf { it.restarts }.coerceAtLeast(1)
                item {
                    Text("$total restarts in ${days.size} days", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("healthTotal"))
                    Text("$cold normal · $hard forced · ${days.first().day} to ${days.last().day}", color = Muted)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Accent)); Text("  normal (“cold”)   ", color = Muted, fontSize = 12.sp)
                        Box(Modifier.size(10.dp).background(Warn)); Text("  forced (“hard”)", color = Muted, fontSize = 12.sp)
                    }
                }
                items(days.size) { i ->
                    val d = days[i]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openDay = if (openDay == d.day) null else d.day }.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.day, modifier = Modifier.width(110.dp), fontSize = 13.5.sp)
                            Row(Modifier.weight(1f).height(14.dp)) {
                                val other = d.restarts - d.cold - d.hard
                                if (d.cold > 0) Box(Modifier.weight(d.cold.toFloat()).fillMaxHeight().background(Accent))
                                if (d.hard > 0) Box(Modifier.weight(d.hard.toFloat()).fillMaxHeight().background(Warn))
                                if (other > 0) Box(Modifier.weight(other.toFloat()).fillMaxHeight().background(Muted))
                                val rest = max - d.restarts
                                if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
                            }
                            Text("${d.restarts}", modifier = Modifier.width(40.dp).padding(start = 8.dp), fontSize = 13.5.sp)
                        }
                        if (openDay == d.day) DayDetail(d)
                    }
                }
                if (HealthLogParser.frequentForced(days)) item {
                    Notice("Forced restarts are frequent. That usually means unstable power to the camera (a loose cable, a weak hardwire kit, or the car battery dropping while parked) or the camera crashing.", Warn)
                }
                if (HealthLogParser.nightlyRestart(days)) item {
                    Text("The camera restarts at about 2:00 AM on most nights. That looks scheduled.", color = Muted)
                }
                val settings = days.flatMap { d -> d.settings.map { d.day to it } }
                if (settings.isNotEmpty()) {
                    item { SectionLabel("Setting changes") }
                    items(settings.size) { i ->
                        val (day, s) = settings[i]
                        Row {
                            Text("$day ${s.time.take(5)}", color = Muted, modifier = Modifier.width(170.dp), fontSize = 13.5.sp)
                            Text(if (s.masked) "${s.name} changed (value hidden)" else "${s.name} → ${s.value}", fontSize = 13.5.sp)
                        }
                    }
                }
                val sd = days.flatMap { d -> d.sdCard.map { d.day to it } }
                if (sd.isNotEmpty()) {
                    item { SectionLabel("Memory card events") }
                    items(sd.size) { i ->
                        Row {
                            Text("${sd[i].first} ${sd[i].second.time.take(5)}", color = Muted, modifier = Modifier.width(170.dp), fontSize = 13.5.sp)
                            Text(sd[i].second.message, fontSize = 13.5.sp)
                        }
                    }
                }
                item {
                    Text("“Normal” and “forced” are this app's reading of the log's “cold” and “hard”; the manufacturer does not document them. " +
                        "The camera's log keeps its Wi-Fi password as readable text on the card, so anyone with the card can read it. This app never shows it.",
                        color = Muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 14.dp).widthIn(max = 640.dp))
                }
            }
        }
    }
}

@Composable
private fun DayDetail(d: HealthDay) {
    Column(Modifier.padding(start = 110.dp, top = 4.dp)) {
        for (e in d.events.filterIsInstance<HealthEvent.Reboot>()) {
            val kind = when (e.kind) { HealthEvent.Reboot.Kind.COLD -> "normal"; HealthEvent.Reboot.Kind.HARD -> "forced"; else -> e.raw }
            Text("${e.time}  $kind", color = if (e.kind == HealthEvent.Reboot.Kind.HARD) Warn else Muted, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
