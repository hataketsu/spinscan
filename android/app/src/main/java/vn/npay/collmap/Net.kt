package vn.npay.collmap

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Everything this app says to the rig.
 *
 * Still HttpURLConnection and still no HTTP client dependency. The build has
 * Gradle now and could resolve one, but every call here is a handful of JSON
 * endpoints and one multipart upload on a LAN, and the upload path in
 * particular is tuned around streaming a JPEG straight out with a known length.
 * A client library would replace that with a different set of trade-offs, not
 * fewer.
 */
object Net {
    private const val CONNECT_MS = 6_000
    private const val READ_MS = 60_000

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            setRequestProperty("Accept", "application/json")
        }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        val stream = if (code >= 400) errorStream else inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code >= 400) throw RuntimeException("HTTP $code: ${brief(text)}")
        return text
    }

    /** FastAPI reports its errors as a JSON "detail"; surface just that. */
    private fun brief(text: String): String = try {
        JSONObject(text).get("detail").toString()
    } catch (_: Exception) {
        text.take(200)
    }

    fun getArray(base: String, path: String): JSONArray =
        JSONArray(open(base + path, "GET").body())

    fun getObject(base: String, path: String): JSONObject =
        JSONObject(open(base + path, "GET").body())

    fun postJson(base: String, path: String, payload: JSONObject? = null): JSONObject {
        val c = open(base + path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val data = (payload?.toString() ?: "{}").toByteArray()
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        val text = c.body()
        return if (text.isEmpty()) JSONObject() else JSONObject(text)
    }

    /**
     * A raw body POST for the endpoints that take bytes rather than a form.
     * The live preview goes out this way: a multipart wrapper around a frame
     * the server throws away a third of a second later is pure overhead.
     */
    fun postBytes(base: String, path: String, contentType: String, data: ByteArray): String {
        val c = open(base + path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", contentType)
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        return c.body()
    }

    /**
     * One photo per request, streamed straight from the JPEG bytes.
     *
     * Deliberately not batched: a capture run is long, and a per-photo request
     * means a dropped frame costs one retry instead of the whole session. The
     * field name is "files" because that is what the FastAPI handler binds.
     */
    fun uploadPhoto(base: String, project: String, filename: String,
                    jpeg: ByteArray): JSONObject {
        val boundary = "----collmap${System.nanoTime()}"
        val head = ("--$boundary\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"$filename\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val c = open("$base/api/projects/${enc(project)}/images", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(head.size.toLong() + jpeg.size + tail.size)
        DataOutputStream(c.outputStream).use { out ->
            out.write(head); out.write(jpeg); out.write(tail); out.flush()
        }
        return JSONObject(c.body())
    }

    /** Streams a download into [sink], reporting bytes so far. */
    fun download(url: String, sink: OutputStream, onProgress: ((Long, Long) -> Unit)?) {
        val c = open(url, "GET")
        if (c.responseCode >= 400) throw RuntimeException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            val buf = ByteArray(1 shl 16)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                sink.write(buf, 0, n)
                done += n
                onProgress?.invoke(done, total)
            }
        }
        sink.flush()
    }

    fun bytes(url: String): ByteArray {
        val out = ByteArrayOutputStream()
        download(url, out, null)
        return out.toByteArray()
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
