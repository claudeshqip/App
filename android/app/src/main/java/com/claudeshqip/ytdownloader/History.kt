package com.claudeshqip.ytdownloader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lista e skedarëve të shkarkuar (faqja "Shkarkimet"), e ruajtur si JSON në memorien e app-it. */
object History {
    private const val TAG = "History"
    private const val MAX_ENTRIES = 500

    private fun file(context: Context) = File(context.filesDir, "history.json")

    @Synchronized
    private fun read(context: Context): MutableList<JSONObject> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val array = JSONArray(f.readText())
            MutableList(array.length()) { array.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "history unreadable", e)
            mutableListOf()
        }
    }

    @Synchronized
    private fun write(context: Context, entries: List<JSONObject>) {
        file(context).writeText(JSONArray(entries.take(MAX_ENTRIES)).toString())
    }

    @Synchronized
    fun add(context: Context, entry: JSONObject) {
        val entries = read(context)
        entries.add(0, entry)
        write(context, entries)
    }

    fun find(context: Context, id: String): JSONObject? = read(context).firstOrNull { it.optString("id") == id }

    /** Kthen listën; skedarët e fshirë jashtë app-it shënohen me "missing". */
    fun list(context: Context): JSONArray {
        val result = JSONArray()
        read(context).forEach { entry ->
            val copy = JSONObject(entry.toString())
            copy.put("missing", !exists(context, entry))
            copy.remove("path")
            result.put(copy)
        }
        return result
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val entries = read(context)
        val entry = entries.firstOrNull { it.optString("id") == id } ?: return
        try {
            deleteFile(context, entry)
        } catch (e: Exception) {
            Log.w(TAG, "could not delete file", e)
        }
        entries.remove(entry)
        write(context, entries)
    }

    private fun exists(context: Context, entry: JSONObject): Boolean {
        val path = entry.optString("path")
        if (path.isNotEmpty() && path != "null") return File(path).exists()
        val uri = Uri.parse(entry.optString("uri"))
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteFile(context: Context, entry: JSONObject) {
        val path = entry.optString("path")
        if (path.isNotEmpty() && path != "null") {
            File(path).delete()
            return
        }
        val uri = Uri.parse(entry.optString("uri"))
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
