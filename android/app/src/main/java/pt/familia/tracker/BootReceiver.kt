package pt.familia.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reinicia o serviço após o telemóvel arrancar ou a app ser atualizada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Prefs.enabled(context) && Prefs.isConfigured(context)) {
            LocationService.start(context)
        }
    }
}
