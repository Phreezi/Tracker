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
 * Serviço em primeiro plano que lê a localização e a envia para o Firestore
 * do SIPFA.
 *
 * Só ESCREVE em dois casos:
 *  - andou pelo menos a distância mínima (filtro aplicado pelo próprio sistema
 *    operativo — parada não acorda a app nem envia nada);
 *  - o site pediu «localização agora» (pedidoEm no doc rastreio/config): lê a
 *    posição, envia-a e diz se continua «no mesmo local».
 * As ordens do site (rastreio/config: intervalo, distância, «ao vivo», pedido)
 * são lidas a cada 60 s — ou ao intervalo do «ao vivo», enquanto ligado.
 * Pontos em lote; a fila persiste se não houver rede.
 */
class LocationService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var currentInterval = -1
    private var currentMinDistance = -1
    @Volatile private var pedidoEmCurso = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { enqueueAndSend(it, null) }
        }
    }

    // Lê as ordens do site e volta a agendar-se (60 s; no «ao vivo», mais depressa).
    private val controlo = object : Runnable {
        override fun run() {
            val self = this
            io.execute {
                val proxima = try { verificarOrdens() } catch (_: Exception) { 60 }
                main.postDelayed(self, proxima * 1000L)
            }
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
        main.removeCallbacks(controlo)
        main.post(controlo)
        // Um ponto logo ao arrancar, para o site ver que a partilha começou.
        main.postDelayed({ requestOneShot() }, 5_000L)
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
                if (loc != null) enqueueAndSend(loc, null)
            }
        } catch (_: SecurityException) { }
    }

    /** Lê rastreio/config e aplica. Devolve daqui a quantos segundos voltar a ler. */
    private fun verificarOrdens(): Int {
        val cfg = FirestoreUploader.getConfig(this) ?: return 60
        val agora = System.currentTimeMillis()
        val vivo = cfg.optLong("aoVivoAte", 0L) > agora
        val intNormal = cfg.optInt("intervalSec", 60).coerceIn(5, 3600)
        val intVivo = cfg.optInt("intervaloAoVivoSec", 5).coerceIn(5, 600)
        val dist = cfg.optInt("minDistanceM", Prefs.minDistanceM(this)).coerceIn(0, 5000)
        val intervalo = if (vivo) intVivo else intNormal

        var mudou = false
        if (intervalo != Prefs.intervalSec(this)) { Prefs.setIntervalSec(this, intervalo); mudou = true }
        if (dist != Prefs.minDistanceM(this)) { Prefs.setMinDistanceM(this, dist); mudou = true }
        if (mudou) main.post { startUpdates() }

        // Pedido de localização agora (ignora pedidos com mais de 10 min).
        val pedido = cfg.optLong("pedidoEm", 0L)
        if (pedido > Prefs.lastPedido(this) && pedido > agora - 10 * 60_000L && pedido != pedidoEmCurso) {
            pedidoEmCurso = pedido
            main.post { responderPedido(pedido) }
        }
        return if (vivo) intVivo else 60
    }

    /** Lê a posição com precisão e envia-a marcada como resposta ao pedido. */
    private fun responderPedido(pedido: Long) {
        val creq = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(10_000L)
            .setDurationMillis(30_000L)
            .build()
        try {
            fused.getCurrentLocation(creq, null)
                .addOnSuccessListener { loc -> if (loc != null) enqueueAndSend(loc, pedido) else pedidoEmCurso = 0L }
                .addOnFailureListener { pedidoEmCurso = 0L }
        } catch (_: SecurityException) { pedidoEmCurso = 0L }
    }

    private fun batteryPct(): Int? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (p in 0..100) p else null
        } catch (_: Exception) { null }
    }

    private fun enqueueAndSend(loc: Location, pedido: Long?) {
        val ctx = this
        val pt = JSONObject().apply {
            put("t", loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            if (loc.hasAccuracy()) put("acc", loc.accuracy.toDouble())
            if (loc.hasSpeed()) put("spd", loc.speed.toDouble())
            batteryPct()?.let { put("bat", it) }
            if (pedido != null) {
                put("pedido", pedido)
                Prefs.lastLocation(ctx)?.let { (lat, lng) ->
                    val d = FloatArray(1)
                    Location.distanceBetween(lat, lng, loc.latitude, loc.longitude, d)
                    val limiar = maxOf(Prefs.minDistanceM(ctx).toFloat(),
                                       if (loc.hasAccuracy()) loc.accuracy else 0f, 15f)
                    put("mesmoLocal", d[0] <= limiar)
                }
            }
        }
        io.execute { persistAndFlush(pt, loc, pedido) }
    }

    /** Junta o novo ponto à fila e tenta enviar tudo. Em falha, fica para a próxima. */
    private fun persistAndFlush(newPoint: JSONObject, loc: Location, pedido: Long?) {
        val queue = loadQueue()
        queue.add(newPoint)
        // Limita a fila para não crescer sem limite offline.
        while (queue.size > MAX_QUEUE) queue.removeAt(0)

        val ok = FirestoreUploader.send(this, queue)
        if (ok) {
            saveQueue(emptyList())
            Prefs.setLastLocation(this, loc.latitude, loc.longitude)
            Prefs.setLastSentAt(this, System.currentTimeMillis())
            if (pedido != null) Prefs.setLastPedido(this, pedido)
        } else {
            saveQueue(queue)
        }
        if (pedido != null) pedidoEmCurso = 0L
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
        main.removeCallbacks(controlo)
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
