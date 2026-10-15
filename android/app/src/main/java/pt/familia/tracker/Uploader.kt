package pt.familia.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de um envio: inclui as definições devolvidas pelo servidor. */
data class IngestResult(
    val ok: Boolean,
    val intervalSec: Int? = null,
    val minDistanceM: Int? = null,
    val heartbeatMin: Int? = null,
    val liveUntil: Long = 0L,
)

/** Envia pontos para o servidor usando apenas HttpURLConnection (sem dependências). */
object Uploader {

    fun send(ctx: Context, points: List<JSONObject>): IngestResult {
        val base = Prefs.serverUrl(ctx)
        val token = Prefs.deviceToken(ctx)
        if (base.isEmpty() || token.isEmpty()) return IngestResult(false)

        val body = JSONObject().put("points", JSONArray(points)).toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/api/ingest").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Token", token)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) return IngestResult(false)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val cfg = json.optJSONObject("config")
            IngestResult(
                ok = json.optBoolean("ok", false),
                intervalSec = cfg?.optInt("intervalSec"),
                minDistanceM = cfg?.optInt("minDistanceM"),
                heartbeatMin = cfg?.optInt("heartbeatMin"),
                liveUntil = cfg?.optLong("liveUntil") ?: 0L,
            )
        } catch (e: Exception) {
            IngestResult(false)
        } finally {
            conn?.disconnect()
        }
    }
}
