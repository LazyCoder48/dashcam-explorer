package dev.dashcam.explorer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** One row of the camera's folder index. Pure data so the parser can be unit-tested. */
data class IndexEntry(val name: String, val isDir: Boolean, val approxSize: Long, val modifiedMs: Long?)

/**
 * The camera's own web server (DESIGN.md 11.2a): while a phone is joined to the camera's
 * Wi-Fi, port 8080 serves the whole memory card as plain HTML folder listings, with
 * Range support and no login. Sizes in a listing are rounded ("88.4M").
 */
object CameraIndex {
    private val ROW = Regex("""<tr><td><a href="([^"]*)">([^<]*)</a></td><td>(?:&nbsp;)*([^<]*)</td><td>(?:&nbsp;)*([^<]*)</td>""")
    private val SIZE = Regex("""^([\d.]+)\s*([kKmMgG]?)$""")

    fun parse(html: String): List<IndexEntry> {
        val date = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.US)
        return ROW.findAll(html).mapNotNull { m ->
            val (href, text, modified, size) = m.destructured
            if (href.endsWith("/..") || text.trim() == "Parent directory") return@mapNotNull null
            val isDir = size.contains("DIRECTORY") || href.endsWith("/")
            val name = unescape(text).trimEnd('/')
            if (name.isEmpty()) return@mapNotNull null
            IndexEntry(name, isDir, if (isDir) 0 else parseSize(size.trim()), runCatching { date.parse(modified.trim())?.time }.getOrNull())
        }.toList()
    }

    fun parseSize(s: String): Long {
        val m = SIZE.matchEntire(s) ?: return 0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0
        val mult = when (m.groupValues[2].lowercase()) { "k" -> 1024.0; "m" -> 1048576.0; "g" -> 1073741824.0; else -> 1.0 }
        return (n * mult).toLong()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    fun encodePath(path: String): String =
        path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

class WifiCamera(private val network: Network?, val base: String, private val cacheDir: File) {

    private fun connect(path: String, dir: Boolean): HttpURLConnection {
        val url = URL(base + "/" + CameraIndex.encodePath(path) + if (dir && path.isNotEmpty()) "/" else "")
        // Bind to the camera's Wi-Fi so mobile data or another network is never used.
        val c = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 15000
        c.setRequestProperty("Connection", "close")
        return c
    }

    private fun list(path: String): List<IndexEntry> {
        val c = connect(path, dir = true)
        try {
            if (c.responseCode != 200) throw IOException("The camera answered ${c.responseCode} for /$path")
            return CameraIndex.parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Read the whole card's tree: one small request per folder, a few at a time. */
    suspend fun scan(onProgress: (files: Int) -> Unit): Node = withContext(Dispatchers.IO) {
        val gate = Semaphore(3) // the camera is a small device; do not flood it
        val seen = AtomicInteger()

        suspend fun read(dirPath: String): List<Node> = coroutineScope {
            coroutineContext.ensureActive()
            val entries = gate.withPermit { list(dirPath) }.filterNot { it.name.startsWith(".") }
            val nodes = entries.map { e ->
                Node(e.name, if (dirPath.isEmpty()) e.name else "$dirPath/${e.name}", e.isDir, "", e.modifiedMs, e.approxSize)
            }
            onProgress(seen.addAndGet(nodes.count { !it.isDir }))
            nodes.filter { it.isDir }.map { n -> async { n.children = read(n.path) } }.awaitAll()
            for (n in nodes) if (n.isDir) n.sumUp()
            nodes.sortedWith(SourceAccess.TREE_ORDER)
        }

        val root = Node("Dashcam (Wi-Fi)", "", true, "", null, 0)
        root.children = read("")
        root.sumUp()
        root
    }

    private fun cacheFile(n: Node): File {
        val key = MessageDigest.getInstance("SHA-1").digest("${n.path}|${n.modifiedMs}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(cacheDir, key + "." + n.name.substringAfterLast('.', "bin"))
    }

    fun cached(n: Node): File? = cacheFile(n).takeIf { it.exists() }

    // Downloads run in the camera's own scope, not the caller's. A screen that stops
    // waiting (for example at an auto-play hand-over) does not kill the download; the
    // next screen that asks for the same file simply joins it where it is.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One file on its way down. [part] grows as bytes arrive; the player reads it while it does. */
    class Download internal constructor(val part: File) {
        @Volatile var done = 0L
        @Volatile var total = 0L
        lateinit var job: Deferred<File>
    }
    private val active = HashMap<String, Download>()

    /** Begin (or join) the download of a file. Null means it is already on this device. */
    fun start(n: Node): Download? = synchronized(active) {
        cached(n)?.let { it.setLastModified(System.currentTimeMillis()); return null }
        active[n.path] ?: Download(File(cacheFile(n).path + ".part")).also { d ->
            active[n.path] = d
            d.job = scope.async {
                try { download(n, d) } finally { synchronized(active) { active.remove(n.path) } }
            }
        }
    }

    /** Download a file to the cache (if it is not there yet) and return it. */
    suspend fun fetch(n: Node, onProgress: (done: Long, total: Long) -> Unit): File {
        val dl = start(n) ?: return cacheFile(n)
        while (true) {
            val file = withTimeoutOrNull(300) { dl.job.await() }
            if (file != null) return file
            if (dl.total > 0) onProgress(dl.done, dl.total)
        }
    }

    /** Stop downloads nobody needs any more (everything except [keepPaths]). */
    fun cancelExcept(keepPaths: Set<String>) {
        val stale = synchronized(active) { active.filterKeys { it !in keepPaths }.values.toList() }
        stale.forEach { it.job.cancel() }
    }

    private suspend fun download(n: Node, dl: Download): File {
        val out = cacheFile(n)
        cacheDir.mkdirs()
        val tmp = dl.part
        val started = System.nanoTime()
        val startedWall = System.currentTimeMillis()
        val c = connect(n.path, dir = false)
        try {
            if (c.responseCode != 200) throw IOException("The camera answered ${c.responseCode} for ${n.name}")
            val total = c.contentLengthLong
            dl.total = total
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r)
                        done += r
                        dl.done = done
                    }
                    if (total > 0 && done != total) throw IOException("The download of ${n.name} stopped early")
                }
            }
            if (!tmp.renameTo(out)) throw IOException("Could not store ${n.name}")
            n.size = out.length() // the listing's size was rounded; this one is exact
            val ms = (System.nanoTime() - started) / 1_000_000
            android.util.Log.i("WifiCamera", "downloaded ${n.name}: ${out.length()} bytes in $ms ms, started at $startedWall")
            return out
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Keep downloaded copies under [maxBytes], dropping the least recently used first. */
    fun trim(maxBytes: Long = 3L shl 30) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length(); f.delete()
        }
    }

    companion object {
        const val PORT = 8080
        /** URIs of this scheme are read by the player straight from a download in progress. */
        const val SCHEME = "dashcam"

        /**
         * Look for the camera on the Wi-Fi network this device is joined to. The camera is
         * that network's gateway; 192.168.1.1 is tried as well. Returns null when no
         * camera-like folder index answers.
         */
        suspend fun find(context: Context): WifiCamera? = withContext(Dispatchers.IO) {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            val candidates = LinkedHashSet<Pair<Network?, String>>()
            for (n in wifi) {
                val gw = cm.getLinkProperties(n)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                if (gw != null) candidates += n to gw
                candidates += n to "192.168.1.1"
            }
            if (wifi.isEmpty()) candidates += null to "192.168.1.1"
            val cache = File(context.cacheDir, "wifi")
            for ((net, host) in candidates) {
                val cam = WifiCamera(net, "http://$host:$PORT", cache)
                val ok = runCatching {
                    val c = cam.connect("", dir = true)
                    try {
                        c.connectTimeout = 2500
                        c.responseCode == 200 && c.inputStream.bufferedReader().readText().let { it.contains("Index of") && it.contains("QUBO", true) }
                    } finally { c.disconnect() }
                }.getOrDefault(false)
                if (ok) return@withContext cam
            }
            null
        }
    }
}
