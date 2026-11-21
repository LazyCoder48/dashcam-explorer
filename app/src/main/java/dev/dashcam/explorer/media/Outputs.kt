package dev.dashcam.explorer.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Where finished videos go: the app's cache first, then the gallery on request. */
object Outputs {
    fun dir(context: Context): File = File(context.cacheDir, "out").apply { mkdirs() }

    /** Copy a finished file into Movies/DashcamExplorer so it shows up in the gallery. */
    suspend fun saveToGallery(context: Context, file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DashcamExplorer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw java.io.IOException("The gallery refused the new video")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // never leave a broken file in the gallery
            throw e
        }
        uri
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share video",
        )
    }

    /** Keep the cache folder under [maxBytes], dropping the oldest files first. */
    fun trim(context: Context, maxBytes: Long = 1L shl 30) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length(); f.delete()
        }
    }
}

/**
 * One MP4 with Front on top and Rear below, sound from the front camera
 * (DESIGN.md section 8). Uses Media3 Transformer's multi-sequence compositing.
 */
@OptIn(UnstableApi::class)
object CombinedExporter {
    /**
     * Front in the top half, rear scaled to the same width in the bottom half.
     *
     * The compositor draws each input at its own pixel size, centred, unless told
     * otherwise. So: scale each input to the output width, then move its centre to
     * the middle of its own band. Anchors are in -1..1 coordinates (y up).
     */
    private class Stacked : VideoCompositorSettings {
        private var sizes: List<Size> = emptyList()
        private var out = Size(2, 2)
        private var frontH = 0

        override fun getOutputSize(inputSizes: List<Size>): Size {
            sizes = inputSizes
            val w = inputSizes[0].width / 2 * 2
            frontH = inputSizes[0].height / 2 * 2
            val rearH = if (inputSizes.size > 1) (inputSizes[1].height.toLong() * w / inputSizes[1].width / 2 * 2).toInt() else 0
            out = Size(w, frontH + rearH)
            return out
        }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val input = sizes.getOrNull(inputId) ?: return StaticOverlaySettings.Builder().build()
            val scale = out.width.toFloat() / input.width
            val bandH = if (inputId == 0) frontH else out.height - frontH
            // centre of this camera's band, measured from the top of the output, as -1..1 with y up
            val centreFromTop = (if (inputId == 0) 0 else frontH) + bandH / 2f
            val y = 1f - 2f * centreFromTop / out.height
            return StaticOverlaySettings.Builder()
                .setScale(scale, scale)
                .setBackgroundFrameAnchor(0f, y)
                .build()
        }
    }

    suspend fun export(context: Context, front: Uri, rear: Uri, out: File, onProgress: (Int) -> Unit) {
        val tmp = File(out.path + ".part")
        tmp.delete()
        val frontItem = EditedMediaItem.Builder(MediaItem.fromUri(front)).build()
        val rearItem = EditedMediaItem.Builder(MediaItem.fromUri(rear)).setRemoveAudio(true).build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(frontItem)),
            EditedMediaItemSequence.withVideoFrom(listOf(rearItem)),
        ).setVideoCompositorSettings(Stacked()).build()

        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        done.complete(Unit)
                    }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                        done.completeExceptionally(e)
                    }
                })
                .build()
            transformer.start(composition, tmp.path)
            val poll = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    transformer.getProgress(holder)
                    onProgress(holder.progress)
                    delay(400)
                }
            }
            try {
                done.await()
            } catch (e: Throwable) {
                runCatching { transformer.cancel() }
                tmp.delete()
                throw e
            } finally {
                poll.cancel()
            }
        }
        if (!tmp.renameTo(out)) throw java.io.IOException("Could not finish the export file")
    }
}

/**
 * Day timelapse (DESIGN.md section 9): the day's photos, Front over Rear, as one H.264 video.
 */
object TimelapseBuilder {
    data class Frame(val front: Uri?, val rear: Uri?)

    /** One worker's scratch space: decoded photos, the stacked picture and its YUV planes. */
    private class Slot(val w: Int, val h: Int, val rows: Int) {
        val height = h * rows
        val bmp: Bitmap = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val px = IntArray(w * height)
        val y = ByteArray(w * height)
        val u = ByteArray(w * height / 4)
        val v = ByteArray(w * height / 4)
        var a: Bitmap? = null
        var b: Bitmap? = null
        private val dst = Rect()

        fun render(f: Frame, decode: (Uri?, Bitmap?) -> Bitmap?) {
            a = decode(f.front, a) ?: a
            b = decode(f.rear, b) ?: b
            canvas.drawColor(Color.BLACK)
            var row = 0
            for (bitmap in listOf(if (f.front != null || rows == 2) a else null, if (f.rear != null || rows == 2) b else null)) {
                if (rows == 1 && bitmap == null) continue
                if (bitmap != null) { dst.set(0, row * h, w, row * h + h); canvas.drawBitmap(bitmap, null, dst, null) }
                row++
            }
            bmp.getPixels(px, 0, w, 0, 0, w, height)
            // RGB -> YUV 4:2:0 (BT.601), one pass over the picture
            val cw = w / 2
            for (yy in 0 until height) {
                val o = yy * w
                val chroma = yy and 1 == 0
                val co = (yy / 2) * cw
                for (x in 0 until w) {
                    val c = px[o + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val bl = c and 0xFF
                    y[o + x] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
                    if (chroma && x and 1 == 0) {
                        u[co + x / 2] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).toByte()
                        v[co + x / 2] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).toByte()
                    }
                }
            }
        }
    }

    /**
     * Each photo pair is drawn into one picture, converted to YUV and handed to the
     * encoder with its true timestamp (frame n at n/fps seconds). The timestamp matters:
     * the encoder spends its bit budget per second of video, so frames stamped with the
     * (much faster) build clock would come out starved and blocky.
     *
     * Reading, decoding and converting the photos is the slow part, so it runs on several
     * cores at once, a batch of frames at a time; the encoder is then fed in order.
     */
    suspend fun build(context: Context, frames: List<Frame>, fps: Int, out: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.Default) {
        require(frames.size >= 2) { "This day has no timelapse photos" }
        val resolver = context.contentResolver
        val decode: (Uri?, Bitmap?) -> Bitmap? = { uri, reuse ->
            if (uri == null) null else {
                val opts = BitmapFactory.Options().apply { inMutable = true; inBitmap = reuse }
                runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it.buffered(), null, opts) } }
                    .recoverCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it.buffered()) } }
                    .getOrNull()
            }
        }

        val first = decode(frames[0].front ?: frames[0].rear, null) ?: throw java.io.IOException("Could not read the first photo")
        val w = first.width / 2 * 2
        val h = first.height / 2 * 2
        first.recycle()
        val rows = if (frames.any { it.front != null } && frames.any { it.rear != null }) 2 else 1
        val H = h * rows
        val tmp = File(out.path + ".part").also { it.delete() }
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val slots = List(workers) { Slot(w, h, rows) }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000 * rows / 2 + 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // This is an offline job, not a live camera: ask the hardware encoder to work as
            // fast as it can instead of tuning itself for the (low) frame rate above.
            setInteger(MediaFormat.KEY_PRIORITY, 1)            // 1 = best effort, not real time
            setInteger(MediaFormat.KEY_OPERATING_RATE, 240)    // work as fast as the chip allows
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mux = muxer
            var track = -1
            var written = 0
            var ended = false
            val info = MediaCodec.BufferInfo()

            fun drain(wait: Boolean) {
                var idle = 0
                while (!ended) {
                    val i = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
                    when {
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(codec.outputFormat); mux.start() }
                        // at the end keep waiting for the last frames, but never for more than ~5 s
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!wait || ++idle > 500) return
                        i >= 0 -> {
                            val buf = codec.getOutputBuffer(i)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                                mux.writeSampleData(track, buf, info)
                                written++
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                            codec.releaseOutputBuffer(i, false)
                        }
                    }
                }
            }

            /** Wait for a free input buffer, emptying the output side meanwhile. */
            fun input(): Int {
                while (true) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) return i
                    drain(false)
                }
            }

            val cw = w / 2
            fun feed(slot: Slot, n: Int) {
                val idx = input()
                val image = codec.getInputImage(idx) ?: throw java.io.IOException("This device's video encoder cannot take frames this way")
                val (yP, uP, vP) = image.planes
                val yBuf = yP.buffer
                if (yP.rowStride == w) { yBuf.position(0); yBuf.put(slot.y, 0, w * H) }
                else for (r in 0 until H) { yBuf.position(r * yP.rowStride); yBuf.put(slot.y, r * w, w) }
                val uBuf = uP.buffer
                val vBuf = vP.buffer
                if (uP.pixelStride == 1 && vP.pixelStride == 1) {
                    // planar layout: whole rows at a time
                    for (r in 0 until H / 2) {
                        uBuf.position(r * uP.rowStride); uBuf.put(slot.u, r * cw, cw)
                        vBuf.position(r * vP.rowStride); vBuf.put(slot.v, r * cw, cw)
                    }
                } else {
                    // interleaved layout: the two planes share memory, so place each byte
                    for (r in 0 until H / 2) {
                        val uo = r * uP.rowStride
                        val vo = r * vP.rowStride
                        val so = r * cw
                        for (x in 0 until cw) {
                            uBuf.put(uo + x * uP.pixelStride, slot.u[so + x])
                            vBuf.put(vo + x * vP.pixelStride, slot.v[so + x])
                        }
                    }
                }
                codec.queueInputBuffer(idx, 0, w * H * 3 / 2, n * 1_000_000L / fps, 0)
                drain(false)
            }

            var start = 0
            var renderNs = 0L
            var feedNs = 0L
            val t0 = System.nanoTime()
            while (start < frames.size) {
                coroutineContext.ensureActive()
                val count = minOf(workers, frames.size - start)
                val base = start
                val r0 = System.nanoTime()
                coroutineScope {
                    (0 until count).map { k -> async { slots[k].render(frames[base + k], decode) } }.awaitAll()
                }
                val r1 = System.nanoTime()
                for (k in 0 until count) feed(slots[k], base + k)
                renderNs += r1 - r0
                feedNs += System.nanoTime() - r1
                start += count
                onProgress(start)
            }
            codec.queueInputBuffer(input(), 0, 0, frames.size * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val d0 = System.nanoTime()
            drain(true)
            android.util.Log.i("Timelapse", "frames=${frames.size} fps=$fps workers=$workers prepare=${renderNs / 1_000_000}ms " +
                "encode=${feedNs / 1_000_000}ms finish=${(System.nanoTime() - d0) / 1_000_000}ms total=${(System.nanoTime() - t0) / 1_000_000}ms")
            onProgress(frames.size)
            if (written == 0) throw java.io.IOException("The encoder produced no video")
            mux.stop()
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
            slots.forEach { it.bmp.recycle(); it.a?.recycle(); it.b?.recycle() }
        }
        if (!tmp.renameTo(out)) throw java.io.IOException("Could not finish the timelapse file")
    }
}
