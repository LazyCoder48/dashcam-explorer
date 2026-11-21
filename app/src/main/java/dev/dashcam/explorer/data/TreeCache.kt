package dev.dashcam.explorer.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Keeps the last scanned tree of each source on disk, so reopening a source shows it
 * at once while a fresh scan runs in the background. A 13,000-file card is a few
 * hundred kilobytes compressed and loads in well under a second.
 */
object TreeCache {
    private const val VERSION = 2

    private fun file(context: Context, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(File(context.filesDir, "trees").apply { mkdirs() }, "$h.bin")
    }

    fun write(context: Context, key: String, root: Node) {
        val f = file(context, key)
        val tmp = File(f.path + ".part")
        runCatching {
            DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered(), 1 shl 16)).use { o ->
                o.writeInt(VERSION)
                fun put(n: Node) {
                    o.writeUTF(n.name); o.writeBoolean(n.isDir); o.writeUTF(n.docId)
                    o.writeLong(n.modifiedMs ?: Long.MIN_VALUE); o.writeLong(n.size)
                    if (n.isDir) { o.writeInt(n.children.size); n.children.forEach(::put) }
                }
                put(root)
            }
            tmp.renameTo(f)
        }.onFailure { tmp.delete() }
    }

    /** The cached tree, or null when there is none or it cannot be read. */
    fun read(context: Context, key: String): Node? {
        val f = file(context, key)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(GZIPInputStream(f.inputStream().buffered(), 1 shl 16)).use { i ->
                if (i.readInt() != VERSION) return null
                fun get(parent: String?): Node {
                    val name = i.readUTF(); val isDir = i.readBoolean(); val docId = i.readUTF()
                    val m = i.readLong(); val size = i.readLong()
                    val path = when { parent == null -> ""; parent.isEmpty() -> name; else -> "$parent/$name" }
                    val n = Node(name, path, isDir, docId, if (m == Long.MIN_VALUE) null else m, size)
                    if (isDir) {
                        val count = i.readInt()
                        val kids = ArrayList<Node>(count)
                        repeat(count) { kids += get(path) }
                        n.children = kids   // already sorted when written
                        n.sumUp()
                    }
                    return n
                }
                get(null)
            }
        }.getOrElse { f.delete(); null }
    }
}
