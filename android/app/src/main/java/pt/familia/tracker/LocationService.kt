package pt.familia.tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Serviço em primeiro plano que lê a localização e a envia para o servidor.
 *
 * Eficiência de bateria:
 *  - o filtro de distância mínima é aplicado pelo próprio sistema operativo
 *    (minUpdateDistanceMeters), por isso parado não acorda a app nem envia nada;
 *  - a prioridade BALANCED usa rede/Wi-Fi em vez de GPS sempre que chega;
 *  - os pontos são enviados em lote e a fila persiste se não houver rede.
 */
class LocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var liveUntil: Long = 0L
    private var currentInterval = -1
    private var currentMinDistance = -1

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { enqueueAndSend(it) }
        }
    }

    // Pede uma leitura única de tempos a tempos, mesmo parada (sinal de vida).
    private val heartbeat = object : Runnable {
        override fun run() {
            requestOneShot()
            val mins = Prefs.heartbeatMin(this@LocationService).coerceIn(1, 240)
            main.postDelayed(this, mins * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startUpdates()
        main.removeCallbacks(heartbeat)
        main.postDelayed(heartbeat, 15_000L)
        return START_STICKY
    }

    private fun startUpdates() {
        val interval = Prefs.intervalSec(this).coerceIn(5, 3600)
        val minDist = Prefs.minDistanceM(this).coerceIn(0, 5000)
        if (interval == currentInterval && minDist == currentMinDistance) return
        currentInterval = interval
        currentMinDistance = minDist

        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval * 1000L)
            .setMinUpdateIntervalMillis(interval * 1000L)
            .setMinUpdateDistanceMeters(minDist.toFloat())
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // sem permissão de localização: nada a fazer até o utilizador conceder
        }
    }

    private fun requestOneShot() {
        val creq = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(30_000L)
            .build()
        try {
            fused.getCurrentLocation(creq, null).addOnSuccessListener { loc ->
                if (loc != null) enqueueAndSend(loc)
            }
        } catch (_: SecurityException) { }
    }

    private fun batteryPct(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (p in 0..100) p else null
        } catch (_: Exception) { null }
    }

    private fun enqueueAndSend(loc: Location) {
        val pt = JSONObject().apply {
            put("t", loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            if (loc.hasAccuracy()) put("acc", loc.accuracy.toDouble())
            if (loc.hasSpeed()) put("spd", loc.speed.toDouble())
            batteryPct()?.let { put("bat", it) }
        }
        io.execute { persistAndFlush(pt) }
    }

    /** Junta o novo ponto à fila e tenta enviar tudo. Em falha, fica para a próxima. */
    private fun persistAndFlush(newPoint: JSONObject) {
        val queue = loadQueue()
        queue.add(newPoint)
        // Limita a fila para não crescer sem limite offline.
        while (queue.size > MAX_QUEUE) queue.removeAt(0)

        val result = Uploader.send(this, queue)
        if (result.ok) {
            saveQueue(emptyList())
            applyConfig(result)
        } else {
            saveQueue(queue)
        }
    }

    private fun applyConfig(r: IngestResult) {
        var changed = false
        r.intervalSec?.let { if (it in 5..3600 && it != Prefs.intervalSec(this)) { Prefs.setIntervalSec(this, it); changed = true } }
        r.minDistanceM?.let { if (it in 0..5000 && it != Prefs.minDistanceM(this)) { Prefs.setMinDistanceM(this, it); changed = true } }
        r.heartbeatMin?.let { if (it in 1..240) Prefs.setHeartbeatMin(this, it) }
        liveUntil = r.liveUntil
        if (changed) main.post { startUpdates() }
    }

    // --- Fila persistente (ficheiro JSON por linha) --------------------------
    private fun queueFile() = File(filesDir, "queue.jsonl")

    private fun loadQueue(): MutableList<JSONObject> {
        val f = queueFile()
        if (!f.exists()) return mutableListOf()
        return try {
            f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveQueue(points: List<JSONObject>) {
        try {
            if (points.isEmpty()) { queueFile().delete(); return }
            queueFile().writeText(points.joinToString("\n") { it.toString() })
        } catch (_: Exception) { }
    }

    // --- Notificação em primeiro plano (visível e obrigatória) ---------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacks(heartbeat)
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) { }
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "tracker_location"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUE = 500

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }
    }
}
