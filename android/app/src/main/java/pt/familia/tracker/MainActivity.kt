package pt.familia.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Ecrã de configuração: nome do telemóvel e permissões.
 * Construído em código para não depender de recursos de layout.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var status: TextView

    private val fineLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val bgLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Tracker — configuração"
            textSize = 22f
            setPadding(0, 0, 0, pad)
        })

        root.addView(label("Nome deste telemóvel (ex.: Gabriela)"))
        nameField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "nome"
            setText(Prefs.deviceName(this@MainActivity))
        }
        root.addView(nameField)

        root.addView(button("Guardar configuração") {
            Prefs.setDeviceName(this, nameField.text.toString())
            toast("Guardado.")
            refresh()
        })

        root.addView(spacer(pad))
        root.addView(button("1 · Permitir localização") {
            fineLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        })
        root.addView(button("2 · Permitir localização em segundo plano") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else toast("Não é necessário nesta versão do Android.")
        })
        root.addView(button("3 · Permitir notificações") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else toast("Não é necessário nesta versão do Android.")
        })

        root.addView(spacer(pad))
        root.addView(button("▶ Iniciar partilha") {
            if (!Prefs.isConfigured(this)) { toast("Escreve o nome do telemóvel primeiro."); return@button }
            if (!hasFine()) { toast("Falta a permissão de localização."); return@button }
            Prefs.setEnabled(this, true)
            LocationService.start(this)
            refresh()
        })
        root.addView(button("⏹ Parar partilha") {
            Prefs.setEnabled(this, false)
            LocationService.stop(this)
            refresh()
        })

        root.addView(spacer(pad))
        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Esta app mostra sempre uma notificação enquanto partilha a localização. " +
                   "A monitorização deve ser do conhecimento de quem usa este telemóvel."
            setPadding(0, pad, 0, 0)
            alpha = 0.7f
        })

        setContentView(wrapScroll(root))
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun hasFine() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBg() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        if (!::status.isInitialized) return
        val sb = StringBuilder()
        sb.append("Configurado: ").append(if (Prefs.isConfigured(this)) "sim" else "não").append('\n')
        sb.append("Localização: ").append(if (hasFine()) "ok" else "em falta").append('\n')
        sb.append("Segundo plano: ").append(if (hasBg()) "ok" else "em falta").append('\n')
        sb.append("Notificações: ").append(if (hasNotif()) "ok" else "em falta").append('\n')
        sb.append("Partilha: ").append(if (Prefs.enabled(this)) "ATIVA" else "parada")
        status.text = sb.toString()
    }

    // --- Ajudantes de UI -----------------------------------------------------
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4); alpha = 0.8f }
    private fun spacer(h: Int) = View(this).apply { minimumHeight = h }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { onClick() }
    }
    private fun wrapScroll(v: View) = android.widget.ScrollView(this).apply { addView(v) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
