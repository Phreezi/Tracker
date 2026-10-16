package pt.familia.tracker

import android.content.Context

/** Guarda a configuração local (conta do Firestore e definições de envio). */
object Prefs {
    private const val FILE = "tracker_prefs"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Conta do telemóvel no Firebase/Firestore do SIPFA -------------------
    // Os pontos são escritos no Firestore do SIPFA com esta conta dedicada
    // (criada em Firebase → Authentication). O e-mail é fixo por omissão; só a
    // palavra-passe é que se escreve na app.
    fun accountEmail(ctx: Context): String =
        p(ctx).getString("accountEmail", "tracker@sipfa.local") ?: "tracker@sipfa.local"
    fun setAccountEmail(ctx: Context, v: String) =
        p(ctx).edit().putString("accountEmail", v.trim()).apply()

    fun accountPassword(ctx: Context): String = p(ctx).getString("accountPassword", "") ?: ""
    fun setAccountPassword(ctx: Context, v: String) =
        p(ctx).edit().putString("accountPassword", v).apply()

    // Campos antigos (servidor Node) — mantidos por compatibilidade, já não usados.
    fun serverUrl(ctx: Context): String = p(ctx).getString("serverUrl", "") ?: ""
    fun setServerUrl(ctx: Context, v: String) =
        p(ctx).edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    fun deviceToken(ctx: Context): String = p(ctx).getString("deviceToken", "") ?: ""
    fun setDeviceToken(ctx: Context, v: String) =
        p(ctx).edit().putString("deviceToken", v.trim()).apply()

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    fun intervalSec(ctx: Context): Int = p(ctx).getInt("intervalSec", 60)
    fun setIntervalSec(ctx: Context, v: Int) = p(ctx).edit().putInt("intervalSec", v).apply()

    fun minDistanceM(ctx: Context): Int = p(ctx).getInt("minDistanceM", 15)
    fun setMinDistanceM(ctx: Context, v: Int) = p(ctx).edit().putInt("minDistanceM", v).apply()

    fun heartbeatMin(ctx: Context): Int = p(ctx).getInt("heartbeatMin", 10)
    fun setHeartbeatMin(ctx: Context, v: Int) = p(ctx).edit().putInt("heartbeatMin", v).apply()

    fun isConfigured(ctx: Context): Boolean =
        accountEmail(ctx).isNotEmpty() && accountPassword(ctx).isNotEmpty()
}
