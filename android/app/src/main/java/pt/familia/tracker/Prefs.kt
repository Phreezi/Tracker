package pt.familia.tracker

import android.content.Context

/** Guarda a configuração local (conta do Firestore e definições de envio). */
object Prefs {
    private const val FILE = "tracker_prefs"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Identificação do telemóvel ------------------------------------------
    // O telemóvel só precisa de um NOME (ex.: "Gabriela"). A conta do Firestore
    // (tracker@sipfa.local) e a palavra-passe estão embutidas na app, por isso
    // quem instala não tem de saber credenciais — só escreve o nome.
    fun deviceName(ctx: Context): String = p(ctx).getString("deviceName", "") ?: ""
    fun setDeviceName(ctx: Context, v: String) =
        p(ctx).edit().putString("deviceName", v.trim()).apply()

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    fun intervalSec(ctx: Context): Int = p(ctx).getInt("intervalSec", 60)
    fun setIntervalSec(ctx: Context, v: Int) = p(ctx).edit().putInt("intervalSec", v).apply()

    fun minDistanceM(ctx: Context): Int = p(ctx).getInt("minDistanceM", 15)
    fun setMinDistanceM(ctx: Context, v: Int) = p(ctx).edit().putInt("minDistanceM", v).apply()


    // --- Estado do envio (para o ecrã e para o «pedir agora» do site) ----------
    /** Último pedido de localização (pedidoEm do rastreio/config) já respondido. */
    fun lastPedido(ctx: Context): Long = p(ctx).getLong("lastPedido", 0L)
    fun setLastPedido(ctx: Context, v: Long) = p(ctx).edit().putLong("lastPedido", v).apply()

    /** Quando foi o último envio aceite pelo Firestore (ms). */
    fun lastSentAt(ctx: Context): Long = p(ctx).getLong("lastSentAt", 0L)
    fun setLastSentAt(ctx: Context, v: Long) = p(ctx).edit().putLong("lastSentAt", v).apply()

    /** Última posição enviada — serve para dizer «no mesmo local». */
    fun lastLocation(ctx: Context): Pair<Double, Double>? {
        val a = (p(ctx).getString("lastLoc", null) ?: return null).split(',')
        val lat = a.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = a.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lng
    }
    fun setLastLocation(ctx: Context, lat: Double, lng: Double) =
        p(ctx).edit().putString("lastLoc", "$lat,$lng").apply()

    fun isConfigured(ctx: Context): Boolean = deviceName(ctx).isNotEmpty()
}
