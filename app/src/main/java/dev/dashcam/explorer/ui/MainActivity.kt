package dev.dashcam.explorer.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dashcam.explorer.data.Node
import dev.dashcam.explorer.data.SourceAccess
import dev.dashcam.explorer.domain.FileKind
import dev.dashcam.explorer.domain.Fmt
import dev.dashcam.explorer.domain.clipTimeMs
import dev.dashcam.explorer.domain.tagOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFF6FCF9F)
val AccentSoft = Color(0xFF223A2E)
val Panel = Color(0xFF1E211E)
val Line = Color(0xFF33382F)
val Muted = Color(0xFF969E92)
val Warn = Color(0xFFF0A060)
val KindColor = mapOf(
    FileKind.VIDEO to Color(0xFF7EA6F5), FileKind.PHOTO to Color(0xFFE0B45A),
    FileKind.LOG to Color(0xFFC394EA), FileKind.OTHER to Color(0xFF9AA196),
)

private val Colors = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF0D1F16), secondary = Accent,
    background = Color(0xFF161816), surface = Panel, surfaceVariant = Color(0xFF262A25),
    onBackground = Color(0xFFE6E9E2), onSurface = Color(0xFFE6E9E2), onSurfaceVariant = Muted,
    outline = Line, error = Warn,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: AppViewModel = viewModel()
                    BackHandler(enabled = vm.screen.let { vm.canGoBack }) { vm.back() }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (val s = vm.screen) {
                            Screen.Sources -> SourcesScreen(vm)
                            Screen.Explorer -> ExplorerScreen(vm)
                            is Screen.Viewer -> ViewerScreen(vm, s)
                            is Screen.Timelapse -> TimelapseScreen(vm, s)
                            Screen.Health -> HealthScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

private val ROW_DATE = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())   // main thread only

fun dateText(ms: Long?): String = if (!Fmt.isRealDate(ms)) "–" else ROW_DATE.format(Date(ms!!))

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        else Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        actions()
    }
}

// ----------------------------------------------------------------------------- Sources

@Composable
fun SourcesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.open(uri, persist = true)
    }
    val volumePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { vm.open(it, persist = true) }
    }
    LaunchedEffect(Unit) { vm.refreshRemembered() }
    val volumes = remember(vm.remembered) { SourceAccess.removableVolumes(context) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Dashcam Explorer", onBack = if (vm.source != null) ({ vm.back() }) else null)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.scanning) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Scanning… ${"%,d".format(vm.scanCount)} files", color = Muted)
                }
            }
            vm.error?.let { e -> item { Notice(e, Warn) } }

            if (volumes.isNotEmpty()) {
                item { SectionLabel("Plugged in now") }
                items(volumes.size) { i ->
                    val v = volumes[i]
                    val label = v.getDescription(context) ?: "Removable storage"
                    SourceCard(
                        title = label,
                        subtitle = if (SourceAccess.looksQubo(label)) "Looks like a Qubo card. Tap, then confirm the folder." else "Removable storage. Tap, then confirm the folder.",
                        current = false, enabled = true,
                        onClick = { volumePicker.launch(SourceAccess.pickerIntentFor(v)) },
                    )
                }
            }
            item { SectionLabel("Straight from the camera") }
            item {
                SourceCard(
                    title = "Dashcam over Wi-Fi",
                    subtitle = "First join the camera's Wi-Fi network (QUBO_…) in this device's Wi-Fi settings. Clips are downloaded before they play.",
                    current = vm.source?.isRemote == true, enabled = !vm.scanning,
                    onClick = { vm.openWifi() },
                )
            }
            if (vm.remembered.isNotEmpty()) {
                item { SectionLabel("Opened before") }
                items(vm.remembered.size) { i ->
                    val r = vm.remembered[i]
                    SourceCard(
                        title = r.label,
                        subtitle = if (r.reachable) r.where else "${r.where} · not connected",
                        current = vm.source?.treeUri == r.uri, enabled = r.reachable,
                        onClick = { vm.open(r.uri) },
                        onForget = { vm.forget(r.uri) },
                    )
                }
            }
            item {
                Button(onClick = { picker.launch(null) }, modifier = Modifier.padding(top = 6.dp).testTag("chooseFolder")) {
                    Text("Choose a folder…")
                }
            }
            item {
                Text(
                    "Plug the dashcam's memory card into this device with a USB card reader, or choose any folder that holds a copy of the card. " +
                        "Pick the folder that contains QUBO. The app only reads; it never changes the card.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 560.dp).padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp))

@Composable
fun Notice(text: String, color: Color) {
    Text(text, color = color, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(14.dp))
}

@Composable
private fun SourceCard(title: String, subtitle: String, current: Boolean, enabled: Boolean, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 720.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SdCard, null, tint = if (enabled) Accent else Muted)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.Unspecified else Muted)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (current) Text("showing", color = Accent, fontSize = 12.sp)
        if (onForget != null) IconButton(onClick = onForget) { Icon(Icons.Filled.Close, "Forget this folder", tint = Muted) }
    }
}

// ----------------------------------------------------------------------------- Explorer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplorerScreen(vm: AppViewModel) {
    val source = vm.source ?: return
    val rows = vm.rows
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val cursor = vm.cursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    // While the filter box has focus, letters, Space and Enter belong to it, not to the tree.
    var filterFocused by remember { mutableStateOf(false) }

    fun activate(row: TreeRow) {
        val n = row.node
        when {
            n.isDir -> vm.toggle(n)
            n.kind == FileKind.VIDEO || n.kind == FileKind.PHOTO -> vm.go(Screen.Viewer(n.path))
        }
    }
    LaunchedEffect(cursor, rows.size) {
        // keep the keyboard cursor on screen
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        val at = cursor + HEADER_ITEMS
        if (at <= first || at >= last) listState.animateScrollToItem((at - 3).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || rows.isEmpty()) return@onPreviewKeyEvent false
            if (filterFocused) {
                // Down or Escape leaves the filter box and returns to the tree
                if (e.key == Key.DirectionDown || e.key == Key.Escape) { runCatching { focus.requestFocus() }; return@onPreviewKeyEvent true }
                return@onPreviewKeyEvent false
            }
            val row = rows[cursor]
            val n = row.node
            when (e.key) {
                Key.DirectionDown -> vm.cursor = (cursor + 1).coerceAtMost(rows.size - 1)
                Key.DirectionUp -> vm.cursor = (cursor - 1).coerceAtLeast(0)
                Key.MoveHome -> vm.cursor = 0
                Key.MoveEnd -> vm.cursor = rows.size - 1
                Key.PageDown -> vm.cursor = (cursor + 15).coerceAtMost(rows.size - 1)
                Key.PageUp -> vm.cursor = (cursor - 15).coerceAtLeast(0)
                Key.DirectionRight ->
                    if (n.isDir && !row.open) vm.setOpen(n, true)
                    else if (n.isDir && cursor + 1 < rows.size && rows[cursor + 1].depth > row.depth) vm.cursor = cursor + 1
                Key.DirectionLeft ->
                    if (n.isDir && row.open && vm.filter.isBlank()) vm.setOpen(n, false)
                    else (cursor - 1 downTo 0).firstOrNull { rows[it].depth < row.depth }?.let { vm.cursor = it }
                Key.Spacebar, Key.Enter, Key.NumPadEnter -> activate(row)
                Key.T -> source.timelapseOf(n)?.let { vm.go(Screen.Timelapse(it.path)) } ?: return@onPreviewKeyEvent false
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        TopBar(source.label, onBack = null) {
            if (vm.scanning) CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            TextButton(onClick = { vm.rescan() }, modifier = Modifier.testTag("rescan")) { Text("Rescan") }
            OutlinedButton(onClick = { vm.go(Screen.Sources) }, modifier = Modifier.testTag("changeSource")) { Text("Change source") }
        }
        vm.error?.let { Notice(it, Warn) }
        val wide = LocalConfiguration.current.screenWidthDp > 700
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("tree")) {
            item { SummaryCards(vm) }
            item {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    OutlinedTextField(
                        value = vm.filter, onValueChange = { vm.filter = it; vm.cursor = 0 },
                        placeholder = { Text("Filter by file or folder name…") }, singleLine = true,
                        modifier = Modifier.weight(1f).widthIn(min = 220.dp).testTag("filter").onFocusChanged { filterFocused = it.isFocused },
                    )
                    OutlinedButton(onClick = { vm.expandFolders() }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Expand folders") }
                    OutlinedButton(onClick = { vm.filter = ""; vm.collapseAll() }, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Collapse all") }
                }
            }
            if (rows.isEmpty()) item { Text("Nothing matches “${vm.filter}”.", color = Muted, modifier = Modifier.padding(24.dp)) }
            itemsIndexed(rows, key = { _, r -> r.node.path }, contentType = { _, r -> r.node.isDir }) { i, row ->
                TreeRowView(vm, row, selected = i == cursor, rootSize = source.root.size.coerceAtLeast(1), wide = wide) {
                    vm.cursor = i
                    activate(row)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val HEADER_ITEMS = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryCards(vm: AppViewModel) {
    val s = vm.source ?: return
    FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val u = vm.usage
        // "97% full" only makes sense when the chosen folder is (nearly) the whole volume
        val wholeVolume = u != null && u.first > 0 && s.root.size > (u.first - u.second) * 0.8
        Card("Storage".takeIf { wholeVolume } ?: "Folder size") {
            if (wholeVolume && u != null) {
                val used = u.first - u.second
                val pct = used.toFloat() / u.first
                Big("${(pct * 100).toInt()}% full")
                LinearProgressIndicator(progress = { pct }, color = if (pct > 0.9f) Warn else Accent, trackColor = Line,
                    modifier = Modifier.padding(vertical = 6.dp).width(170.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                Small("${Fmt.size(used)} used of ${Fmt.size(u.first)} · ${Fmt.size(u.second)} free")
            } else {
                Big(Fmt.size(s.root.size))
                Small(if (s.isRemote) "over Wi-Fi · sizes are rounded" else "chosen folder")
            }
        }
        Card("Contents") {
            Big("${"%,d".format(s.root.files)} files")
            Small("in ${"%,d".format(s.root.dirs)} folders")
        }
        Card("By type") {
            val labels = mapOf(FileKind.VIDEO to "Videos", FileKind.PHOTO to "Photos", FileKind.LOG to "Logs & text", FileKind.OTHER to "Other")
            val total = vm.totals.values.sumOf { it.bytes }.coerceAtLeast(1)
            Row(Modifier.padding(vertical = 6.dp).width(260.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Line)) {
                for ((k, t) in vm.totals.entries.sortedBy { it.key }) {
                    val w = t.bytes.toFloat() / total
                    if (w > 0.002f) Box(Modifier.weight(w).height(8.dp).background(KindColor.getValue(k)))
                }
            }
            for ((k, t) in vm.totals.entries.sortedBy { it.key }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(KindColor.getValue(k)))
                    Small("  ${labels.getValue(k)} ${"%,d".format(t.count)} · ${Fmt.size(t.bytes)}")
                }
            }
        }
        Card("Source") {
            Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Small(s.where)
            Small(if (vm.showingCached) "From your last visit · refreshing…" else "Scanned in ${vm.scanMs} ms")
        }
        if (vm.hasHealthLogs) {
            AssistChip(onClick = { vm.go(Screen.Health) }, label = { Text("Camera health summary") },
                modifier = Modifier.align(Alignment.CenterVertically).testTag("healthChip"))
        }
    }
}

@Composable
private fun Card(label: String, content: @Composable () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 16.dp, vertical = 12.dp).widthIn(min = 150.dp)) {
        Text(label.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        content()
    }
}

@Composable private fun Big(t: String) = Text(t, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
@Composable private fun Small(t: String) = Text(t, color = Muted, fontSize = 12.5.sp)

@Composable
private fun TreeRowView(vm: AppViewModel, row: TreeRow, selected: Boolean, rootSize: Long, wide: Boolean, onClick: () -> Unit) {
    val n = row.node
    val source = vm.source ?: return
    val openable = n.isDir || n.kind == FileKind.VIDEO || n.kind == FileKind.PHOTO
    run {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) AccentSoft else Color.Transparent)
                .clickable(enabled = openable, onClick = onClick)
                .semantics { contentDescription = (if (n.isDir) "Folder " else "File ") + n.name }
                .padding(start = (12 + row.depth * 18).dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (n.isDir && n.children.isNotEmpty())
                Icon(if (row.open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
            else Spacer(Modifier.width(18.dp))
            val (icon, tint) = when {
                n.isDir -> Icons.Filled.Folder to Accent
                n.kind == FileKind.VIDEO -> Icons.Filled.Movie to KindColor.getValue(FileKind.VIDEO)
                n.kind == FileKind.PHOTO -> Icons.Filled.Image to KindColor.getValue(FileKind.PHOTO)
                n.kind == FileKind.LOG -> Icons.Filled.Description to KindColor.getValue(FileKind.LOG)
                else -> Icons.AutoMirrored.Filled.InsertDriveFile to KindColor.getValue(FileKind.OTHER)
            }
            Icon(icon, null, tint = tint, modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
            val date = remember(n) { dateText(if (n.isDir) n.modifiedMs else clipTimeMs(n.name) ?: n.modifiedMs) }
            val tag = remember(n) { if (n.isDir) null else tagOf(n.name) }
            val timelapse = remember(n) { source.timelapseOf(n) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        n.name.ifEmpty { source.label }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (n.isDir) FontWeight.Medium else FontWeight.Normal,
                        fontFamily = if (n.isDir) FontFamily.Default else FontFamily.Monospace,
                        fontSize = if (n.isDir) 15.sp else 13.sp, modifier = Modifier.weight(1f, fill = false),
                    )
                    tag?.let { Text("  $it", color = Muted, fontSize = 11.5.sp) }
                    timelapse?.let { tl ->
                        Spacer(Modifier.width(8.dp))
                        Pill("▶ Timelapse") { vm.go(Screen.Timelapse(tl.path)) }
                    }
                    if (n.isDir && n.name == "DeviceHealth") {
                        Spacer(Modifier.width(8.dp))
                        Pill("Summary") { vm.go(Screen.Health) }
                    }
                }
                if (!wide) Text(
                    listOfNotNull(Fmt.size(n.size), if (n.isDir) "${"%,d".format(n.files)} files" else null, date.takeIf { it != "–" }).joinToString(" · "),
                    color = Muted, fontSize = 12.sp,
                )
            }
            if (wide) {
                if (n.isDir) LinearProgressIndicator(
                    progress = { (n.size.toFloat() / rootSize).coerceIn(0f, 1f) }, color = Accent, trackColor = Line, drawStopIndicator = {},
                    modifier = Modifier.padding(horizontal = 10.dp).width(60.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                ) else Spacer(Modifier.width(80.dp))
                Text(Fmt.size(n.size), modifier = Modifier.width(84.dp), fontSize = 13.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(if (n.isDir) "%,d".format(n.files) else "", color = Muted, modifier = Modifier.width(70.dp), fontSize = 13.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text(date, color = Muted, modifier = Modifier.width(180.dp), fontSize = 13.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text, color = Accent, fontSize = 11.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(AccentSoft).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}
