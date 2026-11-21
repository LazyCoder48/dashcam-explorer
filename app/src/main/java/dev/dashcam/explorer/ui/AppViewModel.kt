package dev.dashcam.explorer.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dashcam.explorer.data.Node
import dev.dashcam.explorer.data.Source
import dev.dashcam.explorer.data.SourceAccess
import dev.dashcam.explorer.data.TreeCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import dev.dashcam.explorer.data.WifiCamera
import kotlinx.coroutines.async
import dev.dashcam.explorer.domain.FileKind
import dev.dashcam.explorer.domain.HealthDay
import dev.dashcam.explorer.domain.HealthLogParser
import dev.dashcam.explorer.domain.clipTimeMs
import dev.dashcam.explorer.media.CombinedExporter
import dev.dashcam.explorer.media.Outputs
import dev.dashcam.explorer.media.TimelapseBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Sources : Screen
    data object Explorer : Screen
    data class Viewer(val path: String, val autoPlay: Boolean = false) : Screen
    data class Timelapse(val path: String) : Screen
    data object Health : Screen
}

data class RememberedSource(val uri: Uri, val label: String, val where: String, val reachable: Boolean)

@Immutable
data class TreeRow(val node: Node, val depth: Int, val open: Boolean)

data class TypeTotals(val count: Int, val bytes: Long)

/** State of a long job (export or timelapse build). */
data class JobState(
    val title: String, val progress: Int = 0, val total: Int = 100,
    val file: File? = null, val saveName: String? = null, val error: String? = null,
    val savedMessage: String? = null,
) {
    val running get() = file == null && error == null
}

data class TimelapseInfo(
    val day: String, val frames: Int, val cameras: String, val firstMs: Long?, val lastMs: Long?,
    val fps: Int, val file: File, val saveName: String,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("settings", 0)

    // ---- navigation (a tiny back stack; the app has five screens) ----
    private val stack = ArrayDeque<Screen>()
    var screen by mutableStateOf<Screen>(Screen.Sources); private set
    fun go(s: Screen) { stack.addLast(screen); screen = s }
    /** Replace the current screen (previous/next file) without growing the stack. */
    fun swap(s: Screen) { screen = s }
    /** True when Back has somewhere to go inside the app (otherwise it leaves the app). */
    val canGoBack: Boolean get() = stack.isNotEmpty()
    /** A newly opened source starts a fresh history: Sources, then its Explorer. */
    private fun showExplorer() { stack.clear(); stack.addLast(Screen.Sources); screen = Screen.Explorer }
    fun back(): Boolean {
        val prev = stack.removeLastOrNull() ?: return false
        screen = prev
        return true
    }

    // ---- sources ----
    var remembered by mutableStateOf(listOf<RememberedSource>()); private set
    var source by mutableStateOf<Source?>(null); private set
    var scanning by mutableStateOf(false); private set
    var scanCount by mutableIntStateOf(0); private set
    var scanMs by mutableStateOf(0L); private set
    var error by mutableStateOf<String?>(null)
    var usage by mutableStateOf<Pair<Long, Long>?>(null); private set
    var totals by mutableStateOf(mapOf<FileKind, TypeTotals>()); private set
    private var scanJob: Job? = null

    /** Asks the storage provider about each remembered folder, so it runs off the main thread. */
    fun refreshRemembered() {
        viewModelScope.launch {
            remembered = withContext(Dispatchers.IO) {
                SourceAccess.remembered(ctx).map {
                    val (label, where) = SourceAccess.describe(it)
                    RememberedSource(it, label, where, SourceAccess.reachable(ctx, it))
                }
            }
        }
    }

    fun forget(uri: Uri) { SourceAccess.forget(ctx, uri); refreshRemembered() }

    /** Called with a folder the user just picked, or a remembered one. */
    fun open(uri: Uri, persist: Boolean = false) {
        if (persist) SourceAccess.persist(ctx, uri)
        load {
            val (label, where) = SourceAccess.describe(uri)
            showThenRefresh("saf:$uri", { root -> Source(uri, label, where, root) }) {
                SourceAccess.scan(ctx, uri) { scanCount = it }
            }
        }
    }

    /** The dashcam's own Wi-Fi: the device must already be joined to the camera's network. */
    fun openWifi() = load {
        val cam = WifiCamera.find(ctx) ?: throw NoCameraException()
        cam.trim()
        showThenRefresh("wifi:${cam.base}", { root -> Source(Uri.parse(cam.base), "Dashcam (Wi-Fi)", cam.base.removePrefix("http://"), root, cam) }) {
            cam.scan { scanCount = it }
        }
    }

    class NoCameraException : Exception()

    /** True while the tree on screen came from the last visit and a fresh scan is still running. */
    var showingCached by mutableStateOf(false); private set
    var hasHealthLogs by mutableStateOf(false); private set

    /**
     * Show the tree saved from the last visit straight away (if there is one), then scan
     * and swap in the fresh tree. The view (open folders, filter, cursor) is only reset
     * when there was nothing cached to show.
     */
    private suspend fun showThenRefresh(key: String, make: (Node) -> Source, scan: suspend () -> Node) {
        val t0 = System.currentTimeMillis()
        // A rescan of the source already on screen keeps that tree up until the new one is ready.
        val cached = if (source?.cacheKey == key) null else withContext(Dispatchers.IO) { TreeCache.read(ctx, key) }
        val alreadyShown = source?.cacheKey == key
        if (cached != null) {
            present(make(cached).also { it.cacheKey = key }, resetView = true)
            showingCached = true
            scanMs = System.currentTimeMillis() - t0
        }
        val root = scan()
        present(make(root).also { it.cacheKey = key }, resetView = cached == null && !alreadyShown)
        showingCached = false
        scanMs = System.currentTimeMillis() - t0
        withContext(Dispatchers.IO) { TreeCache.write(ctx, key, root) }
    }

    private suspend fun present(s: Source, resetView: Boolean) {
        val (u, t, h) = withContext(Dispatchers.Default) {
            val files = s.find { !it.isDir }
            Triple(
                if (s.isRemote) null else SourceAccess.usage(ctx.contentResolver, s.treeUri),
                files.groupBy { it.kind }.mapValues { (_, v) -> TypeTotals(v.size, v.sumOf { it.size }) },
                s.find { it.isDir && it.name == "DeviceHealth" }.isNotEmpty(),
            )
        }
        source = s; usage = u; totals = t; hasHealthLogs = h
        if (resetView) { expanded = defaultExpanded(s); filter = ""; cursor = 0 }
        health = null
        if (screen == Screen.Sources) showExplorer()
    }

    private fun load(block: suspend () -> Unit) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning = true; scanCount = 0; error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = disconnectMessage(e)
            } finally {
                scanning = false; showingCached = false
                refreshRemembered()
            }
        }
    }

    fun rescan() { source?.let { if (it.isRemote) openWifi() else open(it.treeUri) } }

    fun disconnectMessage(e: Throwable): String = when (e) {
        is NoCameraException -> "No dashcam answered. Join the camera's Wi-Fi network (its name starts with QUBO_) in this device's Wi-Fi settings, choose to stay connected if it warns about no internet, then try again."
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.UnknownHostException, is java.net.SocketException ->
            "The dashcam stopped answering. Check this device is still on the camera's Wi-Fi."
        is SecurityException -> "Access to this folder was withdrawn. Choose it again."
        is java.io.FileNotFoundException, is IllegalArgumentException, is java.io.IOException ->
            "The files could not be read. Is the card still connected?"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- explorer ----
    var expanded by mutableStateOf(setOf<String>()); private set
    var filter by mutableStateOf("")
    var cursor by mutableIntStateOf(0)

    private fun defaultExpanded(s: Source): Set<String> {
        // first levels open, like the web version: root, its folders, and theirs
        val out = HashSet<String>()
        fun walk(n: Node, depth: Int) {
            if (!n.isDir || depth > 2) return
            out += n.path
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(s.root, 0)
        return out
    }

    fun toggle(n: Node) { expanded = if (n.path in expanded) expanded - n.path else expanded + n.path }
    fun setOpen(n: Node, open: Boolean) { expanded = if (open) expanded + n.path else expanded - n.path }
    fun collapseAll() { expanded = setOf("") }
    fun expandFolders() {
        val s = source ?: return
        val out = HashSet<String>()
        fun walk(n: Node, depth: Int) {
            if (!n.isDir || depth > 4) return
            out += n.path
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(s.root, 0)
        expanded = out
    }

    /** Rows on screen, top to bottom. Kept up to date off the main thread (see init). */
    var rows by mutableStateOf(listOf<TreeRow>()); private set

    init {
        viewModelScope.launch {
            var lastFilter = ""
            snapshotFlow { Triple(source, expanded, filter) }.collectLatest { (s, open, text) ->
                // Opening and closing folders is immediate; typing waits for a short pause,
                // because each new filter walks every file on the card.
                if (text != lastFilter && text.isNotBlank()) delay(150)
                lastFilter = text
                rows = withContext(Dispatchers.Default) { buildRows(s, open, text) }
            }
        }
    }

    /** With a filter: matching files and the folders leading to them, all open. */
    private fun buildRows(s: Source?, open: Set<String>, text: String): List<TreeRow> {
        s ?: return emptyList()
        val q = text.trim().lowercase()
        val out = ArrayList<TreeRow>(256)
        if (q.isEmpty()) {
            fun walk(n: Node, depth: Int) {
                val isOpen = n.isDir && n.path in open
                out += TreeRow(n, depth, isOpen)
                if (isOpen) for (c in n.children) walk(c, depth + 1)
            }
            walk(s.root, 0)
        } else {
            fun walk(n: Node, depth: Int): Boolean {
                val self = n.lowerName.contains(q)
                if (!n.isDir) {
                    if (self) out += TreeRow(n, depth, false)
                    return self
                }
                val at = out.size
                out += TreeRow(n, depth, true)
                var any = false
                for (c in n.children) any = walk(c, depth + 1) or any
                if (!any && !self) out.removeAt(at)
                else if (!any) { // folder name matches: show its contents
                    for (c in n.children) out += TreeRow(c, depth + 1, false)
                }
                return any || self
            }
            walk(s.root, 0)
        }
        return out
    }

    // ---- viewer preferences ----
    var autoNext by mutableStateOf(prefs.getBoolean("autoNext", true)); private set
    fun setAutoNextPref(on: Boolean) { autoNext = on; prefs.edit().putBoolean("autoNext", on).apply() }

    // ---- long jobs ----
    var job by mutableStateOf<JobState?>(null); private set
    private var running: Job? = null
    fun dismissJob() { running?.cancel(); running = null; job = null }

    private fun key(vararg parts: Any?): String =
        MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun exportCombined(pair: Source.Pair) {
        val s = source ?: return
        val front = pair.front ?: return
        val rear = pair.rear ?: return
        val stamp = clipTimeMs(front.name)?.let { SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(it)) }
            ?: front.name.substringBeforeLast('.')
        val name = "Dashcam_Front-Rear_$stamp.mp4"
        val out = File(Outputs.dir(ctx), "combo-${key(s.treeUri, front.path, front.modifiedMs, rear.modifiedMs)}.mp4")
        running?.cancel()
        job = JobState("Exporting Front + Rear", saveName = name)
        running = viewModelScope.launch {
            try {
                if (!out.exists()) {
                    if (ctx.cacheDir.usableSpace < 300L * 1024 * 1024) throw java.io.IOException("Not enough free space on this device (300 MB needed)")
                    val f = s.fetch(front)   // already on the device when it was just watched
                    val r = s.fetch(rear)
                    CombinedExporter.export(ctx, f, r, out) { p -> job = job?.copy(progress = p) }
                    Outputs.trim(ctx)
                }
                job = job?.copy(progress = 100, file = out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job = job?.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun saveJobToGallery() {
        val j = job ?: return
        val f = j.file ?: return
        viewModelScope.launch {
            job = try {
                Outputs.saveToGallery(ctx, f, j.saveName ?: f.name)
                j.copy(savedMessage = "Saved to Movies/DashcamExplorer")
            } catch (e: Exception) {
                j.copy(savedMessage = "Could not save: ${e.message}")
            }
        }
    }

    // ---- timelapse ----
    var timelapse by mutableStateOf<TimelapseInfo?>(null); private set

    fun buildTimelapse(path: String, fps: Int) {
        val s = source ?: return
        val tl = s.node(path) ?: return
        running?.cancel()
        timelapse = null
        job = JobState("Building the timelapse")
        running = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.Default) {
                    fun photos(side: String): Map<String, Node> {
                        val base = s.node("$path/$side") ?: return emptyMap()
                        val out = HashMap<String, Node>()
                        fun walk(n: Node) {
                            if (n.isDir) n.children.forEach(::walk)
                            else if (n.kind == FileKind.PHOTO) out[n.path.removePrefix(base.path + "/")] = n
                        }
                        walk(base)
                        return out
                    }
                    val front = photos("Front")
                    val rear = photos("Rear")
                    // keep only moments both cameras have, so the halves stay in step
                    val both = front.isNotEmpty() && rear.isNotEmpty()
                    val keys = (if (both) front.keys intersect rear.keys else front.keys + rear.keys)
                        .sortedWith(compareBy({ clipTimeMs(it.substringAfterLast('/')) ?: 0L }, { it }))
                    if (keys.size < 2) throw IllegalStateException("This day has no timelapse photos")
                    val cameras = when { both -> "Front + Rear"; front.isNotEmpty() -> "Front"; else -> "Rear" }
                    val picked = keys.map { k -> front[k] to rear[k] }
                    val day = tl.parentPath.substringAfterLast('/')
                    val times = keys.mapNotNull { clipTimeMs(it.substringAfterLast('/')) }
                    val out = File(Outputs.dir(ctx), "tl-${key("v4", s.treeUri, path, keys.size, keys.last(), cameras, fps)}.mp4")
                    Triple(picked, out, TimelapseInfo(day, keys.size, cameras, times.firstOrNull(), times.lastOrNull(), fps, out,
                        "Dashcam_Timelapse_${cameras.replace(" + ", "-")}_${day}_${fps}fps.mp4"))
                }
                val (picked, out, meta) = info
                job = job?.copy(total = picked.size)
                if (!out.exists()) {
                    // Over Wi-Fi the photos are fetched first (a few at a time), then stitched.
                    if (s.isRemote) job = job?.copy(title = "Downloading the day's photos")
                    val done = java.util.concurrent.atomic.AtomicInteger()
                    val gate = kotlinx.coroutines.sync.Semaphore(4)
                    val frames = kotlinx.coroutines.coroutineScope {
                        picked.map { (f, r) ->
                            async {
                                gate.acquire()
                                try { TimelapseBuilder.Frame(f?.let { s.fetch(it) }, r?.let { s.fetch(it) }) }
                                finally { gate.release(); if (s.isRemote) job = job?.copy(progress = done.incrementAndGet()) }
                            }
                        }.map { it.await() }
                    }
                    job = job?.copy(title = "Building the timelapse", progress = 0)
                    TimelapseBuilder.build(ctx, frames, fps, out) { n -> job = job?.copy(progress = n) }
                    Outputs.trim(ctx)
                }
                timelapse = meta
                job = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job = job?.copy(error = if (e is IllegalStateException) e.message else disconnectMessage(e))
            }
        }
    }

    // ---- health ----
    var health by mutableStateOf<List<HealthDay>?>(null); private set
    var healthError by mutableStateOf<String?>(null); private set


    fun loadHealth() {
        val s = source ?: return
        if (health != null) return
        viewModelScope.launch {
            healthError = null
            try {
                health = withContext(Dispatchers.IO) {
                    s.find { !it.isDir && it.name.endsWith(".log", true) && it.path.contains("DeviceHealth/") }
                        .sortedBy { it.path }
                        .map { n ->
                            val day = n.parentPath.substringAfterLast('/')
                            val text = s.readBytes(ctx, n).decodeToString()
                            HealthLogParser.parse(day, text)
                        }
                        .sortedBy { runCatching { SimpleDateFormat("dd-MMM-yyyy", Locale.US).parse(it.day)?.time }.getOrNull() ?: 0L }
                }
            } catch (e: Exception) {
                healthError = disconnectMessage(e)
            }
        }
    }
}
