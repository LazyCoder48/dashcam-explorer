package dev.dashcam.explorer.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import dev.dashcam.explorer.data.Node
import dev.dashcam.explorer.data.WifiCamera
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lets the player read a camera clip while it is still downloading.
 *
 * The camera's link is only a little faster than the clips' own bitrate, so waiting for
 * the whole pair costs close to a minute. The clips keep their index at the front of the
 * file, so playback can begin as soon as the first bytes are here. This source reads the
 * growing ".part" file and simply waits when the player asks for bytes that have not
 * arrived yet (the player shows that as buffering). Any other URI goes to the normal source.
 */
@OptIn(UnstableApi::class)
class CameraDataSource(
    private val camera: WifiCamera,
    private val lookup: (String) -> Node?,
    private val fallback: DataSource,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val context: Context, private val camera: WifiCamera, private val lookup: (String) -> Node?) : DataSource.Factory {
        private val fallback = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = CameraDataSource(camera, lookup, fallback.createDataSource())
    }

    private var uri: Uri? = null
    private var delegated = false
    private var opened = false
    private var node: Node? = null
    private var download: WifiCamera.Download? = null
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        if (dataSpec.uri.scheme != WifiCamera.SCHEME) {
            delegated = true
            return fallback.open(dataSpec)
        }
        val n = lookup(dataSpec.uri.path.orEmpty().trimStart('/')) ?: throw IOException("This file is no longer on the camera")
        node = n
        transferInitializing(dataSpec)

        val dl = camera.start(n)
        val length: Long
        if (dl == null) {
            length = openWhole(n)
        } else {
            // the size arrives with the camera's first answer
            while (dl.total <= 0 && !dl.job.isCompleted) pause()
            if (dl.total > 0) { download = dl; length = dl.total } else length = openWhole(n)
        }
        position = dataSpec.position
        if (position > length) throw IOException("Read past the end of ${n.name}")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    /** The finished copy, for a clip that is already here (or whose download just ended). */
    private fun openWhole(n: Node): Long {
        val f: File = camera.cached(n) ?: throw IOException("The download of ${n.name} stopped")
        file = RandomAccessFile(f, "r")
        return f.length()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (delegated) return fallback.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        var available = remaining
        val dl = download
        if (dl != null) {
            // wait here until the download has passed the point the player wants
            while (dl.done <= position) {
                if (dl.job.isCompleted) throw IOException("The download of ${node?.name} stopped")
                pause()
            }
            available = minOf(available, dl.done - position)
        }
        val f = file ?: run {
            // the ".part" file is renamed when the download ends, so fall back to the finished copy
            val opened = runCatching { RandomAccessFile(dl!!.part, "r") }.getOrNull()
                ?: RandomAccessFile(camera.cached(node!!) ?: throw IOException("The download of ${node?.name} stopped"), "r")
            file = opened
            opened
        }
        f.seek(position)
        val read = f.read(buffer, offset, minOf(length.toLong(), available).toInt())
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    private fun pause() {
        try { Thread.sleep(25) } catch (e: InterruptedException) { throw InterruptedIOException() }
    }

    override fun getUri(): Uri? = if (delegated) fallback.uri else uri

    override fun close() {
        try {
            if (delegated) fallback.close() else file?.close()
        } finally {
            file = null; download = null; node = null; uri = null
            val wasOpened = opened
            opened = false; delegated = false
            if (wasOpened) transferEnded()
        }
    }
}
