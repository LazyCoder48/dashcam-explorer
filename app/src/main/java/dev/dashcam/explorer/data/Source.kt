package dev.dashcam.explorer.data

import android.content.ContentResolver
import androidx.compose.runtime.Stable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.dashcam.explorer.domain.FileKind
import dev.dashcam.explorer.domain.Match
import dev.dashcam.explorer.domain.NaturalOrder
import dev.dashcam.explorer.domain.Pairing
import dev.dashcam.explorer.domain.Camera
import dev.dashcam.explorer.domain.kindOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** One file or folder in the chosen source. [path] is "/"-separated from the source root ("" for the root). */
@Stable
class Node(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val docId: String,
    val modifiedMs: Long?,
    size: Long,
) {
    var size: Long = size
        internal set
    var files: Int = 0
        internal set
    var dirs: Int = 0
        internal set
    var children: List<Node> = emptyList()
        internal set
    /** Lower-cased once; the filter box compares against it on every keystroke. */
    val lowerName: String by lazy(LazyThreadSafetyMode.NONE) { name.lowercase() }
    val kind: FileKind = if (isDir) FileKind.OTHER else kindOf(name)
    val parentPath: String get() = path.substringBeforeLast('/', "")

    /** Fill in this folder's totals from its (already totalled) children. */
    internal fun sumUp() {
        var s = 0L; var f = 0; var d = 0
        for (c in children) { s += c.size; if (c.isDir) { f += c.files; d += 1 + c.dirs } else f++ }
        size = s; files = f; dirs = d
    }
}

/** A folder tree the user granted access to: a card in a USB reader, an SD card, or any copied folder. */
class Source(val treeUri: Uri, val label: String, val where: String, val root: Node, val camera: WifiCamera? = null) {
    /** True when files come from the dashcam over Wi-Fi and must be downloaded before use. */
    val isRemote: Boolean get() = camera != null
    /** Identifies this source in the on-disk tree cache. */
    var cacheKey: String = ""
    private val byPath = HashMap<String, Node>()
    private val namesByDir = HashMap<String, Set<String>>()

    init {
        fun index(n: Node) {
            byPath[n.path] = n
            if (n.isDir) {
                namesByDir[n.path] = n.children.filter { !it.isDir }.mapTo(HashSet()) { it.name }
                n.children.forEach(::index)
            }
        }
        index(root)
    }

    fun node(path: String): Node? = byPath[path.trim('/')]
    /** Where to read a file right now. For a Wi-Fi source this is only valid once [fetch] has run. */
    fun uri(n: Node): Uri =
        if (camera == null) DocumentsContract.buildDocumentUriUsingTree(treeUri, n.docId)
        else camera.cached(n)?.let(Uri::fromFile) ?: Uri.EMPTY

    /** What the video player should open: the local copy, or the camera download as it arrives. */
    fun playUri(n: Node): Uri =
        if (camera == null) uri(n)
        else camera.cached(n)?.let(Uri::fromFile) ?: Uri.Builder().scheme(WifiCamera.SCHEME).path(n.path).build()

    /** A readable local URI: immediate for a folder source, after a download for the camera. */
    suspend fun fetch(n: Node, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Uri =
        if (camera == null) uri(n) else Uri.fromFile(camera.fetch(n, onProgress))

    suspend fun readBytes(context: Context, n: Node): ByteArray = withContext(Dispatchers.IO) {
        val u = fetch(n)
        context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw java.io.IOException("Could not read ${n.name}")
    }

    /** The two cameras' files for one moment, plus neighbours for previous/next. */
    fun pair(path: String): Pair? {
        val n = node(path) ?: return null
        if (n.isDir) return null
        val p = Pairing.counterpart(n.path) { dir -> namesByDir[dir] }
        val other = p.otherPath?.let(::node)
        val siblings = node(n.parentPath)?.children.orEmpty().filter {
            !it.isDir && it.name.substringAfterLast('.').equals(n.name.substringAfterLast('.'), true)
        }
        val i = siblings.indexOfFirst { it.path == n.path }
        return Pair(
            kind = n.kind,
            opened = n,
            side = p.side,
            front = when (p.side) { Camera.FRONT -> n; Camera.REAR -> other; null -> null },
            rear = when (p.side) { Camera.REAR -> n; Camera.FRONT -> other; null -> null },
            match = p.match,
            index = i + 1,
            count = siblings.size,
            prev = siblings.getOrNull(i - 1),
            next = siblings.getOrNull(i + 1),
        )
    }

    data class Pair(
        val kind: FileKind, val opened: Node, val side: Camera?, val front: Node?, val rear: Node?,
        val match: Match, val index: Int, val count: Int, val prev: Node?, val next: Node?,
    ) {
        /** With no Front/Rear folder in the path the file is shown alone. */
        val single: Node? get() = if (side == null) opened else null
    }

    /** Day folders (or their Timelapse folder) that have timelapse photos. Returns the Timelapse node. */
    fun timelapseOf(n: Node): Node? {
        if (!n.isDir) return null
        val tl = if (n.name == "Timelapse") n else n.children.firstOrNull { it.isDir && it.name == "Timelapse" }
        return tl?.takeIf { it.files > 1 }
    }

    fun find(predicate: (Node) -> Boolean): List<Node> = byPath.values.filter(predicate)
}

object SourceAccess {
    /** Removable volumes that are mounted; ones labelled like a Qubo card come first. */
    fun removableVolumes(context: Context): List<StorageVolume> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .sortedByDescending { looksQubo(it.getDescription(context)) }
    }

    fun looksQubo(label: String?) = label?.contains("qubo", ignoreCase = true) == true

    /** Folder picker already pointed at this volume (the user still confirms). */
    fun pickerIntentFor(volume: StorageVolume): Intent = volume.createOpenDocumentTreeIntent()

    fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Folders granted earlier that are still readable, newest first (at most five are kept). */
    fun remembered(context: Context): List<Uri> {
        val perms = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .sortedByDescending { it.persistedTime }
        perms.drop(5).forEach {
            runCatching { context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        return perms.take(5).map { it.uri }
    }

    fun forget(context: Context, uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Human description of a tree URI without touching the provider: "QUBO2K" / "primary:Download/Sample". */
    fun describe(uri: Uri): kotlin.Pair<String, String> {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: uri.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val label = path.trimEnd('/').substringAfterLast('/').ifEmpty { if (volume == "primary") "Internal storage" else volume }
        val where = if (volume == "primary") "Internal storage" + (if (path.isEmpty()) "" else " / $path") else "Removable storage ($volume)" + (if (path.isEmpty()) "" else " / $path")
        return label to where
    }

    /** True when the folder still exists and can be listed (false once a card is unplugged). */
    fun reachable(context: Context, uri: Uri): Boolean = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private val COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    /**
     * Read the whole tree. One provider query per folder (DocumentFile would need one
     * per file property, far too slow for a 13,000-file card). Sibling folders are read
     * a few at a time: each query is a round trip to the storage provider's process.
     */
    suspend fun scan(context: Context, treeUri: Uri, onProgress: (files: Int) -> Unit): Node = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val seen = AtomicInteger()
        val gate = Semaphore(4)

        fun query(dirId: String, dirPath: String): ArrayList<Node> {
            val out = ArrayList<Node>()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            resolver.query(uri, COLS, null, null, null)?.use { c ->
                out.ensureCapacity(c.count)
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                    out += Node(
                        name = name, path = if (dirPath.isEmpty()) name else "$dirPath/$name", isDir = isDir, docId = c.getString(0),
                        modifiedMs = if (c.isNull(4)) null else c.getLong(4),
                        size = if (isDir || c.isNull(3)) 0 else c.getLong(3),
                    )
                }
            } ?: throw java.io.IOException("The folder could not be read. Is the card still connected?")
            return out
        }

        suspend fun read(dirId: String, dirPath: String): List<Node> = coroutineScope {
            coroutineContext.ensureActive()
            val out = gate.withPermit { query(dirId, dirPath) }
            onProgress(seen.addAndGet(out.count { !it.isDir }))
            out.filter { it.isDir }.map { n -> async { n.children = read(n.docId, n.path) } }.awaitAll()
            for (n in out) if (n.isDir) n.sumUp()
            // folders first, then files, ascending by name
            out.sortWith(TREE_ORDER)
            out
        }

        val root = Node(describe(treeUri).first, "", true, rootId, null, 0)
        root.children = read(rootId, "")
        root.sumUp()
        root
    }

    val TREE_ORDER: Comparator<Node> = Comparator { a, b ->
        if (a.isDir != b.isDir) (if (a.isDir) -1 else 1) else NaturalOrder.compare(a.name, b.name)
    }

    /** Total and free bytes of the volume holding the tree, when the provider reports them. */
    fun usage(resolver: ContentResolver, treeUri: Uri): kotlin.Pair<Long, Long>? = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.openFileDescriptor(docUri, "r")?.use { pfd ->
            val st = android.system.Os.fstatvfs(pfd.fileDescriptor)
            (st.f_blocks * st.f_frsize) to (st.f_bavail * st.f_frsize)
        }
    }.getOrNull()
}
