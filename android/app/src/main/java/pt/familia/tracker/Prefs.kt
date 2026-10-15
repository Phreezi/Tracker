package pt.familia.tracker

import android.content.Context

/** Guarda a configuração local (URL do servidor, token e definições de envio). */
object Prefs {
    private const val FILE = "tracker_prefs"
    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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
        serverUrl(ctx).isNotEmpty() && deviceToken(ctx).isNotEmpty()
}
