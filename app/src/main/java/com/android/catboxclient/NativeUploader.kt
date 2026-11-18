package com.android.catboxclient

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// ─────────  API 定義 ─────────

enum class ServiceType(val endpoint: String) {
    CATBOX("https://catbox.moe/user/api.php"),
    LITTERBOX("https://litterbox.catbox.moe/resources/internals/api.php")
}

enum class LitterboxTime(val value: String, @StringRes val labelRes: Int) {
    HOUR_1("1h", R.string.litterbox_time_1h),
    HOUR_12("12h", R.string.litterbox_time_12h),
    HOUR_24("24h", R.string.litterbox_time_24h),
    HOUR_72("72h", R.string.litterbox_time_72h)
}

private object ApiField {
    const val REQTYPE = "reqtype"
    const val USERHASH = "userhash"
    const val TIME = "time"
    const val FILE = "fileToUpload"
}

private object ReqType {
    const val FILE_UPLOAD = "fileupload"
}

class UploadException(
    @StringRes val messageRes: Int,
    val args: List<Any> = emptyList()
) : Exception()

// ───────── 上傳 ─────────

object NativeUploader {

    private const val CRLF = "\r\n"
    private const val BUFFER_SIZE = 64 * 1024
    private const val USER_AGENT = "CatboxUploader/1.0 (Android)"

    private data class FileMeta(val name: String, val size: Long, val mimeType: String)

    suspend fun upload(
        contentResolver: ContentResolver,
        uri: Uri,
        serviceType: ServiceType,
        time: LitterboxTime,
        userHash: String?,
        onProgress: (Float) -> Unit
    ): String {
        val meta = readMeta(contentResolver, uri)

        // reqtype → userhash/time → 檔案
        val fields = buildList {
            add(ApiField.REQTYPE to ReqType.FILE_UPLOAD)
            when (serviceType) {
                ServiceType.CATBOX ->
                    if (!userHash.isNullOrBlank()) add(ApiField.USERHASH to userHash.trim())

                ServiceType.LITTERBOX -> add(ApiField.TIME to time.value)
            }
        }

        val boundary = "----CatboxUploader${UUID.randomUUID().toString().replace("-", "")}"

        val head = buildString {
            fields.forEach { (name, value) ->
                append("--$boundary$CRLF")
                append("Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF")
                append(value).append(CRLF)
            }
            append("--$boundary$CRLF")
            append("Content-Disposition: form-data; name=\"${ApiField.FILE}\"; filename=\"${meta.name}\"$CRLF")
            append("Content-Type: ${meta.mimeType}$CRLF$CRLF")
        }.toByteArray(Charsets.UTF_8)
        val tail = "$CRLF--$boundary--$CRLF".toByteArray(Charsets.UTF_8)

        val connection = (URL(serviceType.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (meta.size >= 0) {
                setFixedLengthStreamingMode(head.size + meta.size + tail.size)
            } else {
                setChunkedStreamingMode(BUFFER_SIZE)
            }
        }

        try {
            connection.outputStream.use { output ->
                output.write(head)

                val input = contentResolver.openInputStream(uri)
                    ?: throw UploadException(R.string.error_read_file)
                input.use {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var sent = 0L
                    var lastUpdate = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = it.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        sent += read

                        val now = System.currentTimeMillis()
                        if (meta.size > 0 && now - lastUpdate > 100) {
                            onProgress((sent.toFloat() / meta.size).coerceAtMost(1f))
                            lastUpdate = now
                        }
                    }
                }

                output.write(tail)
                output.flush()
            }
            onProgress(1f)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()

            if (code == 200) {
                if (body.startsWith("https://") && !body.contains('\n') && !body.contains(' ')) {
                    return body
                }
                throw if (body.isEmpty()) {
                    UploadException(R.string.error_empty_response)
                } else {
                    UploadException(R.string.error_server_response, listOf(body.take(200)))
                }
            }

            throw when {
                code == 502 || code == 503 || code == 504 ->
                    UploadException(R.string.error_server_busy, listOf(code))

                body.isEmpty() || body.startsWith("<") ->
                    UploadException(R.string.error_http, listOf(code))

                else ->
                    UploadException(R.string.error_http_detail, listOf(code, body.take(200)))
            }
        } finally {
            connection.disconnect()
        }
    }

    fun queryFileSize(cr: ContentResolver, uri: Uri): Long = readMeta(cr, uri).size

    /** 取得原始檔名與大小 */
    private fun readMeta(cr: ContentResolver, uri: Uri): FileMeta {
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name: String? = null
        var size = -1L

        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }

        if (size < 0) {
            size = runCatching {
                cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
        }

        if (name.isNullOrBlank()) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            name = "upload_${System.currentTimeMillis()}.$ext"
        }

        val safeName = name.replace(Regex("[\"\\r\\n]"), "_")
        return FileMeta(safeName, size, mime)
    }
}