package com.overdrive.app.navmap.google

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.util.ScratchRoot
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Daemon copy of the app's latest Google usage snapshot, held in memory and on disk for the settings page. */
object GoogleUsageMirror {

    private const val FILE_NAME = "navmap_google_usage.json"
    private const val MAX_BYTES = 16 * 1024

    private val logger = DaemonLogger.getInstance("GoogleUsageMirror")
    private val lock = Any()
    private var latestText: String? = null
    private var loaded = false

    /** Keeps [json] when it is a JSON object of at most 16 KB; anything else is ignored and returns false. */
    @JvmStatic
    fun store(json: String?): Boolean {
        if (json == null || json.length > MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        val text = try {
            JSONObject(json).toString()
        } catch (_: Exception) {
            return false
        } catch (_: StackOverflowError) {
            return false
        }
        synchronized(lock) {
            loaded = true
            if (text == latestText) return true
            latestText = text
            write(text)
        }
        return true
    }

    /** A copy of the latest snapshot, read from disk on first use; null when none was stored. */
    @JvmStatic
    fun latest(): JSONObject? {
        val text = synchronized(lock) {
            if (!loaded) {
                loaded = true
                latestText = read()
            }
            latestText
        } ?: return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun read(): String? = try {
        val file = ScratchRoot.file(FILE_NAME)
        if (!file.isFile || file.length() > MAX_BYTES) null
        else JSONObject(file.readText(Charsets.UTF_8)).toString()
    } catch (_: Exception) {
        null
    }

    // Temp file + rename so a reader never sees a half-written snapshot.
    private fun write(text: String) {
        val target = ScratchRoot.file(FILE_NAME)
        val tmp = File(target.path + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                logger.warn("Usage snapshot rename failed")
            }
        } catch (e: Exception) {
            tmp.delete()
            logger.warn("Usage snapshot write failed: " + e.javaClass.simpleName)
        }
    }
}
