package com.claudeshqip.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * E njëjta logjikë si app.py, por yt-dlp, Python dhe ffmpeg ekzekutohen në telefon.
 */
object Downloader {
    private const val TAG = "Downloader"
    private const val FOLDER = "YouTube Downloader"

    private val VIDEO_CONTAINERS = setOf("mp4", "mkv", "webm")
    private val AUDIO_FORMATS = setOf("mp3", "m4a", "opus", "wav", "flac")
    private val AUDIO_BITRATES = setOf("128", "192", "256", "320")
    private val YOUTUBE_RE = Regex("^https?://([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE)
    private val RESOLUTION_NAMES = mapOf(4320 to "8K", 2160 to "4K", 1440 to "2K", 1080 to "Full HD", 720 to "HD")

    private val pool = Executors.newCachedThreadPool()
    private val ready = CountDownLatch(1)
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile private var initError: Throwable? = null
    private var appContext: Context? = null

    @Synchronized
    fun prepare(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        pool.execute {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
                initError = e
                ready.countDown()
                return@execute
            }
            // YouTube ndryshon shpesh, prandaj yt-dlp përditësohet në çdo nisje.
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed", e)
            }
            ready.countDown()
        }
    }

    private fun awaitReady() {
        if (!ready.await(3, TimeUnit.MINUTES)) throw IOException("App-i po përgatitet ende, provoni sërish.")
        initError?.let { throw IOException("App-i nuk u nis dot: ${it.message}") }
    }

    private fun validateUrl(url: String) {
        if (!YOUTUBE_RE.containsMatchIn(url)) throw IllegalArgumentException("Ju lutem shkruani një link valid të YouTube.")
    }

    fun friendlyError(e: Throwable): String {
        val message = e.message ?: e.toString()
        return message.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: message
    }

    fun info(url: String): JSONObject {
        validateUrl(url)
        awaitReady()
        val request = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--no-playlist")
        val data = JSONObject(YoutubeDL.getInstance().execute(request).out)

        val formats = data.optJSONArray("formats") ?: JSONArray()
        var bestAudioSize = 0L
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (f.optString("vcodec") == "none" && f.optString("acodec", "none") != "none") {
                bestAudioSize = maxOf(bestAudioSize, sizeOf(f))
            }
        }

        val byHeight = sortedMapOf<Int, JSONObject>(compareByDescending { it })
        val codecs = mutableMapOf<Int, MutableSet<String>>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val height = f.optInt("height", 0)
            val vcodec = f.optString("vcodec", "none")
            if (height <= 0 || vcodec == "none" || vcodec == "null") continue
            val entry = byHeight.getOrPut(height) {
                JSONObject().put("height", height).put("fps", 0).put("size", 0L)
                    .put("label", RESOLUTION_NAMES[height] ?: "")
            }
            entry.put("fps", maxOf(entry.getInt("fps"), f.optDouble("fps", 0.0).toInt()))
            var size = sizeOf(f)
            if (f.optString("acodec", "none") in setOf("none", "null")) size += bestAudioSize
            entry.put("size", maxOf(entry.getLong("size"), size))
            codecs.getOrPut(height) { sortedSetOf() }.add(vcodec.substringBefore("."))
        }
        byHeight.forEach { (height, entry) -> entry.put("codecs", JSONArray(codecs[height].orEmpty().toList())) }

        return JSONObject()
            .put("id", data.opt("id"))
            .put("title", data.optString("title"))
            .put("uploader", data.optString("uploader"))
            .put("duration", data.optDouble("duration", 0.0))
            .put("thumbnail", data.optString("thumbnail"))
            .put("resolutions", JSONArray(byHeight.values.toList()))
    }

    private fun sizeOf(f: JSONObject): Long {
        val size = f.optLong("filesize", 0L)
        return if (size > 0) size else f.optLong("filesize_approx", 0L)
    }

    /** Nis shkarkimin dhe kthen id-në e punës. Hedh IllegalArgumentException për të dhëna të gabuara. */
    fun start(payload: JSONObject): String {
        val url = payload.optString("url").trim()
        validateUrl(url)
        val mode = payload.optString("mode", "video")
        val container = payload.optString("container", "mp4")
        val height = payload.optString("height")
        var bitrate = payload.optString("bitrate", "192")

        when (mode) {
            "audio" -> {
                require(container in AUDIO_FORMATS) { "Format audio i panjohur." }
                if (bitrate !in AUDIO_BITRATES) bitrate = "192"
            }
            "video" -> {
                require(container in VIDEO_CONTAINERS) { "Format video i panjohur." }
                require(height.isNotEmpty() && height.all { it.isDigit() }) { "Zgjidhni një rezolucion." }
            }
            else -> throw IllegalArgumentException("Mënyrë e panjohur.")
        }

        val context = appContext!!
        val jobId = UUID.randomUUID().toString().replace("-", "")
        val dir = File(context.cacheDir, "downloads/$jobId").apply { mkdirs() }
        val job = Job()
        jobs[jobId] = job

        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--newline")
            .addOption("-o", "${dir.absolutePath}/%(title).150B [%(id)s].%(ext)s")
        if (mode == "audio") {
            request.addOption("-f", "bestaudio/best")
                .addOption("-x")
                .addOption("--audio-format", container)
            if (container == "mp3") request.addOption("--audio-quality", "${bitrate}K")
        } else {
            val h = height.toInt()
            request.addOption(
                "-f",
                "bv*[height=$h]+ba/b[height=$h]/bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b",
            )
            when (container) {
                "mp4" -> request.addOption("-S", "res,fps,ext:mp4:m4a")
                    .addOption("--merge-output-format", "mp4")
                    .addOption("--remux-video", "mp4")
                "webm" -> request.addOption("-S", "res,fps,ext:webm:webm")
                    .addOption("--merge-output-format", "webm/mkv")
                    .addOption("--recode-video", "webm")
                else -> request.addOption("-S", "res,fps")
                    .addOption("--merge-output-format", "mkv")
                    .addOption("--remux-video", "mkv")
            }
        }

        pool.execute { run(context, jobId, job, request, dir) }
        return jobId
    }

    private fun run(context: Context, jobId: String, job: Job, request: YoutubeDLRequest, dir: File) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytdownloader:$jobId")
        wakeLock.acquire(TimeUnit.HOURS.toMillis(3))
        try {
            awaitReady()
            YoutubeDL.getInstance().execute(request, jobId) { _, eta, line -> job.onLine(line, eta) }
            val file = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("Shkarkimi përfundoi, por skedari nuk u gjet.")
            job.status = "processing"
            val (uri, mime) = saveToDownloads(context, file)
            job.finish(file.name, uri, mime)
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            job.status = "error"
            job.error = friendlyError(e)
        } finally {
            dir.deleteRecursively()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    private fun saveToDownloads(context: Context, file: File): Pair<Uri, String> {
        val mime = mimeOf(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Skedari nuk u ruajt dot te Downloads.")
            try {
                resolver.openOutputStream(uri).use { out ->
                    out ?: throw IOException("Skedari nuk u ruajt dot te Downloads.")
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to mime
        }

        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        folder.mkdirs()
        var target = File(folder, file.name)
        var n = 1
        while (target.exists()) target = File(folder, "${file.nameWithoutExtension} (${n++}).${file.extension}")
        file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target) to mime
    }

    fun progress(jobId: String): JSONObject =
        jobs[jobId]?.toJson() ?: JSONObject().put("status", "error").put("error", "Shkarkimi nuk u gjet.")

    fun fileOf(jobId: String): Pair<Uri, String>? {
        val job = jobs[jobId] ?: return null
        val uri = job.uri ?: return null
        return uri to job.mime
    }

    private class Job {
        @Volatile var status = "queued"
        @Volatile var percent = 0.0
        @Volatile var stream = 0
        @Volatile var speed = 0L
        @Volatile var eta = 0L
        @Volatile var error: String? = null
        @Volatile var filename: String? = null
        @Volatile var uri: Uri? = null
        @Volatile var mime = "application/octet-stream"

        private val percentRe = Regex("""^\[download]\s+([\d.]+)%""")
        private val speedRe = Regex("""at\s+([\d.]+)\s*([KMG]?)i?B/s""")
        private val processingPrefixes = listOf(
            "[Merger]", "[ExtractAudio]", "[VideoRemuxer]", "[VideoConvertor]", "[FixupM", "[Fixup",
        )

        fun onLine(line: String, eta: Long) {
            val percentMatch = percentRe.find(line)
            when {
                line.startsWith("[download] Destination:") -> {
                    stream++
                    status = "downloading"
                    percent = 0.0
                }
                percentMatch != null -> {
                    status = "downloading"
                    if (stream == 0) stream = 1
                    percent = percentMatch.groupValues[1].toDoubleOrNull() ?: percent
                    speed = speedRe.find(line)?.let { m ->
                        val power = "KMG".indexOf(m.groupValues[2]) + 1
                        (m.groupValues[1].toDouble() * Math.pow(1024.0, power.toDouble())).toLong()
                    } ?: 0L
                    if (eta > 0) this.eta = eta
                }
                processingPrefixes.any { line.startsWith(it) } -> {
                    status = "processing"
                    percent = 100.0
                }
            }
        }

        fun finish(name: String, savedUri: Uri, savedMime: String) {
            filename = name
            uri = savedUri
            mime = savedMime
            percent = 100.0
            status = "finished"
        }

        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("percent", percent)
            .put("stream", stream)
            .put("speed", speed)
            .put("eta", eta)
            .put("error", error ?: JSONObject.NULL)
            .put("filename", filename ?: JSONObject.NULL)
    }
}
