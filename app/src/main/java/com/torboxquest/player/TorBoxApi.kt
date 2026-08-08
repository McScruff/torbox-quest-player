package com.torboxquest.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val BASE_URL = "https://api.torbox.app/v1/api"

private val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "wmv", "flv", "mpg", "mpeg", "m2ts"
)

class TorBoxApiException(message: String) : IOException(message)

object TorBoxApi {

    suspend fun fetchList(apiKey: String, type: SourceType): List<TorBoxItem> =
        withContext(Dispatchers.IO) {
            val body = httpGet("$BASE_URL/${type.segment}/mylist?bypass_cache=true", apiKey)
            val root = JSONObject(body)
            if (!root.optBoolean("success", true)) {
                throw TorBoxApiException(root.optString("detail", "Request failed"))
            }
            val data = root.opt("data")
            val array: JSONArray = when (data) {
                is JSONArray -> data
                else -> JSONArray()
            }
            (0 until array.length()).map { i -> parseItem(array.getJSONObject(i), type) }
        }

    suspend fun requestDownloadLink(
        apiKey: String,
        type: SourceType,
        itemId: Long,
        fileId: Long
    ): String = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/${type.segment}/requestdl" +
            "?token=${URLEncoder.encode(apiKey, "UTF-8")}" +
            "&${type.idParam}=$itemId" +
            "&file_id=$fileId" +
            "&redirect=false"
        val body = httpGet(url, apiKey)
        val root = JSONObject(body)
        if (!root.optBoolean("success", true)) {
            throw TorBoxApiException(root.optString("detail", "Could not get download link"))
        }
        val link = root.optString("data", "")
        if (link.isBlank()) {
            throw TorBoxApiException("TorBox returned an empty download link")
        }
        link
    }

    private fun parseItem(json: JSONObject, type: SourceType): TorBoxItem {
        val id = json.optLong("id", -1)
        val name = json.optString("name", json.optString("filename", "Unknown"))
        val size = json.optLong("size", 0L)
        val ready = json.optBoolean("download_finished", false) ||
            json.optBoolean("download_present", false) ||
            json.optBoolean("cached", false)
        val rawState = json.optString("download_state", "")
        val statusLabel = when {
            rawState.isNotBlank() -> rawState.replaceFirstChar { it.uppercase() }
            ready -> "Ready"
            else -> "In progress"
        }

        val allFiles = mutableListOf<TorBoxFile>()
        json.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val shortName = f.optString("short_name", f.optString("name", "file"))
                allFiles += TorBoxFile(
                    id = f.optLong("id", -1),
                    shortName = shortName,
                    size = f.optLong("size", 0L)
                )
            }
        }
        val videoFiles = allFiles.filter { file ->
            val ext = file.shortName.substringAfterLast('.', "").lowercase()
            ext in VIDEO_EXTENSIONS
        }
        val files = videoFiles.ifEmpty { allFiles }

        return TorBoxItem(
            id = id,
            type = type,
            name = name,
            size = size,
            ready = ready,
            statusLabel = statusLabel,
            createdAt = parseCreatedAt(json),
            files = files
        )
    }

    private fun parseCreatedAt(json: JSONObject): Long {
        val raw = json.optString("created_at", "")
        if (raw.isBlank()) return 0L
        return try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    private fun httpGet(urlString: String, apiKey: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readStream(it) } ?: ""
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw TorBoxApiException(
                    if (!detail.isNullOrBlank()) detail else "HTTP $code from TorBox"
                )
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append('\n')
            }
            return sb.toString()
        }
    }
}
