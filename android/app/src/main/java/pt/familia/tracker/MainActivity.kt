package pt.familia.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Ecrã único da app: estado da partilha (com um botão grande para ligar/
 * desligar), nome do telemóvel e permissões em falta. Construído em código
 * (sem layouts XML), com cartões e botões arredondados; segue o tema claro/
 * escuro do telemóvel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var toggleBtn: MaterialButton
    private lateinit var permBox: LinearLayout

    private val fineLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val bgLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    // --- Cores (claro / escuro) ----------------------------------------------
    private val dark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    private val cBg: Int get() = if (dark) 0xFF0F1115.toInt() else 0xFFF3F5F9.toInt()
    private val cCard: Int get() = if (dark) 0xFF1A1D24.toInt() else Color.WHITE
    private val cText: Int get() = if (dark) 0xFFE8EAED.toInt() else 0xFF1B1F27.toInt()
    private val cMuted: Int get() = if (dark) 0xFF9AA0AA.toInt() else 0xFF6B7280.toInt()
    private val cLine: Int get() = if (dark) 0xFF2A2F3A.toInt() else 0xFFDDE2EA.toInt()
    private val cBlue = 0xFF4F8CFF.toInt()
    private val cGreen = 0xFF2F9E44.toInt()
    private val cRed = 0xFFE03131.toInt()

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(22), px(18), px(28))
        }

        // Cabeçalho
        root.addView(TextView(this).apply {
            text = "📍 Tracker"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })
        root.addView(TextView(this).apply {
            text = "Partilha de localização com a família"
            textSize = 14f
            setTextColor(cMuted)
            setPadding(0, px(2), 0, px(18))
        })

        // Cartão de estado + botão grande
        val (estado, estadoIn) = card(null)
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        linha.addView(statusDot, LinearLayout.LayoutParams(px(14), px(14)).apply { marginEnd = px(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        }
        statusSub = TextView(this).apply { textSize = 13f; setTextColor(cMuted) }
        col.addView(statusTitle)
        col.addView(statusSub)
        linha.addView(col)
        estadoIn.addView(linha)
        toggleBtn = pill("", cGreen, true) { alternar() }
        estadoIn.addView(toggleBtn)
        root.addView(estado)

        // Nome
        val (nomeCard, nomeIn) = card("Nome deste telemóvel")
        nameField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "ex.: Gabriela"
            setText(Prefs.deviceName(this@MainActivity))
            textSize = 17f
            setTextColor(cText)
            setHintTextColor(cMuted)
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(cBg)
                setStroke(px(1), cLine)
            }
            setPadding(px(14), px(12), px(14), px(12))
        }
        nomeIn.addView(nameField)
        nomeIn.addView(pill("Guardar nome", cBlue, false) {
            Prefs.setDeviceName(this, nameField.text.toString())
            toast("Nome guardado.")
            refresh()
        })
        root.addView(nomeCard)

        // Permissões (só mostra botão nas que faltam)
        val (permCard, permIn) = card("Permissões")
        permBox = permIn
        root.addView(permCard)

        // Aviso
        root.addView(TextView(this).apply {
            text = "Enquanto partilha, o telemóvel mostra sempre uma notificação. " +
                   "Só envia quando se desloca ou quando o site pede a localização. " +
                   "A monitorização deve ser do conhecimento de quem usa este telemóvel."
            textSize = 12f
            setTextColor(cMuted)
            setPadding(px(4), px(4), px(4), 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    // --- Ações -----------------------------------------------------------------
    private fun alternar() {
        if (Prefs.enabled(this)) {
            Prefs.setEnabled(this, false)
            LocationService.stop(this)
        } else {
            if (!Prefs.isConfigured(this)) { toast("Escreve e guarda o nome primeiro."); return }
            if (!hasFine()) { toast("Falta a permissão de localização."); return }
            Prefs.setEnabled(this, true)
            LocationService.start(this)
        }
        refresh()
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBg() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotif() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        if (!::toggleBtn.isInitialized) return
        val on = Prefs.enabled(this)
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) cGreen else cMuted)
        }
        statusTitle.text = if (on) "A partilhar" else "Parada"
        val nome = Prefs.deviceName(this)
        val ult = Prefs.lastSentAt(this)
        statusSub.text = buildString {
            append(if (nome.isNotEmpty()) nome else "Sem nome")
            if (ult > 0) append(" · último envio ").append(haQuanto(ult))
            else if (on) append(" · à espera do primeiro envio")
        }
        toggleBtn.text = if (on) "⏹   Parar partilha" else "▶   Iniciar partilha"
        toggleBtn.backgroundTintList = ColorStateList.valueOf(if (on) cRed else cGreen)

        while (permBox.childCount > 1) permBox.removeViewAt(1)
        permBox.addView(permRow("Localização", hasFine()) {
            fineLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        })
        permBox.addView(permRow("Localização «sempre» (2.º plano)", hasBg()) {
            if (!hasFine()) toast("Primeiro a localização.")
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        })
        permBox.addView(permRow("Notificações", hasNotif()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
    }

    private fun haQuanto(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "há ${s}s"
            s < 3600 -> "há ${s / 60} min"
            else -> "há ${s / 3600}h ${(s % 3600) / 60}min"
        }
    }

    // --- Ajudantes de UI -------------------------------------------------------
    private fun card(titulo: String?): Pair<MaterialCardView, LinearLayout> {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(18))
        }
        if (titulo != null) inner.addView(TextView(this).apply {
            text = titulo.uppercase()
            textSize = 12f
            letterSpacing = 0.06f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cMuted)
            setPadding(0, 0, 0, px(10))
        })
        val c = MaterialCardView(this).apply {
            radius = px(18).toFloat()
            cardElevation = px(2).toFloat()
            setCardBackgroundColor(cCard)
            strokeWidth = 0
            addView(inner)
        }
        c.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = px(14) }
        return c to inner
    }

    private fun pill(t: String, cor: Int, cheio: Boolean, onClick: () -> Unit) = MaterialButton(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        cornerRadius = px(28)
        insetTop = 0
        insetBottom = 0
        minHeight = px(54)
        if (cheio) {
            backgroundTintList = ColorStateList.valueOf(cor)
            setTextColor(Color.WHITE)
        } else {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(cor)
            strokeColor = ColorStateList.valueOf(cor)
            strokeWidth = px(2)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(14) }
    }

    private fun permRow(titulo: String, ok: Boolean, acao: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(6))
        }
        r.addView(TextView(this).apply {
            text = titulo
            textSize = 15f
            setTextColor(cText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (ok) {
            r.addView(TextView(this).apply {
                text = "✓ OK"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cGreen)
            })
        } else {
            r.addView(MaterialButton(this).apply {
                text = "Permitir"
                isAllCaps = false
                cornerRadius = px(20)
                insetTop = 0
                insetBottom = 0
                backgroundTintList = ColorStateList.valueOf(cBlue)
                setTextColor(Color.WHITE)
                setOnClickListener { acao() }
            })
        }
        return r
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
