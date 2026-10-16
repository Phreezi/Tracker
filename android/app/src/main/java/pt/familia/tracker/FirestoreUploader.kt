package pt.familia.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Envia os pontos de localização DIRETAMENTE para o Firestore do SIPFA,
 * sem servidor próprio e sem SDK do Firebase — só HttpURLConnection:
 *
 *  1. autentica-se na conta dedicada do telemóvel (`tracker@sipfa.local`) pela
 *     API REST do Firebase Auth e guarda o idToken (renovado pelo refreshToken);
 *  2. escreve os pontos num único `:commit`:
 *       · rastreioHist/{auto}  — um doc por ponto (percurso);
 *       · rastreio/atual       — a última posição (sobrescrita).
 *
 * As regras do Firestore só deixam esta conta escrever estas coleções e só o
 * admin do SIPFA as lê (extra/firestore.rules).
 */
object FirestoreUploader {

    // A apiKey do Firebase é pública (vai no site do SIPFA); não é segredo.
    private const val API_KEY = "AIzaSyA3GaOEEPerKqLbtIuUKUbtB23Z_IPLk9Q"
    private const val PROJECT = "sipfa-52e5b"
    // Conta dedicada do telemóvel. O e-mail é fixo; a palavra-passe vem embutida
    // no build (BuildConfig.ACCOUNT_PASSWORD, do secret TRACKER_ACCOUNT_PASSWORD)
    // para o telemóvel só precisar de um nome.
    private const val EMAIL = "tracker@sipfa.local"
    private fun password() = BuildConfig.ACCOUNT_PASSWORD
    private const val DOCS = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
    private const val MAX_WRITES = 400   // o Firestore aceita até 500 por commit

    @Volatile private var idToken: String? = null
    @Volatile private var idTokenExp: Long = 0L
    @Volatile private var refreshToken: String? = null

    private val rnd = SecureRandom()
    private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private fun randomId(): String {
        val sb = StringBuilder(20)
        repeat(20) { sb.append(ID_CHARS[rnd.nextInt(ID_CHARS.length)]) }
        return sb.toString()
    }

    private fun post(urlStr: String, body: String, bearer: String? = null): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: "erro")
        } finally {
            conn?.disconnect()
        }
    }

    /** Garante um idToken válido (renova ou faz login). null = sem credenciais/erro. */
    private fun ensureToken(ctx: Context): String? {
        val now = System.currentTimeMillis()
        idToken?.let { if (now < idTokenExp - 60_000L) return it }

        // Tenta renovar com o refresh token (não gasta a palavra-passe).
        refreshToken?.let { rt ->
            val (code, text) = post(
                "https://securetoken.googleapis.com/v1/token?key=$API_KEY",
                JSONObject().put("grant_type", "refresh_token").put("refresh_token", rt).toString()
            )
            if (code in 200..299) {
                val j = JSONObject(text)
                idToken = j.optString("id_token").ifEmpty { null }
                refreshToken = j.optString("refresh_token", rt)
                idTokenExp = now + (j.optString("expires_in", "3600").toLongOrNull() ?: 3600L) * 1000L
                if (idToken != null) return idToken
            }
        }

        // Login com a conta embutida (o telemóvel não guarda credenciais).
        val pass = password()
        if (pass.isEmpty()) return null
        val (code, text) = post(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY",
            JSONObject().put("email", EMAIL).put("password", pass).put("returnSecureToken", true).toString()
        )
        if (code in 200..299) {
            val j = JSONObject(text)
            idToken = j.optString("idToken").ifEmpty { null }
            refreshToken = j.optString("refreshToken").ifEmpty { null }
            idTokenExp = now + (j.optString("expiresIn", "3600").toLongOrNull() ?: 3600L) * 1000L
            return idToken
        }
        return null
    }

    /** Converte um ponto {t,lat,lng,acc,spd,bat} em `fields` tipados do Firestore. */
    private fun fields(p: JSONObject, received: Long, nome: String): JSONObject {
        val f = JSONObject()
        f.put("t", JSONObject().put("integerValue",
            (p.optLong("t").takeIf { it > 0 } ?: received).toString()))
        f.put("lat", JSONObject().put("doubleValue", p.getDouble("lat")))
        f.put("lng", JSONObject().put("doubleValue", p.getDouble("lng")))
        if (p.has("acc")) f.put("acc", JSONObject().put("doubleValue", p.optDouble("acc")))
        if (p.has("spd")) f.put("spd", JSONObject().put("doubleValue", p.optDouble("spd")))
        if (p.has("bat")) f.put("bat", JSONObject().put("integerValue", p.optInt("bat").toString()))
        if (nome.isNotEmpty()) f.put("nome", JSONObject().put("stringValue", nome.take(60)))
        f.put("atualizadoEm", JSONObject().put("integerValue", received.toString()))
        return f
    }

    private fun histWrite(p: JSONObject, received: Long, nome: String): JSONObject {
        val name = "projects/$PROJECT/databases/(default)/documents/rastreioHist/${randomId()}"
        return JSONObject().put("update", JSONObject().put("name", name).put("fields", fields(p, received, nome)))
    }

    private fun commit(writes: JSONArray, token: String): Boolean {
        if (writes.length() == 0) return true
        val (code, _) = post("$DOCS:commit", JSONObject().put("writes", writes).toString(), token)
        return code in 200..299
    }

    /** Envia o lote. true = tudo aceite (a fila pode ser limpa). */
    fun send(ctx: Context, points: List<JSONObject>): Boolean {
        if (points.isEmpty()) return true
        val token = ensureToken(ctx) ?: return false
        val now = System.currentTimeMillis()
        val nome = Prefs.deviceName(ctx)

        val validos = points.filter { it.has("lat") && it.has("lng") }
        if (validos.isEmpty()) return true

        // Histórico em blocos de MAX_WRITES; se um bloco falhar, pára (a fila
        // fica e tenta-se de novo — pode repetir alguns pontos, aceitável).
        var i = 0
        while (i < validos.size) {
            val bloco = JSONArray()
            var n = 0
            while (i < validos.size && n < MAX_WRITES) {
                bloco.put(histWrite(validos[i], now, nome)); i++; n++
            }
            if (!commit(bloco, token)) return false
        }

        // Última posição (doc `atual`, sobrescrito).
        val atual = JSONArray().put(
            JSONObject().put("update", JSONObject()
                .put("name", "projects/$PROJECT/databases/(default)/documents/rastreio/atual")
                .put("fields", fields(validos.last(), now, nome)))
        )
        return commit(atual, token)
    }
}
