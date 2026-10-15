package com.kita.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val USER = 0
private const val AI = 1
private const val KIND_DOTS = -1
private const val BLUE_ACTION = "#0AACFF"
private const val BLUE = "#0B72D9"

class MainActivity : Activity() {

    private val presets = listOf(
        Preset("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash"),
        Preset("OpenAI", "openai", "https://api.openai.com/v1", ""),
        Preset("Anthropic", "anthropic", "https://api.anthropic.com/v1", ""),
        Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", ""),
        Preset("Groq", "openai", "https://api.groq.com/openai/v1", ""),
        Preset("DeepSeek", "openai", "https://api.deepseek.com", ""),
        Preset("Ollama lokal", "openai", "http://127.0.0.1:11434/v1", ""),
        Preset("Kustom (OpenAI)", "openai", "", ""),
        Preset("CLI Termux", "cli", "gemini -p {prompt}", "")
    )
    private var prov = "Gemini"

    private fun preset(n: String) = presets.firstOrNull { it.name == n } ?: presets[0]
    private fun pvOf(n: String, k: String): String {
        val v = prefs.getString("p_${n}_$k", "") ?: ""
        return if (k == "key") KeyVault.dec(v) else v
    }

    /** Enkripsi API key lama yang masih tersimpan sebagai teks biasa. */
    private fun migrateKeys() {
        val e = prefs.edit()
        for (p in presets) {
            val raw = prefs.getString("p_${p.name}_key", "") ?: ""
            if (raw.isNotEmpty() && !KeyVault.isEncrypted(raw)) e.putString("p_${p.name}_key", KeyVault.enc(raw))
        }
        val legacy = prefs.getString("key", "") ?: ""
        if (legacy.isNotEmpty() && (prefs.getString("p_Gemini_key", "") ?: "").isEmpty()) {
            e.putString("p_Gemini_key", KeyVault.enc(legacy))
        }
        e.remove("key").apply()
    }
    private fun cfgKey(): String {
        val k = pvOf(prov, "key")
        return if (k.isEmpty() && prov == "Gemini") (prefs.getString("key", "") ?: "") else k
    }
    private fun cfgModel(): String = pvOf(prov, "model").ifEmpty { preset(prov).model }
    private fun cfgBase(): String = pvOf(prov, "base").ifEmpty { preset(prov).base }
    private fun needsKey(): Boolean = preset(prov).fmt != "cli" && !cfgBase().startsWith("http://")

    private val desk =
        "Kalimat singkat (maks 8 kata) berbahasa Indonesia tentang apa yang sedang kamu lakukan, " +
        "contoh: 'Membaca MainActivity.kt' atau 'Memperbaiki error build'"

    private lateinit var prefs: SharedPreferences
    private lateinit var store: ChatStore
    private val hist = ArrayList<Hist>()
    private var chatId = ""
    private var chatTitle = ""
    private var chatApk = ""
    private val storeLock = Any()
    private val termuxHome = "/data/data/com.termux/files/home"
    private val termuxBin = "/data/data/com.termux/files/usr/bin"
    private var workDirField: EditText? = null

    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var emptyState: View
    private lateinit var folderHint: TextView
    private lateinit var modelLabel: TextView
    private var modelChip: TextView? = null
    private var welcomeFolder: TextView? = null
    private lateinit var input: EditText
    private lateinit var actionIcon: IconView
    private lateinit var actionBg: GradientDrawable

    // Baris status tunggal (ikon + teks berkilau + tanda >)
    private lateinit var statusRoot: LinearLayout
    private lateinit var statusLead: FrameLayout
    private lateinit var statusTitle: ShimmerText
    private lateinit var statusChevron: IconView
    private lateinit var statusDetail: TextView
    private var leadKind = -2
    private var detailOpen = false
    @Volatile private var curKind = KIND_DOTS
    @Volatile private var curTitle = ""
    @Volatile private var curDetail = ""

    @Volatile private var busy = false
    @Volatile private var stopRequested = false
    private var reqCode = 0
    private var worker: Thread? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var runToken: String? = null
    private val termuxLatch = AtomicReference<CountDownLatch?>(null)
    private val approvalQueue = AtomicReference<LinkedBlockingQueue<Boolean>?>(null)
    private var approvalDialog: AlertDialog? = null
    private var pendingInstall: File? = null
    // true hanya setelah pengguna membuka halaman izin; mencegah pemasangan otomatis saat kembali dari layar lain (mis. pemilih file).
    private var awaitingInstallPermission = false
    private var installReceiver: BroadcastReceiver? = null
    @Volatile private var installSessionId = -1

    /** Mode otomatis penuh: semua perintah dan penulisan file dijalankan tanpa konfirmasi. */
    private fun fullAuto(): Boolean = ::prefs.isInitialized && prefs.getBoolean("fullAuto", true)

    private fun autoRead(): Boolean = ::prefs.isInitialized && prefs.getBoolean("autoRead", true)

    private fun col(s: String) = Color.parseColor(s)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // Skala proporsional terhadap lebar layar contoh (874 px) agar tata letak 1:1 di semua HP.
    private val ui: Float by lazy {
        minOf(resources.displayMetrics.widthPixels.toFloat(), 480f * resources.displayMetrics.density) / 874f
    }
    private fun u(v: Float): Int = Math.round(v * ui)

    /** Ukuran teks (px): proporsional dengan layar, tetapi tidak pernah di bawah [minSp]. */
    private fun tsz(v: Float, minSp: Float = 14f): Float = maxOf(
        v * ui,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, resources.displayMetrics)
    )

    private fun ripple(round: Boolean, alpha: Int): RippleDrawable {
        val a = if (alpha > 0) alpha else 28
        val tint = if (alpha > 0) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
        val mask = GradientDrawable().apply {
            setColor(Color.BLACK)
            if (round) cornerRadius = u(100f).toFloat() else shape = GradientDrawable.OVAL
        }
        return RippleDrawable(ColorStateList.valueOf(tint), null, mask)
    }

    private fun softShadow(v: View, elev: Float, alpha: Int) {
        v.elevation = elev
        if (Build.VERSION.SDK_INT >= 28) {
            v.outlineAmbientShadowColor = Color.argb(alpha, 30, 80, 140)
            v.outlineSpotShadowColor = Color.argb(alpha, 30, 80, 140)
        }
    }

    /** Tombol bulat putih dengan bayangan lembut (menu & obrolan baru di bilah atas). */
    private fun roundButton(icon: IconView, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        softShadow(this, u(4f).toFloat(), 38)
        foreground = ripple(false, 0)
        addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))
        setOnClickListener { onClick() }
    }

    /** Menu pada tombol "Tingkatkan": model, pengaturan, folder kerja, hapus. */
    private fun showMainMenu(anchor: View) {
        val model = if (preset(prov).fmt == "cli") "CLI" else cfgModel().ifBlank { prov }
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Ganti model · $model")
        m.menu.add(0, 5, 1, "Riwayat percakapan")
        m.menu.add(0, 6, 2, "Percakapan baru")
        m.menu.add(0, 3, 3, folderHint.text.toString())
        m.menu.add(0, 2, 4, "Pengaturan")
        m.menu.add(0, 4, 5, "Hapus percakapan ini")
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> pickModel()
                2 -> showSettings()
                3 -> pickWorkDir()
                4 -> confirmDelete()
                5 -> showHistory()
                6 -> newChat()
            }
            true
        }
        m.show()
    }

    private fun decl(name: String, desc: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for ((n, d) in params) {
            props.put(n, JSONObject().put("type", "string").put("description", d))
            req.put(n)
        }
        return JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
    }

    private val tools: JSONArray by lazy {
        JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray()
                    .put(
                        decl(
                            "run_command",
                            "Jalankan perintah bash di Termux (ls, cat, grep, ./gradlew, dll).",
                            "deskripsi" to desk, "command" to "Perintah bash"
                        )
                    )
                    .put(
                        decl(
                            "write_file",
                            "Tulis (timpa) file dengan isi lengkap. Path relatif terhadap home Termux.",
                            "deskripsi" to desk, "path" to "Path file", "content" to "Isi lengkap file"
                        )
                    )
            )
        )
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        try { migrateKeys() } catch (_: Exception) {}
        prov = prefs.getString("prov", "Gemini") ?: "Gemini"

        window.statusBarColor = col("#FDFEFF")
        window.navigationBarColor = col("#99CEFC")
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SkyDrawable()
        }

        // ---- Bilah atas: [menu]  [✦ Tingkatkan] ............ [obrolan baru]
        val menuIcon = IconView(this, IconView.MENU, col("#111111"))
        val menuBtn = roundButton(menuIcon) { showHistory() }.also { it.contentDescription = "Riwayat percakapan" }
        val upgradeText = TextView(this).apply {
            text = "Pilih model"
            maxWidth = u(430f)
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tsz(29.5f))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(col("#0B6FD2"))
            includeFontPadding = false
            maxLines = 1
        }
        modelChip = upgradeText
        val upgradeBtn = LinearLayout(this).apply {
            contentDescription = "Model dan menu"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(col("#E6F1FD")); cornerRadius = u(100f).toFloat() }
            foreground = ripple(true, 0)
            setPadding(u(25f), 0, u(31f), 0)
            addView(IconView(this@MainActivity, IconView.SPARKLE, col("#0B72D9")), LinearLayout.LayoutParams(u(30f), u(30f)))
            addView(upgradeText, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = u(17f) })
            setOnClickListener { showMainMenu(this) }
        }
        val tempIcon = IconView(this, IconView.BUBBLE, col("#111111"))
        val tempBtn = roundButton(tempIcon) { newChat() }.also { it.contentDescription = "Percakapan baru" }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(32f), u(24f), u(32f), u(18f))
            clipChildren = false
            clipToPadding = false
            addView(menuBtn, LinearLayout.LayoutParams(u(88f), u(88f)))
            addView(upgradeBtn, LinearLayout.LayoutParams(WRAP, u(88f)).apply { marginStart = u(16f) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(tempBtn, LinearLayout.LayoutParams(u(88f), u(88f)))
        }

        // Label model & petunjuk folder tetap ada (dipakai menu), tetapi tidak ditampilkan di layar utama
        modelLabel = TextView(this)
        updateModelLabel()
        folderHint = TextView(this)
        updateFolderHint()

        // ---- Area tengah: daftar chat (kosong = layar bersih seperti contoh)
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(32f), dp(4), u(32f), dp(16))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chatBox)
        }
        emptyState = buildWelcome()
        val center = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        buildStatusRow()

        // ---- Kolom input kapsul: [+]  Tanya ...  [mic] (●)
        val plusIcon = IconView(this, IconView.PLUS, col("#111111"))
        val plusBtn = plusIcon.apply {
            contentDescription = "Lampirkan file dan contoh perintah"
            foreground = ripple(false, 0)
            setOnClickListener { showTemplates(this) }
        }
        input = EditText(this).apply {
            hint = "Ketik perintah atau pertanyaan"
            setHintTextColor(col("#ABABB0"))
            setTextColor(col("#111111"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, tsz(30f))
            background = null
            maxLines = 5
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, u(20f), 0, u(20f))
        }
        val micIcon = IconView(this, IconView.MIC, col("#111111"))
        val micBtn = micIcon.apply {
            contentDescription = "Input suara"
            foreground = ripple(false, 0)
            setOnClickListener { voice() }
        }
        actionIcon = IconView(this, IconView.WAVE, Color.WHITE)
        actionBg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(BLUE_ACTION)) }
        val actionBtn = FrameLayout(this).apply {
            contentDescription = "Kirim atau berhenti"
            background = actionBg
            foreground = ripple(false, 60)
            addView(actionIcon, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { onAction() }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshAction() }
        })
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(106f)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = u(100f).toFloat() }
            softShadow(this, u(7f).toFloat(), 46)
            setPadding(u(6f), 0, u(19f), 0)
            addView(plusBtn, LinearLayout.LayoutParams(u(88f), u(88f)))
            addView(input, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = u(6f) })
            addView(micBtn, LinearLayout.LayoutParams(u(88f), u(88f)))
            addView(actionBtn, LinearLayout.LayoutParams(u(66f), u(66f)).apply { marginStart = u(26f) })
        }
        refreshAction()

        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(pill, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(u(32f), u(8f), u(32f), u(50f))
        })
        setContentView(root)

        store = ChatStore(this)
        showLastCrashIfAny()
        registerInstallReceiver()
        restoreActiveChat()
        val perms = arrayListOf("com.termux.permission.RUN_COMMAND")
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
        if (needsKey() && cfgKey().isEmpty()) showSettings()
    }

    @Volatile private var inForeground = false

    override fun onPause() {
        inForeground = false
        saveChat()
        super.onPause()
    }

    private fun notifyDone(text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(
                android.app.NotificationChannel("done", "Tugas selesai", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = android.app.Notification.Builder(this, "done")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Kita Agent: tugas selesai")
                .setContentText(text.take(120))
                .setStyle(android.app.Notification.BigTextStyle().bigText(text.take(400)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        } catch (_: Exception) {}
    }

    private fun lastReply(): String = synchronized(storeLock) {
        hist.lastOrNull { it.role == 1 && it.text.isNotBlank() }?.text?.trim()
    } ?: "Selesai."

    override fun onResume() {
        super.onResume()
        inForeground = true
        val f = pendingInstall
        if (f != null && awaitingInstallPermission) {
            awaitingInstallPermission = false
            pendingInstall = null
            if (f.exists() && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) {
                installWithSession(f)
            }
        }
    }

    override fun onDestroy() {
        installReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun refreshAction() {
        if (!::actionIcon.isInitialized) return
        actionIcon.color = Color.WHITE
        if (busy) {
            actionIcon.type = IconView.STOP
            actionBg.setColor(col("#1B1B1F"))
            return
        }
        val has = !input.text.isNullOrBlank()
        actionIcon.type = if (has) IconView.SEND else IconView.WAVE
        actionBg.setColor(col(BLUE_ACTION))
    }

    private fun updateFolderHint() {
        if (!::folderHint.isInitialized) return
        val w = (prefs.getString("workdir", "") ?: "").trim()
        folderHint.text = if (w.isEmpty()) "Pilih folder kerja" else "Folder kerja · " + shortPath(w)
        refreshWelcomeFolder()
    }

    private fun workDirAbs(): String {
        var w = (prefs.getString("workdir", "") ?: "").trim()
        if (w.isEmpty() || w == "~") return termuxHome
        if (w.startsWith("~/")) return termuxHome + "/" + w.removePrefix("~/").trimStart('/')
        if (!w.startsWith("/")) return "$termuxHome/" + w.trimStart('/')
        return w.trimEnd('/').ifEmpty { termuxHome }
    }

    // ------------------------------------------------ Baris status (satu baris, berubah-ubah)

    private fun buildStatusRow() {
        statusLead = FrameLayout(this)
        statusTitle = ShimmerText(this).apply {
            textSize = 15f
            setTextColor(col("#6B6B66"))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setShimmer(true)
        }
        statusChevron = IconView(this, IconView.CHEVRON, col("#7A7A75"))
        statusDetail = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(col("#3C3C38"))
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 255, 255, 255))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        val stopPill = TextView(this).apply {
            text = "Berhenti"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(col("#C2410C"))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { requestStop() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(statusLead, LinearLayout.LayoutParams(dp(32), dp(24)))
            addView(statusTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(stopPill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8); marginEnd = dp(4) })
            addView(statusChevron, LinearLayout.LayoutParams(dp(24), dp(24)))
            setOnClickListener { toggleDetail() }
        }
        statusRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(statusDetail, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun toggleDetail() {
        if (curDetail.isEmpty()) return
        detailOpen = !detailOpen
        statusDetail.visibility = if (detailOpen) View.VISIBLE else View.GONE
        statusChevron.rotation = if (detailOpen) 90f else 0f
        scrollDown()
    }

    /** Ubah ikon + teks status. Aman dipanggil dari thread mana pun. */
    private fun setStatus(kind: Int, title: String, detail: String = "") {
        curKind = kind
        curTitle = title
        curDetail = detail
        runOnUiThread {
            emptyState.visibility = View.GONE
            if (kind != leadKind) {
                statusLead.removeAllViews()
                if (kind == KIND_DOTS) {
                    statusLead.addView(DotsView(this), FrameLayout.LayoutParams(MATCH, MATCH))
                } else {
                    statusLead.addView(
                        IconView(this, kind, col("#7A7A75")),
                        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL)
                    )
                }
                leadKind = kind
            }
            statusTitle.text = title
            statusDetail.text = detail
            statusChevron.visibility = if (detail.isEmpty()) View.INVISIBLE else View.VISIBLE
            statusDetail.visibility = if (detail.isNotEmpty() && detailOpen) View.VISIBLE else View.GONE
            if (statusRoot.parent == null) chatBox.addView(statusRoot, LinearLayout.LayoutParams(MATCH, WRAP))
            scrollDown()
        }
    }

    private fun hideStatus() = runOnUiThread {
        (statusRoot.parent as? ViewGroup)?.removeView(statusRoot)
        detailOpen = false
        statusChevron.rotation = 0f
        leadKind = -2
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------ Menu & kontrol

    private fun updateModelLabel() {
        val n = if (preset(prov).fmt == "cli") "CLI" else cfgModel().ifBlank { prov }
        modelLabel.text = n + "  ▾"
        modelChip?.text = n
    }

    private fun pickModel() {
        if (preset(prov).fmt == "cli") { showSettings(); return }
        if (needsKey() && cfgKey().isEmpty()) { showSettings(); return }
        toast("Memuat daftar model...")
        Thread {
            val list = try {
                fetchModels()
            } catch (e: Exception) {
                runOnUiThread { toast("Gagal memuat daftar: " + (e.message ?: "").take(80)) }
                emptyList<String>()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    typeModel()
                } else {
                    val c = loadProbed()
                    if (c != null) showProbed(c.first, c.second, list) else showModelList(list)
                }
            }
        }.start()
    }

    private fun showModelList(list: List<String>) {
        val shown = list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih model ($prov)")
            .setSingleChoiceItems(shown, shown.indexOf(cfgModel())) { d, which ->
                prefs.edit().putString("p_${prov}_model", shown[which]).apply()
                updateModelLabel()
                d.dismiss()
            }
            .setPositiveButton("Uji model") { _, _ -> probeAll(list) }
            .setNeutralButton("Ketik manual") { _, _ -> typeModel() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private val skipWords = listOf(
        "image", "tts", "embed", "computer-use", "deep-research", "imagen", "veo", "live", "audio",
        "robotics", "aqa", "whisper", "dall-e", "moderation", "davinci", "babbage", "transcribe", "realtime"
    )

    private fun loadProbed(): Pair<List<String>, List<String>>? {
        val raw = pvOf(prov, "probe")
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            if (o.optInt("h") != cfgKey().hashCode()) return null
            val g = o.optJSONArray("g") ?: JSONArray()
            val b = o.optJSONArray("b") ?: JSONArray()
            val gl = (0 until g.length()).map { g.getString(it) }
            val bl = (0 until b.length()).map { b.getString(it) }
            if (gl.isEmpty() && bl.isEmpty()) null else Pair(gl, bl)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveProbed(good: List<String>, busy: List<String>) {
        val o = JSONObject().put("h", cfgKey().hashCode()).put("g", JSONArray(good)).put("b", JSONArray(busy))
        prefs.edit().putString("p_${prov}_probe", o.toString()).apply()
    }

    private fun postStatus(url: String, headers: Map<String, String>, body: JSONObject): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        try {
            val st = if (code >= 400) conn.errorStream else conn.inputStream
            st?.use { it.readBytes() }
        } catch (e: Exception) {
        }
        return code
    }

    /** Uji satu model dengan permintaan kecil + definisi tool. Mengembalikan kode HTTP (-1 jika gagal). */
    private fun probe(m: String): Int {
        return try {
            val key = cfgKey()
            val base = cfgBase().trimEnd('/')
            val fmt = preset(prov).fmt
            val headers = HashMap<String, String>()
            val url: String
            val body: JSONObject
            val userMsg = JSONObject().put("role", "user").put("content", "hi")
            if (fmt == "gemini") {
                url = "$base/models/$m:generateContent"
                val part = JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", "hi")))
                body = JSONObject().put("contents", JSONArray().put(part)).put("tools", tools)
                headers["x-goog-api-key"] = key
            } else if (fmt == "anthropic") {
                url = "$base/messages"
                val tl = JSONArray()
                for (d in declList()) {
                    tl.put(
                        JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
                            .put("input_schema", d.getJSONObject("parameters"))
                    )
                }
                body = JSONObject().put("model", m).put("max_tokens", 16)
                    .put("messages", JSONArray().put(userMsg)).put("tools", tl)
                headers["x-api-key"] = key
                headers["anthropic-version"] = "2023-06-01"
            } else {
                url = "$base/chat/completions"
                val tl = JSONArray()
                for (d in declList()) tl.put(JSONObject().put("type", "function").put("function", d))
                body = JSONObject().put("model", m).put("messages", JSONArray().put(userMsg)).put("tools", tl)
                if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
            }
            postStatus(url, headers, body)
        } catch (e: Exception) {
            -1
        }
    }

    private fun probeAll(list: List<String>) {
        val cand = list.filter { n -> skipWords.none { w -> n.contains(w, true) } }.take(30)
        if (cand.isEmpty()) { toast("Tidak ada model yang cocok untuk diuji"); return }
        toast("Menguji ${cand.size} model, tunggu sekitar 1 menit...")
        Thread {
            val pool = Executors.newFixedThreadPool(5)
            val res = try {
                pool.invokeAll(cand.map { m -> Callable { Pair(m, probe(m)) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }
            val good = res.filter { it.second in 200..299 }.map { it.first }
            val busy = res.filter { it.second == 429 || it.second in 500..599 }.map { it.first }
            runOnUiThread {
                if (good.isEmpty() && busy.isEmpty()) {
                    toast("Tidak ada model yang lolos uji. Cek API key atau langganan.")
                    showModelList(list)
                } else {
                    saveProbed(good, busy)
                    showProbed(good, busy, list)
                }
            }
        }.start()
    }

    private fun showProbed(good: List<String>, busy: List<String>, all: List<String>) {
        val names = good + busy
        val labels = (good.map { "✓ $it" } + busy.map { "⏳ $it" }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Model yang bisa dipakai ($prov)")
            .setSingleChoiceItems(labels, names.indexOf(cfgModel())) { d, which ->
                prefs.edit().putString("p_${prov}_model", names[which]).apply()
                updateModelLabel()
                d.dismiss()
            }
            .setPositiveButton("Uji ulang") { _, _ -> probeAll(all) }
            .setNeutralButton("Semua model") { _, _ -> showModelList(all) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun typeModel() {
        val et = EditText(this).apply {
            hint = "Nama model"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(cfgModel())
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(this)
            .setTitle("Model ($prov)")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putString("p_${prov}_model", et.text.toString().trim()).apply()
                updateModelLabel()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        if (code >= 400) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("API $code: ${err.take(200)}")
        }
        val txt = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(txt)
    }

    /** Ambil daftar model langsung dari penyedia yang aktif. */
    private fun fetchModels(): List<String> {
        val key = cfgKey()
        val base = cfgBase().trimEnd('/')
        val out = ArrayList<String>()
        val fmt = preset(prov).fmt
        if (fmt == "gemini") {
            val r = getJson("$base/models?pageSize=1000", mapOf("x-goog-api-key" to key))
            val arr = r.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")
                var ok = methods == null
                if (methods != null) {
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") ok = true
                    }
                }
                if (ok) out.add(m.optString("name").removePrefix("models/"))
            }
        } else {
            val h = HashMap<String, String>()
            val url: String
            if (fmt == "anthropic") {
                h["x-api-key"] = key
                h["anthropic-version"] = "2023-06-01"
                url = "$base/models?limit=1000"
            } else {
                if (key.isNotEmpty()) h["Authorization"] = "Bearer $key"
                url = "$base/models"
            }
            val r = getJson(url, h)
            val arr = r.optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
        }
        return out.filter { it.isNotBlank() }.sorted()
    }



    // ------------------------------------------------ Layar sambutan & aksi cepat

    private fun workDirSet(): Boolean = (prefs.getString("workdir", "") ?: "").trim().isNotEmpty()

    private fun shortPath(p: String, max: Int = 34): String =
        if (p.length <= max) p else "…" + p.takeLast(max - 1)

    private fun refreshWelcomeFolder() {
        val chip = welcomeFolder ?: return
        val w = (prefs.getString("workdir", "") ?: "").trim()
        if (w.isEmpty()) {
            chip.text = "Langkah 1: ketuk di sini untuk memilih folder kerja"
            chip.setTextColor(col("#B45309"))
            chip.background = GradientDrawable().apply { setColor(col("#FFF1E0")); cornerRadius = dp(14).toFloat() }
        } else {
            chip.text = "Folder kerja · " + shortPath(w) + "  (ketuk untuk ganti)"
            chip.setTextColor(col("#0B6FD2"))
            chip.background = GradientDrawable().apply { setColor(col("#E6F1FD")); cornerRadius = dp(14).toFloat() }
        }
    }

    private fun quickAction(prompt: String, sendNow: Boolean) {
        if (busy) { toast("Hentikan proses dulu"); return }
        if (!workDirSet()) {
            toast("Pilih folder kerja dulu")
            pickWorkDir()
            return
        }
        input.setText(prompt)
        input.setSelection(input.text.length)
        if (sendNow) {
            send()
        } else {
            input.requestFocus()
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(input, 0)
            } catch (_: Exception) {}
        }
    }

    private fun buildWelcome(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "Halo! Mau mengerjakan apa?"
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(col("#111111"))
        })
        box.addView(TextView(this).apply {
            text = "Pilih aksi cepat di bawah, atau ketik perintahmu sendiri di kolom bawah."
            textSize = 14f
            setTextColor(col("#5F6B7A"))
            setPadding(0, dp(6), 0, dp(14))
        })
        val chip = TextView(this).apply {
            textSize = 14f
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = "Pilih atau ganti folder kerja"
            setOnClickListener { pickWorkDir() }
        }
        welcomeFolder = chip
        refreshWelcomeFolder()
        box.addView(chip, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        data class Quick(val title: String, val desc: String, val prompt: String, val sendNow: Boolean)
        val actions = listOf(
            Quick("Lihat struktur proyek", "Tampilkan folder dan file utama", "Tampilkan struktur folder dan file utama di folder kerja.", true),
            Quick("Audit proyek", "Periksa kode dan konfigurasi, lalu ringkas masalahnya",
                "Lakukan audit proyek di folder kerja: periksa struktur, konfigurasi build, dan masalah umum. Ringkas hasilnya.", true),
            Quick("Build APK", "Bangun proyek; error dibaca dan diperbaiki", 
                "Build proyek di folder kerja dengan ./gradlew assembleDebug. Kalau gagal, baca error-nya lalu perbaiki kodenya.", false),
            Quick("Tambah fitur baru", "Ketik fitur yang diinginkan setelah ini", "Di folder kerja, tambahkan fitur: ", false)
        )
        for (a in actions) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(64)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
                foreground = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(28, 0, 0, 0)), null,
                    GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(16).toFloat() }
                )
                softShadow(this, dp(2).toFloat(), 38)
                contentDescription = a.title + ". " + a.desc
                setOnClickListener { quickAction(a.prompt, a.sendNow) }
            }
            card.addView(TextView(this).apply {
                text = a.title
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(col("#111111"))
            })
            card.addView(TextView(this).apply {
                text = a.desc
                textSize = 13f
                setTextColor(col("#5F6B7A"))
            })
            box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ------------------------------------------------ Pencatat crash

    private fun crashFile() = File(filesDir, "last_crash.txt")

    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                crashFile().writeText("Thread: ${t.name}\n$sw")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun showLastCrashIfAny() {
        val f = crashFile()
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (text.isBlank()) return
        val tv = TextView(this).apply {
            this.text = text.take(3000)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Aplikasi sebelumnya berhenti")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Salin") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                toast("Log tersalin")
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showTemplates(anchor: View) {
        val items = listOf(
            "Build APK" to "Build proyek di folder kerja dengan ./gradlew assembleDebug. Kalau gagal, baca error-nya lalu perbaiki kodenya.",
            "Lihat struktur proyek" to "Tampilkan struktur folder dan file utama di folder kerja.",
            "Tambah fitur baru" to "Di folder kerja, tambahkan fitur: "
        )
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 99, -1, "Lampirkan file")
        items.forEachIndexed { i, item -> m.menu.add(0, i, i, item.first) }
        m.setOnMenuItemClickListener { mi ->
            if (mi.itemId == 99) {
                pickFile()
            } else {
                input.setText(items[mi.itemId].second)
                input.setSelection(input.text.length)
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------ Pengaturan (lembar bawah modern)

    private fun fieldBg(focus: Boolean) = GradientDrawable().apply {
        setColor(col("#F3F6FA"))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(2), col(if (focus) BLUE else "#F3F6FA"))
    }

    /** Kotak isian berlatar lembut; garis biru muncul saat fokus. [trailing] = tombol kecil di kanan. */
    private fun inputBox(e: EditText, trailing: View? = null): LinearLayout {
        e.apply {
            background = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(col("#111111"))
            setHintTextColor(col("#9AA3AF"))
            includeFontPadding = false
            setPadding(0, dp(14), 0, dp(14))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBg(false)
            setPadding(dp(14), 0, dp(10), 0)
            addView(e, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        e.setOnFocusChangeListener { _, f -> box.background = fieldBg(f) }
        return box
    }

    private fun sectionTitle(t: String, top: Int = 22) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(col("#5B6573"))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top); bottomMargin = dp(8) }
    }

    private fun helperText(t: String) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(col("#7A8594"))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    private fun switchRow(title: String, desc: String, on: Boolean): Pair<LinearLayout, Switch> {
        val sw = Switch(this).apply {
            isChecked = on
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(col(BLUE), col("#C9D1DC"))
            )
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(col("#111111"))
            })
            addView(TextView(context).apply {
                text = desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(col("#7A8594"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(col("#F3F6FA")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(12), dp(12))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }

    private fun showSettings() {
        val names = presets.map { it.name }
        var cur = prov
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // --- Penyedia AI
        val provValue = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(col("#111111"))
            maxLines = 1
        }
        val chevronDown = IconView(this, IconView.CHEVRON, col("#5B6573")).apply { rotation = 90f }
        val provBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fieldBg(false)
            setPadding(dp(14), dp(14), dp(10), dp(14))
            addView(provValue, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chevronDown, LinearLayout.LayoutParams(dp(20), dp(20)))
        }

        val key = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        var keyShown = false
        fun applyKeyVis() {
            key.transformationMethod =
                if (keyShown) null else android.text.method.PasswordTransformationMethod.getInstance()
            key.setSelection(key.text.length)
        }
        val keyToggle = TextView(this).apply {
            text = "Tampilkan"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(col(BLUE))
            setPadding(dp(8), dp(8), dp(6), dp(8))
            setOnClickListener {
                keyShown = !keyShown
                text = if (keyShown) "Sembunyikan" else "Tampilkan"
                applyKeyVis()
            }
        }
        val keyBox = inputBox(key, keyToggle)

        val mdl = EditText(this).apply {
            hint = "Nama model"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val mdlBox = inputBox(mdl)

        // --- Lanjutan: alamat API / perintah CLI (tersembunyi bila tidak perlu diubah)
        val base = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val baseLabel = helperText("Alamat API")
        val advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            addView(baseLabel)
            addView(inputBox(base))
        }
        val advChevron = IconView(this, IconView.CHEVRON, col("#5B6573"))
        val advToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(4))
            addView(TextView(context).apply {
                text = "Lanjutan (alamat API)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(col(BLUE))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(advChevron, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        fun setAdv(open: Boolean) {
            advBox.visibility = if (open) View.VISIBLE else View.GONE
            advChevron.rotation = if (open) 90f else 0f
        }
        advToggle.setOnClickListener { setAdv(advBox.visibility != View.VISIBLE) }

        fun load(n: String) {
            val p = preset(n)
            val isCli = p.fmt == "cli"
            provValue.text = n
            baseLabel.text = if (isCli) "Perintah CLI — pakai {prompt} sebagai tempat pesan" else "Alamat API (base URL)"
            base.setText(pvOf(n, "base").ifEmpty { p.base })
            val oldKey = if (n == "Gemini") (prefs.getString("key", "") ?: "") else ""
            key.setText(pvOf(n, "key").ifEmpty { oldKey })
            key.hint = if (n == "Ollama lokal") "API key (tidak perlu untuk lokal)" else "API key"
            applyKeyVis()
            mdl.setText(pvOf(n, "model").ifEmpty { p.model })
            keyBox.visibility = if (isCli) View.GONE else View.VISIBLE
            mdlBox.visibility = if (isCli) View.GONE else View.VISIBLE
            advToggle.visibility = if (isCli) View.GONE else View.VISIBLE
            if (isCli || base.text.isNullOrBlank()) setAdv(true)
        }
        provBox.setOnClickListener {
            val m = PopupMenu(this, provBox)
            names.forEachIndexed { i, nm -> m.menu.add(0, i, i, nm) }
            m.setOnMenuItemClickListener { mi -> cur = names[mi.itemId]; load(cur); true }
            m.show()
        }

        // --- Folder kerja
        val work = EditText(this).apply {
            hint = "~/proyek atau /sdcard/proyek"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(prefs.getString("workdir", "") ?: "")
        }
        workDirField = work
        val pickDir = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(col("#E6F1FD")); cornerRadius = dp(14).toFloat() }
            addView(IconView(context, IconView.FOLDER, col(BLUE)), FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { pickWorkDir() }
        }
        val workRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(inputBox(work), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(pickDir, LinearLayout.LayoutParams(dp(50), MATCH).apply { marginStart = dp(8) })
        }

        // --- Izin
        val (fullRow, full) = switchRow(
            "Mode otomatis penuh",
            "Jalankan semua perintah tanpa bertanya. Tombol berhenti tetap aktif.",
            prefs.getBoolean("fullAuto", true)
        )
        val (autoRow, auto) = switchRow(
            "Setujui perintah baca saja",
            "ls, cat, grep, find, pwd otomatis diizinkan. Ubah, hapus, dan build tetap minta izin.",
            prefs.getBoolean("autoRead", true)
        )

        // --- Instruksi tambahan
        val extra = EditText(this).apply {
            hint = "Contoh: Selalu jawab singkat dalam bahasa Indonesia"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setText(prefs.getString("extra", ""))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(16))
            addView(sectionTitle("Model AI", 8))
            addView(provBox)
            addView(keyBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(mdlBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(advToggle)
            addView(advBox)
            addView(sectionTitle("Folder kerja"))
            addView(helperText("Tempat AI menjalankan perintah, jadi tidak perlu disebut di setiap pesan."))
            addView(workRow)
            addView(sectionTitle("Izin"))
            addView(fullRow)
            addView(autoRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            addView(sectionTitle("Instruksi tambahan (opsional)"))
            addView(inputBox(extra))
        }

        val cancel = TextView(this).apply {
            text = "Batal"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(col("#5B6573"))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { dlg.dismiss() }
        }
        val save = TextView(this).apply {
            text = "Simpan"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(col(BLUE)); cornerRadius = dp(100).toFloat() }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnClickListener {
                prov = cur
                prefs.edit()
                    .putString("prov", cur)
                    .putString("p_${cur}_base", base.text.toString().trim())
                    .putString("p_${cur}_key", KeyVault.enc(key.text.toString().trim()))
                    .putString("p_${cur}_model", mdl.text.toString().trim())
                    .putString("extra", extra.text.toString().trim())
                    .putString("workdir", work.text.toString().trim())
                    .putBoolean("autoRead", auto.isChecked)
                    .putBoolean("fullAuto", full.isChecked)
                    .apply()
                updateModelLabel()
                updateFolderHint()
                dlg.dismiss()
            }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), dp(10), dp(20), dp(14))
            addView(cancel)
            addView(save)
        }

        val handle = View(this).apply {
            background = GradientDrawable().apply { setColor(col("#D5DBE3")); cornerRadius = dp(4).toFloat() }
        }
        val r = dp(28).toFloat()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(handle, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(10)
            })
            addView(TextView(context).apply {
                text = "Pengaturan"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(col("#111111"))
                setPadding(dp(24), dp(4), dp(24), dp(4))
            })
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = false
                addView(body)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(View(context).apply { setBackgroundColor(col("#EEF1F5")) }, LinearLayout.LayoutParams(MATCH, 1))
            addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        load(cur)
        dlg.setContentView(sheet)
        dlg.setCanceledOnTouchOutside(true)
        dlg.setOnDismissListener { workDirField = null }
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, (resources.displayMetrics.heightPixels * 0.88f).toInt())
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.4f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.setOnShowListener {
            sheet.translationY = resources.displayMetrics.heightPixels * 0.3f
            sheet.alpha = 0f
            sheet.animate().translationY(0f).alpha(1f).setDuration(220).start()
        }
        dlg.show()
    }

    private fun confirmDelete() {
        if (busy) { toast("Hentikan proses dulu"); return }
        if (hist.isEmpty() && chatBox.childCount == 0) { toast("Belum ada percakapan"); return }
        AlertDialog.Builder(this)
            .setMessage("Hapus percakapan ini?")
            .setPositiveButton("Hapus") { _, _ ->
                if (chatId.isNotBlank()) store.delete(chatId)
                freshChat()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun newChat() {
        if (busy) { toast("Hentikan proses dulu"); return }
        saveChat()
        freshChat()
    }

    private fun freshChat() {
        chatId = ""
        chatTitle = ""
        chatApk = ""
        prefs.edit().remove("active_chat").apply()
        hist.clear()
        cancelTyping()
        chatBox.removeAllViews()
        emptyState.visibility = View.VISIBLE
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
        try {
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            toast("Input suara tidak tersedia")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try { importFile(uri) } catch (e: Throwable) { toast("Gagal melampirkan: " + (e.message ?: "").take(80)) }
            }
        }
        if (requestCode == 79 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) applyWorkDirUri(uri)
        }
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!r.isNullOrEmpty()) {
                input.setText(r[0])
                input.setSelection(input.text.length)
            }
        }
    }

    // ------------------------------------------------ Lampirkan file

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(i, 78)
        } catch (e: Exception) {
            toast("Pemilih file tidak tersedia")
        }
    }

    private fun displayName(uri: Uri): String {
        var n = "file"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val v = c.getString(0)
                if (!v.isNullOrBlank()) n = v
            }
        }
        return n.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    }

    /** Salin file pilihan ke Download/KitaAgent agar bisa dibaca dari Termux. Mengembalikan path-nya. */
    private fun copyToDownload(uri: Uri, name: String): String {
        try {
            val src = contentResolver.openInputStream(uri) ?: throw RuntimeException("File tidak bisa dibaca")
            return src.use { ins ->
                val dir = File(getExternalFilesDir(null) ?: filesDir, "KitaAgent")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { o -> ins.copyTo(o) }
                f.absolutePath
            }
        } catch (e: Exception) {
            val cacheF = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                cacheF.outputStream().use { o -> ins.copyTo(o) }
            } ?: throw RuntimeException("Gagal membaca stream file")
            cacheF.absolutePath
        }
    }

    private fun importFile(uri: Uri) {
        toast("Menyalin file...")
        Thread {
            try {
                val path = copyToDownload(uri, displayName(uri))
                runOnUiThread {
                    val line = "File terlampir di $path. "
                    val cur = input.text.toString()
                    input.setText(if (cur.isBlank()) line else cur.trimEnd() + "\n" + line)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                }
            } catch (e: Throwable) {
                runOnUiThread { toast("Gagal melampirkan: " + (e.message ?: "").take(80)) }
            }
        }.start()
    }

    private fun onAction() {
        if (busy) { requestStop(); return }
        if (input.text.isNullOrBlank()) voice() else send()
    }

    private fun insertBeforeStatus(v: View, lp: LinearLayout.LayoutParams) {
        val idx = if (statusRoot.parent === chatBox) chatBox.indexOfChild(statusRoot) else -1
        if (idx >= 0) chatBox.addView(v, idx, lp) else chatBox.addView(v, lp)
    }

    private fun addMsg(kind: Int, text: String) = runOnUiThread {
        emptyState.visibility = View.GONE
        if (kind == USER) {
            finishTyping()
            val tv = TextView(this).apply {
                this.text = text
                setTextIsSelectable(true)
                textSize = 16f
                setTextColor(col("#111111"))
                background = GradientDrawable().apply { setColor(col("#E6F1FD")); cornerRadius = dp(20).toFloat() }
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(10)
                gravity = Gravity.END
                marginStart = dp(56)
            }
            insertBeforeStatus(tv, lp)
        } else if (restoring) {
            fillMarkdown(newAiBox(), text)
        } else {
            finishTyping()
            startTyping(TypeJob(newAiBox(), text, 0, true))
        }
        scrollDown()
    }

    // ------------------------------------------------ Balasan AI: streaming + markdown

    private var streamBox: LinearLayout? = null

    private fun newAiBox(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        insertBeforeStatus(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        return box
    }

    // ---- Efek mengetik: teks balasan muncul bertahap, tanpa kursor.

    private class TypeJob(val box: LinearLayout, var target: String, var shown: Int, var ended: Boolean)

    private var typeJob: TypeJob? = null
    private var restoring = false

    private val typeTick = object : Runnable {
        override fun run() {
            val j = typeJob ?: return
            if (j.shown > j.target.length) j.shown = j.target.length
            val backlog = j.target.length - j.shown
            if (backlog > 0) {
                // Tertinggal banyak -> mengejar lebih cepat; sedikit -> tetap tenang dan halus.
                val step = (backlog / 18).coerceIn(2, 60)
                var n = minOf(j.target.length, j.shown + step)
                if (n < j.target.length && Character.isHighSurrogate(j.target[n - 1])) n++
                j.shown = n
                renderTyped(j)
            }
            if (j.shown >= j.target.length && j.ended) {
                typeJob = null
                return
            }
            scroll.postDelayed(this, 30)
        }
    }

    private fun startTyping(j: TypeJob) {
        typeJob = j
        scroll.removeCallbacks(typeTick)
        scroll.post(typeTick)
    }

    private fun nearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return c.bottom - (scroll.height + scroll.scrollY) < dp(140)
    }

    private fun renderTyped(j: TypeJob) {
        val follow = nearBottom()
        val done = j.shown >= j.target.length
        val txt = if (done) j.target else partialMd(j.target.substring(0, j.shown))
        fillMarkdown(j.box, txt)
        j.box.tag = j.target
        if (follow) scrollDown()
    }

    /** Sembunyikan penanda markdown yang belum lengkap di baris terakhir (mis. "**" atau "`" menggantung). */
    private fun partialMd(t: String): String {
        if ((t.split("```").size - 1) % 2 == 1) return t
        val nl = t.lastIndexOf('\n')
        val head = if (nl >= 0) t.substring(0, nl + 1) else ""
        var line = if (nl >= 0) t.substring(nl + 1) else t
        if ((line.split("**").size - 1) % 2 == 1) line = line.substring(0, line.lastIndexOf("**"))
        if ((line.split("`").size - 1) % 2 == 1) line = line.substring(0, line.lastIndexOf('`'))
        line = line.trimEnd('*')
        return head + line
    }

    /** Selesaikan seketika animasi yang sedang berjalan (dipakai sebelum pesan baru atau saat dihentikan). */
    private fun finishTyping() {
        val j = typeJob ?: return
        scroll.removeCallbacks(typeTick)
        typeJob = null
        fillMarkdown(j.box, j.target)
    }

    private fun cancelTyping() {
        scroll.removeCallbacks(typeTick)
        typeJob = null
    }

    /** Dipanggil saat teks balasan mengalir masuk; teks ditampilkan bertahap oleh animasi mengetik. */
    private fun streamUpdate(text: String) = runOnUiThread {
        emptyState.visibility = View.GONE
        val j = typeJob
        if (j == null || j.ended) {
            finishTyping()
            val box = newAiBox()
            streamBox = box
            startTyping(TypeJob(box, text, 0, false))
        } else {
            j.target = text
        }
    }

    private fun streamEnd() = runOnUiThread {
        typeJob?.ended = true
        streamBox = null
    }

    private val fenceRe = Regex("```([^\\n`]*)\\n(.*?)(```|\\z)", RegexOption.DOT_MATCHES_ALL)
    private val headRe = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val inlineRe = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")

    private fun fillMarkdown(box: LinearLayout, text: String) {
        box.tag = text
        val content = ensureAiChrome(box)
        content.removeAllViews()
        var last = 0
        for (m in fenceRe.findAll(text)) {
            addTextBlock(content, text.substring(last, m.range.first))
            content.addView(
                codeBlock(m.groupValues[1], m.groupValues[2].trimEnd('\n')),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) }
            )
            last = m.range.last + 1
        }
        if (last < text.length) addTextBlock(content, text.substring(last))
    }

    private fun ensureAiChrome(box: LinearLayout): LinearLayout {
        if (box.childCount >= 2 && box.getChildAt(0).tag == "copybar") {
            return box.getChildAt(1) as LinearLayout
        }
        box.removeAllViews()
        val copy = TextView(this).apply {
            text = "Salin"
            textSize = 13f
            setTextColor(col("#0B72D9"))
            setPadding(dp(8), dp(2), dp(4), dp(6))
            setOnClickListener {
                val raw = (box.tag as? String).orEmpty()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("balasan", raw))
                text = "Tersalin"
                postDelayed({ text = "Salin" }, 1500)
            }
        }
        val bar = LinearLayout(this).apply {
            tag = "copybar"
            gravity = Gravity.END
            addView(copy)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "md"
        }
        box.addView(bar)
        box.addView(content)
        return content
    }

    private fun addTextBlock(box: LinearLayout, seg: String) {
        val t = seg.trim('\n')
        if (t.isBlank()) return
        val tv = TextView(this).apply {
            text = mdToSpannable(t)
            setTextIsSelectable(true)
            textSize = 16f
            setTextColor(col("#1F1F1F"))
            setLineSpacing(0f, 1.15f)
        }
        box.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** Markdown ringan: judul (#), daftar (-, *), **tebal**, *miring*, dan `kode inline`. */
    private fun mdToSpannable(src: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val lines = src.split("\n")
        for ((i, raw) in lines.withIndex()) {
            val start = out.length
            val h = headRe.find(raw)
            val b = bulletRe.find(raw)
            if (h != null) {
                out.append(inline(h.groupValues[2]))
                val size = when (h.groupValues[1].length) { 1 -> 1.35f; 2 -> 1.2f; else -> 1.08f }
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (b != null) {
                out.append("    ".repeat(b.groupValues[1].length / 2)).append("•  ").append(inline(b.groupValues[2]))
            } else {
                out.append(inline(raw))
            }
            if (i < lines.size - 1) out.append("\n")
        }
        return out
    }

    private fun inline(src: String): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        var last = 0
        for (m in inlineRe.findAll(src)) {
            out.append(src, last, m.range.first)
            val st = out.length
            if (m.groups[1] != null) {
                out.append(m.groupValues[1])
                out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(col("#E3E8EF")), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.92f), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (m.groups[2] != null) {
                out.append(m.groupValues[2])
                out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(m.groupValues[3])
                out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        out.append(src, last, src.length)
        return out
    }

    /** Kotak kode: label bahasa, tombol Salin, teks monospace yang bisa digeser ke samping. */
    private fun codeBlock(lang: String, code: String): View {
        val label = TextView(this).apply {
            text = if (lang.isBlank()) "kode" else lang.trim()
            textSize = 12f
            setTextColor(col("#6B6B66"))
        }
        val copy = TextView(this).apply {
            text = "Salin"
            textSize = 13f
            setTextColor(col("#0B72D9"))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val cm = this@MainActivity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("kode", code))
                text = "Tersalin"
                postDelayed({ text = "Salin" }, 1500)
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), 0)
            addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(copy)
        }
        val codeTv = TextView(this).apply {
            text = code
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(col("#24292F"))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(codeTv)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(col("#F6F8FA"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), col("#D0D7DE"))
            }
            addView(header)
            addView(hs)
        }
    }

    // ---------------------------------------------------------------- Agent

    private fun send() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || busy) return
        val key = cfgKey()
        if (needsKey() && key.isEmpty()) { showSettings(); return }
        if (preset(prov).fmt != "cli" && cfgModel().isBlank()) {
            toast("Isi nama model dulu")
            pickModel()
            return
        }
        input.setText("")
        refreshAction()
        stopRequested = false
        busy = true
        refreshAction()
        feedGroup = null
        addMsg(USER, msg)
        setStatus(KIND_DOTS, "Kita Agent sedang berpikir")
        worker = Thread {
            try {
                agentLoop(msg, key)
            } catch (e: StoppedException) {
                noteStopped()
            } catch (e: Exception) {
                if (stopRequested) noteStopped()
                else addMsg(AI, "Error: ${e.message ?: "gangguan tidak diketahui"}")
            } finally {
                hideStatus()
                saveChat()
                if (!inForeground && !stopRequested) notifyDone(lastReply())
                busy = false
                stopRequested = false
                activeConn = null
                runOnUiThread { refreshAction() }
            }
        }
        worker?.start()
    }

    private fun requestStop() {
        if (!busy) return
        stopRequested = true
        try { activeConn?.disconnect() } catch (_: Exception) {}
        approvalQueue.get()?.offer(false)
        runOnUiThread {
            try { approvalDialog?.dismiss() } catch (_: Exception) {}
            approvalDialog = null
        }
        termuxLatch.get()?.countDown()
        killRunningTermux()
        worker?.interrupt()
        setStatus(IconView.STOP, "Menghentikan Kita Agent")
    }

    private var feedGroup: ActivityUi.FeedGroup? = null

    private fun addToFeed(c: Call, out: String) = runOnUiThread {
        emptyState.visibility = View.GONE
        val g = feedGroup ?: ActivityUi.FeedGroup(this, ::dp).also {
            feedGroup = it
            insertBeforeStatus(it.root, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
        }
        g.addStep(c, out)
        scrollDown()
    }

    private fun finishFeed() = runOnUiThread {
        feedGroup?.finish()
        feedGroup = null
    }

    private fun noteStopped() = runOnUiThread {
        finishTyping()
        streamEnd()
        val tv = TextView(this).apply {
            text = "Dihentikan."
            textSize = 14f
            setTextColor(col("#8A4B2F"))
            setPadding(0, dp(6), 0, dp(4))
        }
        insertBeforeStatus(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        scrollDown()
    }

    private fun statusFor(name: String, args: JSONObject): String {
        if (name == "write_file") return "Kita Agent sedang menyusun kode"
        val c = args.optString("command")
        return when {
            c.contains("gradlew") || c.contains("gradle ") -> "Kita Agent sedang membangun proyek"
            CommandPolicy.isReadOnly(c) -> "Kita Agent sedang membaca"
            else -> "Kita Agent sedang mengerjakan perintah"
        }
    }

    private fun kindFor(name: String, args: JSONObject): Int {
        if (name == "write_file") return IconView.CODE
        val c = args.optString("command")
        return if (c.contains("gradlew") || c.contains("gradle ")) IconView.CLOCK else IconView.TERMINAL
    }

    private fun detailFor(name: String, args: JSONObject): String =
        if (name == "write_file") {
            args.optString("path") + "\n\n" + args.optString("content").take(1500)
        } else {
            "$ " + args.optString("command")
        }

    private fun agentLoop(userMsg: String, key: String) {
        if (preset(prov).fmt == "cli") {
            ensureChat()
            synchronized(storeLock) { hist.add(Hist(0, userMsg)) }
            saveChat()
            cliChat(userMsg)
            saveChat()
            return
        }
        ensureChat()
        synchronized(storeLock) { hist.add(Hist(0, userMsg)) }
        saveChat()
        var first = true
        while (!stopRequested) {
            setStatus(KIND_DOTS, if (first) "Kita Agent sedang berpikir" else "Kita Agent sedang menganalisa hasil")
            first = false
            var wrote = false
            val turn = withRetry {
                callModel(
                    key,
                    onText = { t ->
                        if (!wrote) {
                            wrote = true
                            setStatus(KIND_DOTS, "Kita Agent sedang menulis jawaban")
                        }
                        streamUpdate(t)
                    },
                    onFlush = { streamEnd() }
                )
            }
            if (stopRequested) {
                if (turn != null) synchronized(storeLock) { hist.add(turn) }
                finishFeed()
                noteStopped()
                saveChat()
                return
            }
            if (turn == null) {
                finishFeed()
                addMsg(AI, "(tidak ada jawaban)")
                saveChat()
                return
            }
            synchronized(storeLock) { hist.add(turn) }
            saveChat()
            if (turn.calls.isEmpty()) {
                finishFeed()
                return
            }
            val results = ArrayList<Pair<Call, String>>()
            for (c in turn.calls) {
                if (stopRequested) {
                    finishFeed()
                    noteStopped()
                    saveChat()
                    return
                }
                val title = c.args.optString("deskripsi").ifBlank { statusFor(c.name, c.args) }
                val detail = detailFor(c.name, c.args)
                val readOnly = c.name == "run_command" && autoRead() && CommandPolicy.isReadOnly(c.args.optString("command"))
                setStatus(
                    kindFor(c.name, c.args),
                    title,
                    if (readOnly) detail + "\n\n(disetujui otomatis — hanya membaca)" else detail
                )
                val out = runTool(c.name, c.args, readOnly)
                results.add(Pair(c, out))
                addToFeed(c, out)
                if (c.name == "run_command") maybeOfferApk(c.args.optString("command"), out)
                if (stopRequested || out.startsWith("Dihentikan")) {
                    synchronized(storeLock) { hist.add(Hist(2, results = results)) }
                    finishFeed()
                    noteStopped()
                    saveChat()
                    return
                }
            }
            synchronized(storeLock) { hist.add(Hist(2, results = results)) }
            saveChat()
        }
        finishFeed()
        noteStopped()
    }

    private fun <T> withRetry(block: () -> T): T {
        var delay = 2000L
        var retries = 0
        while (true) {
            if (stopRequested) throw StoppedException()
            try {
                return block()
            } catch (e: StoppedException) {
                throw e
            } catch (e: TransientException) {
                if (retries >= 3) {
                    throw RuntimeException("Gagal setelah 3 percobaan ulang. ${e.message}")
                }
                retries++
                val sec = (delay / 1000).toInt()
                setStatus(
                    KIND_DOTS,
                    "Kita Agent mencoba lagi ($retries/3)",
                    "Menunggu $sec detik karena error sementara.\n${e.message}"
                )
                sleepBackoff(delay)
                delay = (delay * 2).coerceAtMost(16000L)
            }
        }
    }

    private fun sleepBackoff(ms: Long) {
        var left = ms
        while (left > 0) {
            if (stopRequested) throw StoppedException()
            val step = minOf(left, 200L)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                throw StoppedException()
            }
            left -= step
        }
    }

    private fun isTransientCode(code: Int) =
        code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529

    private fun isTransientIO(e: Throwable): Boolean {
        val n = e.javaClass.name
        if (n.contains("SocketTimeout") || n.contains("ConnectException") || n.contains("UnknownHost") || n.contains("SocketException")) {
            return true
        }
        val m = (e.message ?: "").lowercase()
        return m.contains("timeout") || m.contains("timed out") || m.contains("connection reset") ||
            m.contains("unexpected end") || m.contains("failed to connect") || m.contains("unreachable")
    }

    private fun sysText(): String {
        val shown = (prefs.getString("workdir", "") ?: "").trim()
        val abs = workDirAbs()
        val label = if (shown.isEmpty()) abs else "$shown (absolut: $abs)"
        val base =
            "Kamu Kita Agent, asisten pengembang. Semua perintah berjalan di Termux (Android). " +
            "Direktori kerja saat ini adalah $label. " +
            "Setiap perintah sudah dijalankan dari folder itu secara otomatis, jadi gunakan path relatif. " +
            "Jangan meminta pengguna mengulang lokasi folder dan jangan berasumsi nama folder tertentu. " +
            "Lihat isi file (cat, ls, grep, find, pwd) sebelum mengubahnya. " +
            "Untuk build Android jalankan ./gradlew assembleDebug dari folder kerja. " +
            (if (fullAuto())
                "Pengguna awam dan sepenuhnya mengandalkanmu: kerjakan seluruh tugas sampai tuntas dalam satu permintaan, " +
                "jangan bertanya atau meminta konfirmasi, ambil keputusan terbaik sendiri, perbaiki error sendiri, lalu laporkan hasil akhirnya. "
            else "Jangan mengubah aplikasi ini (paket com.kita.agentx) kecuali pengguna memintanya. ") +
            "File yang dilampirkan pengguna ada di /sdcard/Download/KitaAgent/ dan bisa dibaca dari Termux. " +
            "Setiap memanggil tool, isi 'deskripsi' dengan kalimat singkat tentang langkahmu. " +
            "Jawab dalam bahasa Indonesia, singkat."
        val extra = prefs.getString("extra", "") ?: ""
        return if (extra.isBlank()) base else "$base\nInstruksi tambahan dari pengguna: $extra"
    }

    private fun callModel(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? =
        when (preset(prov).fmt) {
            "openai" -> callOpenAI(key, onText, onFlush)
            "anthropic" -> callAnthropic(key, onText, onFlush)
            else -> callGemini(key, onText, onFlush)
        }

    private fun connectPost(url: String, headers: Map<String, String>, body: String, readMs: Int): HttpURLConnection {
        if (stopRequested) throw StoppedException()
        val conn = URL(url).openConnection() as HttpURLConnection
        activeConn = conn
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = readMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream, application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn
    }

    private fun errorText(conn: HttpURLConnection): String =
        try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }

    private fun throwForStatus(code: Int, err: String): Nothing {
        val msg = "HTTP $code: ${err.take(220)}"
        if (isTransientCode(code)) throw TransientException(msg)
        throw RuntimeException("API $code: ${err.take(300)}")
    }

    private fun declList(): List<JSONObject> {
        val decls = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        return (0 until decls.length()).map { decls.getJSONObject(it) }
    }

    private fun parseArgs(s: String): JSONObject =
        try { JSONObject(if (s.isBlank()) "{}" else s) } catch (_: Exception) { JSONObject() }

    private class ToolAcc(var id: String, var name: String, val args: StringBuilder = StringBuilder())

    private fun callOpenAI(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysText()))
        for (h in hist) {
            if (h.role == 0) {
                msgs.put(JSONObject().put("role", "user").put("content", h.text))
            } else if (h.role == 1) {
                val m = JSONObject().put("role", "assistant")
                m.put("content", if (h.text.isEmpty()) JSONObject.NULL else h.text)
                if (h.calls.isNotEmpty()) {
                    val tc = JSONArray()
                    for (c in h.calls) {
                        tc.put(
                            JSONObject().put("id", c.id).put("type", "function").put(
                                "function",
                                JSONObject().put("name", c.name).put("arguments", c.args.toString())
                            )
                        )
                    }
                    m.put("tool_calls", tc)
                }
                msgs.put(m)
            } else {
                for ((c, o) in h.results) {
                    msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", o))
                }
            }
        }
        val tl = JSONArray()
        for (d in declList()) tl.put(JSONObject().put("type", "function").put("function", d))
        val body = JSONObject().put("model", cfgModel()).put("messages", msgs).put("tools", tl).put("stream", true)
        val headers = HashMap<String, String>()
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val acc = StringBuilder()
        val toolsAcc = HashMap<Int, ToolAcc>()
        var emitted = false
        try {
            val conn = connectPost(cfgBase().trimEnd('/') + "/chat/completions", headers, body.toString(), 300000)
            val code = conn.responseCode
            if (code >= 400) throwForStatus(code, errorText(conn))
            readModelStream(conn, onEvent = { obj ->
                if (obj.has("error") && !obj.has("choices")) {
                    val em = obj.optJSONObject("error")
                    val message = em?.optString("message") ?: obj.toString()
                    val c = em?.optInt("code", 0) ?: 0
                    if (!emitted && isTransientCode(c)) throw TransientException(message.take(200))
                    if (emitted) acc.append("\n\n(Streaming terputus: ${message.take(180)})")
                    else throw RuntimeException(message.take(300))
                    return@readModelStream
                }
                val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@readModelStream
                if (!delta.isNull("content")) {
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        acc.append(piece)
                        emitted = true
                        onText(acc.toString())
                    }
                }
                val tcs = delta.optJSONArray("tool_calls") ?: return@readModelStream
                for (i in 0 until tcs.length()) {
                    val t = tcs.getJSONObject(i)
                    val idx = if (t.has("index")) t.getInt("index") else i
                    val b = toolsAcc.getOrPut(idx) { ToolAcc(t.optString("id", "call$idx"), "") }
                    if (t.optString("id").isNotEmpty()) b.id = t.optString("id")
                    val f = t.optJSONObject("function") ?: continue
                    if (f.optString("name").isNotEmpty()) b.name = f.optString("name")
                    if (f.has("arguments")) b.args.append(f.optString("arguments", ""))
                }
            }, onJson = { res ->
                val msg = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                if (msg != null) {
                    val text = if (msg.isNull("content")) "" else msg.optString("content", "")
                    if (text.isNotBlank()) {
                        acc.append(text)
                        emitted = true
                        onText(acc.toString())
                    }
                    val tcs = msg.optJSONArray("tool_calls")
                    if (tcs != null) {
                        for (i in 0 until tcs.length()) {
                            val t = tcs.getJSONObject(i)
                            val f = t.optJSONObject("function") ?: continue
                            val b = toolsAcc.getOrPut(i) { ToolAcc(t.optString("id", "call$i"), f.optString("name")) }
                            b.id = t.optString("id", b.id)
                            b.name = f.optString("name", b.name)
                            b.args.append(f.optString("arguments", ""))
                        }
                    }
                }
            })
        } catch (e: StoppedException) {
            throw e
        } catch (e: TransientException) {
            if (emitted) {
                acc.append("\n\n(Streaming terputus: ${e.message})")
            } else throw e
        } catch (e: Exception) {
            if (stopRequested) throw StoppedException()
            if (emitted) acc.append("\n\n(Streaming terputus: ${e.message ?: "gangguan jaringan"})")
            else if (isTransientIO(e)) throw TransientException(e.message ?: "gangguan jaringan sementara")
            else throw e
        }
        val text = acc.toString()
        if (text.isNotBlank()) onFlush()
        val calls = ArrayList<Call>()
        for (idx in toolsAcc.keys.sorted()) {
            val b = toolsAcc[idx] ?: continue
            if (b.name.isBlank()) continue
            val id = b.id.ifBlank { "call$idx" }
            calls.add(Call(id, b.name, parseArgs(b.args.toString())))
        }
        if (stopRequested && text.isBlank() && calls.isEmpty()) throw StoppedException()
        if (text.isBlank() && calls.isEmpty()) return null
        return Hist(1, text, calls)
    }

    private fun callAnthropic(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
        val msgs = JSONArray()
        for (h in hist) {
            if (h.role == 0) {
                msgs.put(JSONObject().put("role", "user").put("content", h.text))
            } else if (h.role == 1) {
                val blocks = JSONArray()
                if (h.text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", h.text))
                for (c in h.calls) {
                    blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args))
                }
                msgs.put(JSONObject().put("role", "assistant").put("content", blocks))
            } else {
                val blocks = JSONArray()
                for ((c, o) in h.results) {
                    blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", c.id).put("content", o))
                }
                msgs.put(JSONObject().put("role", "user").put("content", blocks))
            }
        }
        val tl = JSONArray()
        for (d in declList()) {
            tl.put(
                JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
                    .put("input_schema", d.getJSONObject("parameters"))
            )
        }
        val body = JSONObject().put("model", cfgModel()).put("max_tokens", 8192)
            .put("system", sysText()).put("messages", msgs).put("tools", tl).put("stream", true)
        val headers = hashMapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        class Block(val type: String, var id: String, var name: String, val buf: StringBuilder)
        val blocks = HashMap<Int, Block>()
        var emitted = false
        fun joinedText(): String {
            val sb = StringBuilder()
            for (i in blocks.keys.sorted()) {
                val b = blocks[i] ?: continue
                if (b.type == "text") sb.append(b.buf)
            }
            return sb.toString()
        }
        try {
            val conn = connectPost(cfgBase().trimEnd('/') + "/messages", headers, body.toString(), 300000)
            val code = conn.responseCode
            if (code >= 400) throwForStatus(code, errorText(conn))
            readModelStream(conn, onEvent = { obj ->
                when (obj.optString("type")) {
                    "content_block_start" -> {
                        val idx = obj.optInt("index")
                        val cb = obj.optJSONObject("content_block") ?: JSONObject()
                        val b = Block(cb.optString("type", "text"), cb.optString("id"), cb.optString("name"), StringBuilder())
                        if (b.type == "text") b.buf.append(cb.optString("text"))
                        blocks[idx] = b
                    }
                    "content_block_delta" -> {
                        val idx = obj.optInt("index")
                        val b = blocks.getOrPut(idx) { Block("text", "", "", StringBuilder()) }
                        val d = obj.optJSONObject("delta") ?: return@readModelStream
                        when (d.optString("type")) {
                            "text_delta" -> {
                                b.buf.append(d.optString("text"))
                                emitted = true
                                onText(joinedText())
                            }
                            "input_json_delta" -> b.buf.append(d.optString("partial_json"))
                        }
                    }
                    "error" -> {
                        val message = obj.optJSONObject("error")?.optString("message") ?: obj.toString()
                        val low = message.lowercase()
                        val transient = low.contains("429") || low.contains("503") || low.contains("529") || low.contains("overloaded") || low.contains("timeout") || low.contains("rate")
                        if (!emitted && transient) throw TransientException(message.take(200))
                        if (!emitted) throw RuntimeException(message.take(300))
                        val b = blocks.getOrPut(0) { Block("text", "", "", StringBuilder()) }
                        b.buf.append("\n\n(Streaming terputus: ${message.take(180)})")
                        onText(joinedText())
                    }
                }
            }, onJson = { res ->
                val content = res.optJSONArray("content") ?: return@readModelStream
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    val type = b.optString("type")
                    val block = Block(type, b.optString("id"), b.optString("name"), StringBuilder())
                    if (type == "text") block.buf.append(b.optString("text"))
                    else if (type == "tool_use") block.buf.append((b.optJSONObject("input") ?: JSONObject()).toString())
                    blocks[i] = block
                }
                val text = joinedText()
                if (text.isNotBlank()) {
                    emitted = true
                    onText(text)
                }
            })
        } catch (e: StoppedException) {
            throw e
        } catch (e: TransientException) {
            if (!emitted) throw e
        } catch (e: Exception) {
            if (stopRequested) throw StoppedException()
            if (!emitted) {
                if (isTransientIO(e)) throw TransientException(e.message ?: "gangguan jaringan sementara")
                throw e
            }
        }
        val text = joinedText()
        if (text.isNotBlank()) onFlush()
        val calls = ArrayList<Call>()
        for (i in blocks.keys.sorted()) {
            val b = blocks[i] ?: continue
            if (b.type == "tool_use" && b.name.isNotBlank()) {
                calls.add(Call(b.id.ifBlank { "tu$i" }, b.name, parseArgs(b.buf.toString())))
            }
        }
        if (stopRequested && text.isBlank() && calls.isEmpty()) throw StoppedException()
        if (text.isBlank() && calls.isEmpty()) return null
        return Hist(1, text, calls)
    }

    private inline fun readModelStream(
        conn: HttpURLConnection,
        onEvent: (JSONObject) -> Unit,
        onJson: (JSONObject) -> Unit
    ) {
        conn.inputStream.bufferedReader().use { reader ->
            if (!reader.ready()) {
                // ready() can be false before first byte; still read
            }
            reader.mark(80)
            val peek = CharArray(1)
            val n = reader.read(peek)
            if (n <= 0) return
            reader.reset()
            if (peek[0] == '{') {
                val txt = reader.readText()
                val obj = try { JSONObject(txt) } catch (e: Exception) { throw RuntimeException("Jawaban bukan JSON") }
                onJson(obj)
                return
            }
            while (!stopRequested) {
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    if (stopRequested) return
                    throw e
                } ?: return
                if (!line.startsWith("data:")) continue
                val json = line.removePrefix("data:").trim()
                if (json.isEmpty()) continue
                if (json == "[DONE]") return
                val obj = try { JSONObject(json) } catch (_: Exception) { continue }
                onEvent(obj)
            }
        }
    }

    private fun cliChat(userMsg: String) {
        var t = cfgBase().ifBlank { "gemini -p {prompt}" }
        if (!t.contains("{prompt}")) t += " {prompt}"
        val cmd = t.replace("{prompt}", sh(userMsg))
        setStatus(IconView.TERMINAL, "Kita Agent menjalankan CLI", "$ " + cmd.take(300))
        val out = termux(cmd)
        if (stopRequested) {
            noteStopped()
            return
        }
        val body = out.substringAfter("\n", out).trim()
        val text = if (body.isEmpty()) "(CLI tidak mengeluarkan teks)" else body
        addMsg(AI, text)
        synchronized(storeLock) { hist.add(Hist(1, text)) }
    }

    private fun geminiContents(): JSONArray {
        val arr = JSONArray()
        for (h in hist) {
            if (h.role == 0) {
                arr.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", h.text))))
            } else if (h.role == 1) {
                if (h.raw != null) {
                    arr.put(h.raw)
                } else {
                    val parts = JSONArray()
                    if (h.text.isNotEmpty()) parts.put(JSONObject().put("text", h.text))
                    for (c in h.calls) {
                        parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args)))
                    }
                    arr.put(JSONObject().put("role", "model").put("parts", parts))
                }
            } else {
                val parts = JSONArray()
                for ((c, o) in h.results) {
                    parts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", c.name).put("response", JSONObject().put("result", o))
                        )
                    )
                }
                arr.put(JSONObject().put("role", "user").put("parts", parts))
            }
        }
        return arr
    }

    private fun callGemini(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysText()))))
            .put("contents", geminiContents())
            .put("tools", tools)
        val url = cfgBase().trimEnd('/') + "/models/" + cfgModel() + ":streamGenerateContent?alt=sse"
        val parts = JSONArray()
        val buf = StringBuilder()
        var sig: String? = null
        var lastEmit = 0L
        var emitted = false
        fun flushText() {
            if (buf.isEmpty()) return
            onText(buf.toString())
            onFlush()
            val tp = JSONObject().put("text", buf.toString())
            sig?.let { tp.put("thoughtSignature", it) }
            parts.put(tp)
            buf.setLength(0)
            sig = null
        }
        try {
            val conn = connectPost(url, mapOf("x-goog-api-key" to key), body.toString(), 120000)
            val code = conn.responseCode
            if (code >= 400) throwForStatus(code, errorText(conn))
            conn.inputStream.bufferedReader().use { r ->
                while (!stopRequested) {
                    val line = try { r.readLine() } catch (e: Exception) {
                        if (stopRequested) break
                        throw e
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.removePrefix("data:").trim()
                    if (json.isEmpty()) continue
                    val chunk = try { JSONObject(json) } catch (_: Exception) { continue }
                    if (chunk.has("error")) {
                        val message = chunk.optJSONObject("error")?.optString("message") ?: chunk.toString()
                        val c = chunk.optJSONObject("error")?.optInt("code", 0) ?: 0
                        if (!emitted && isTransientCode(c)) throw TransientException(message.take(200))
                        if (emitted) buf.append("\n\n(Streaming terputus: ${message.take(180)})")
                        else throw RuntimeException(message.take(300))
                        break
                    }
                    val cps = chunk.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until cps.length()) {
                        val p = cps.getJSONObject(i)
                        if (p.has("functionCall")) {
                            flushText()
                            parts.put(p)
                        } else if (p.has("text") && !p.optBoolean("thought", false)) {
                            buf.append(p.getString("text"))
                            emitted = true
                            if (p.has("thoughtSignature")) sig = p.getString("thoughtSignature")
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 60) {
                                onText(buf.toString())
                                lastEmit = now
                            }
                        }
                    }
                }
            }
        } catch (e: StoppedException) {
            throw e
        } catch (e: TransientException) {
            if (emitted) buf.append("\n\n(Streaming terputus: ${e.message})")
            else throw e
        } catch (e: Exception) {
            if (stopRequested) {
                // lanjutkan dengan teks yang sudah ada
            } else if (emitted) {
                buf.append("\n\n(Streaming terputus: ${e.message ?: "gangguan jaringan"})")
            } else if (isTransientIO(e)) {
                throw TransientException(e.message ?: "gangguan jaringan sementara")
            } else throw e
        }
        flushText()
        if (stopRequested && parts.length() == 0) throw StoppedException()
        if (parts.length() == 0) return null
        val sb = StringBuilder()
        val calls = ArrayList<Call>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.has("functionCall")) {
                val fc = p.getJSONObject("functionCall")
                calls.add(Call("g$i", fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
            } else if (p.has("text")) {
                sb.append(p.getString("text"))
            }
        }
        return Hist(1, sb.toString(), calls, raw = JSONObject().put("role", "model").put("parts", parts))
    }

    private fun sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun confirm(
        msg: String, risk: Risk? = null, title: String = "", sub: String = "", body: CharSequence = ""
    ): Boolean {
        if (stopRequested) return false
        val pk = curKind
        val pt = curTitle
        val pd = curDetail
        setStatus(IconView.CLOCK, "Menunggu persetujuan Anda", pd)
        val q = LinkedBlockingQueue<Boolean>(1)
        approvalQueue.set(q)
        runOnUiThread {
            if (stopRequested) {
                q.offer(false)
                return@runOnUiThread
            }
            val b = AlertDialog.Builder(this)
            if (risk != null) b.setView(Approval.view(this, ::dp, risk, title, sub, body)) else b.setMessage(msg)
            val d = b
                .setCancelable(false)
                .setPositiveButton("Izinkan") { _, _ -> q.offer(true) }
                .setNegativeButton("Tolak") { _, _ -> q.offer(false) }
                .create()
            approvalDialog = d
            d.show()
        }
        val ok = try {
            q.poll(30, TimeUnit.MINUTES) ?: false
        } catch (_: InterruptedException) {
            false
        }
        approvalQueue.set(null)
        runOnUiThread {
            try { approvalDialog?.dismiss() } catch (_: Exception) {}
            approvalDialog = null
        }
        if (!stopRequested) setStatus(pk, pt, pd)
        return ok && !stopRequested
    }

    private fun runTool(name: String, args: JSONObject, knownReadOnly: Boolean = false): String = when (name) {
        "run_command" -> {
            val cmd = args.optString("command")
            val auto = knownReadOnly || fullAuto() || (autoRead() && CommandPolicy.isReadOnly(cmd))
            val (risk, ttl) = Approval.classify(cmd)
            if (!auto && !confirm("Jalankan di Termux:\n\n$cmd", risk, ttl, "Akan dijalankan di Termux, folder kerja Anda", cmd)) {
                "Ditolak pengguna."
            } else if (stopRequested) {
                "Dihentikan pengguna."
            } else {
                termux(cmd)
            }
        }
        "write_file" -> {
            val path = args.optString("path")
            val content = args.optString("content")
            val needAsk = !fullAuto()
            val ex = if (needAsk) readExisting(path) else Pair(null, false)
            val (shown, summary) = if (needAsk) Approval.diff(ex.first, ex.second, content) else Pair("" as CharSequence, "")
            val ttl = if (ex.first == null && !ex.second) "Membuat file baru" else "Mengubah file"
            if (needAsk && !confirm("Tulis file: $path", Risk.CHANGE, ttl, "$path · $summary", shown)) {
                "Ditolak pengguna."
            } else if (stopRequested) {
                "Dihentikan pengguna."
            } else {
                val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
                val tmp = "${sh(path)}.mythos.b64"
                var res = termux("mkdir -p \"\$(dirname ${sh(path)})\" && : > $tmp")
                var pos = 0
                val step = 60000
                while (pos < b64.length && !stopRequested && res.contains("exit=0")) {
                    val part = b64.substring(pos, minOf(pos + step, b64.length))
                    res = termux("printf '%s' '$part' >> $tmp")
                    pos += step
                }
                if (stopRequested) "Dihentikan pengguna."
                else if (!res.contains("exit=0")) res
                else termux("base64 -d < $tmp > ${sh(path)} && rm -f $tmp && echo OK")
            }
        }
        else -> "Tool tidak dikenal: $name"
    }

    /** Isi file sekarang (untuk diff). Pasangan: (isi atau null jika belum ada, true jika terlalu besar). */
    private fun readExisting(path: String): Pair<String?, Boolean> {
        val p = sh(path)
        val res = termux(
            "p=$p; if [ ! -f \"\$p\" ]; then echo MYTHOS_NONE; elif [ \$(wc -c < \"\$p\") -gt 100000 ]; " +
                "then echo MYTHOS_BIG; else echo MYTHOS_EXISTS; cat \"\$p\"; fi", 150000
        )
        val out = commandOutput(res)
        val first = out.lineSequence().firstOrNull()?.trim() ?: ""
        return when (first) {
            "MYTHOS_EXISTS" -> out.substringAfter("\n", "") to false
            "MYTHOS_BIG" -> null to true
            else -> null to false
        }
    }

    private fun termux(cmd: String, limit: Int = 4000): String {
        if (stopRequested) return "Dihentikan pengguna."
        val action = "com.kita.agent.RESULT." + System.nanoTime()
        val latch = CountDownLatch(1)
        termuxLatch.set(latch)
        var out = "Tidak ada hasil."
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = i.getBundleExtra("result")
                if (b != null) {
                    val errmsg = b.getString("errmsg") ?: ""
                    val res = "exit=${b.getInt("exitCode")}\n" +
                        (b.getString("stdout") ?: "") + (b.getString("stderr") ?: "")
                    val full = (if (errmsg.isNotEmpty()) "ERR: $errmsg\n" else "") + res
                    out = if (full.length > limit) full.takeLast(limit) else full
                }
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        val token = "CMYTHOS_" + System.nanoTime()
        runToken = token
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                this, reqCode++, Intent(action).setPackage(packageName), flags
            )
            val wd = workDirAbs()
            val script = "cd ${sh(wd)} || exit 1\n: ${sh(token)}\n$cmd"
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                this.action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "$termuxBin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "export PATH=$termuxBin:\$PATH; $script"))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", wd)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            startService(i)
            val finished = try {
                latch.await(10, TimeUnit.MINUTES)
            } catch (_: InterruptedException) {
                false
            }
            if (stopRequested) return "Dihentikan pengguna."
            if (!finished) return "Timeout menunggu Termux."
        } catch (e: Exception) {
            if (stopRequested) return "Dihentikan pengguna."
            return "Gagal memanggil Termux: ${e.message}"
        } finally {
            termuxLatch.compareAndSet(latch, null)
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        return out
    }

    private fun killRunningTermux() {
        val token = runToken ?: return
        try {
            val script = "for p in \$(pgrep -f ${sh(token)} || true); do kill -TERM -\$p 2>/dev/null || true; kill -TERM \$p 2>/dev/null || true; done"
            val i = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                this.action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "$termuxBin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxHome)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            startService(i)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------ Riwayat

    private fun ensureChat() {
        if (chatId.isNotEmpty()) return
        chatId = "c" + System.currentTimeMillis()
        prefs.edit().putString("active_chat", chatId).apply()
    }

    private fun ensureTitle() {
        if (chatTitle.isNotBlank()) return
        val t = hist.firstOrNull { it.role == 0 }?.text?.trim()?.replace('\n', ' ') ?: return
        if (t.isNotBlank()) chatTitle = t.take(48)
    }

    private fun saveChat() {
        if (!::store.isInitialized) return
        synchronized(storeLock) {
            if (chatId.isBlank() || hist.isEmpty()) return
            ensureTitle()
            store.save(chatId, chatTitle.ifBlank { "Percakapan" }, ArrayList(hist), chatApk)
            prefs.edit().putString("active_chat", chatId).apply()
        }
    }

    private fun restoreActiveChat() {
        chatId = prefs.getString("active_chat", "") ?: ""
        if (chatId.isBlank()) return
        val loaded = store.load(chatId) ?: return
        if (loaded.first.isEmpty()) return
        hist.clear()
        hist.addAll(loaded.first)
        chatApk = loaded.second
        chatTitle = store.list().firstOrNull { it.id == chatId }?.title ?: "Percakapan"
        renderConversation()
    }

    private fun renderConversation() {
        cancelTyping()
        restoring = true
        chatBox.removeAllViews()
        streamBox = null
        feedGroup = null
        var any = false
        var group: ActivityUi.FeedGroup? = null
        fun closeGroup() { group?.finish(); group = null }
        for (h in hist) {
            if (h.role == 0 && h.text.isNotBlank()) {
                closeGroup()
                addMsg(USER, h.text)
                any = true
            } else if (h.role == 1 && h.text.isNotBlank()) {
                closeGroup()
                addMsg(AI, h.text)
                any = true
            } else if (h.role == 2) {
                if (group == null) {
                    group = ActivityUi.FeedGroup(this, ::dp)
                    insertBeforeStatus(group!!.root, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
                }
                for ((c, o) in h.results) group!!.addStep(c, o)
                any = true
            }
        }
        closeGroup()
        restoring = false
        if (chatApk.isNotBlank()) showApkButton(chatApk, false)
        emptyState.visibility = if (any || chatApk.isNotBlank()) View.GONE else View.VISIBLE
        scrollDown()
    }

    private fun plainText(s: String): String =
        s.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()

    private fun showHistory() {
        val items = store.list()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Riwayat").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Tutup", null).create()
        val baru = TextView(this).apply {
            text = "Percakapan baru"
            textSize = 16f
            setTextColor(col("#0B72D9"))
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                dialog.dismiss()
                newChat()
            }
        }
        box.addView(baru)
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Belum ada percakapan tersimpan."
                setTextColor(col("#5F6B7A"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        for (meta in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(8), dp(10), dp(4), dp(10))
            }
            val label = TextView(this).apply {
                val t = plainText(meta.title).take(60)
                val sb = android.text.SpannableStringBuilder(t + "\n" + plainText(meta.preview).take(80))
                sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = sb
                textSize = 14f
                setTextColor(col("#1F1F1F"))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val del = TextView(this).apply {
                text = "Hapus"
                textSize = 13f
                setTextColor(col("#B42318"))
                minHeight = dp(48)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Hapus percakapan ini?")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus") { _, _ ->
                            store.delete(meta.id)
                            if (meta.id == chatId) freshChat()
                            dialog.dismiss()
                            showHistory()
                        }
                        .show()
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(del)
            row.setOnClickListener {
                dialog.dismiss()
                openChat(meta.id)
            }
            box.addView(row)
        }
        dialog.show()
    }

    private fun openChat(id: String) {
        if (busy) { toast("Hentikan proses dulu"); return }
        if (id != chatId) saveChat()
        val loaded = store.load(id) ?: run { toast("Percakapan tidak ditemukan"); return }
        chatId = id
        prefs.edit().putString("active_chat", id).apply()
        hist.clear()
        hist.addAll(loaded.first)
        chatApk = loaded.second
        chatTitle = store.list().firstOrNull { it.id == id }?.title ?: "Percakapan"
        renderConversation()
    }

    // ------------------------------------------------ Folder kerja

    private fun pickWorkDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(i, 79)
        } catch (e: Exception) {
            toast("Pemilih folder tidak tersedia. Ketik path Termux secara manual.")
        }
    }

    private fun applyWorkDirUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        val path = treeUriToPath(uri)
        if (path == null) {
            toast("Path folder tidak terbaca. Ketik path Termux, misalnya ~/proyek.")
            return
        }
        prefs.edit().putString("workdir", path).putString("workdir_uri", uri.toString()).apply()
        workDirField?.setText(path)
        updateFolderHint()
        toast("Folder kerja: $path")
    }

    private fun treeUriToPath(uri: Uri): String? {
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return null }
        val parts = docId.split(":", limit = 2)
        if (parts.size == 2 && parts[0] == "primary") {
            val rel = parts[1].trim('/')
            return if (rel.isEmpty()) "/sdcard" else "/sdcard/$rel"
        }
        if (parts.size == 2 && parts[0].isNotBlank()) {
            val rel = parts[1].trim('/')
            return "/storage/${parts[0]}" + if (rel.isEmpty()) "" else "/$rel"
        }
        return null
    }

    // ------------------------------------------------ Pasang APK

    private fun looksLikeAndroidBuild(cmd: String): Boolean {
        val c = cmd.lowercase()
        val gradle = c.contains("gradlew") || c.contains("gradle")
        val buildish = c.contains("assemble") || c.contains("bundle") || c.contains("installdebug") || c.contains("installrelease")
        if (buildish) return true
        if (!gradle) return false
        if (c.contains(" build") || c.endsWith("build") || c.contains("apk")) {
            if (Regex("""\b(tasks|projects|help|properties|dependencies)\b""").containsMatchIn(c) &&
                !c.contains("assemble") && !c.contains("bundle")) return false
            return true
        }
        return false
    }

    private fun buildSucceeded(result: String): Boolean {
        if (result.contains("BUILD FAILED") || result.contains("FAILURE:")) return false
        if (!result.contains("BUILD SUCCESSFUL")) return false
        val exit = Regex("""exit=(-?\d+)""").find(result)?.groupValues?.get(1)?.toIntOrNull()
        return exit == null || exit == 0
    }

    private fun commandOutput(res: String): String {
        val lines = res.lines()
        val exitLine = lines.indexOfFirst { it.startsWith("exit=") }
        return if (exitLine >= 0) lines.drop(exitLine + 1).joinToString("\n") else res
    }

    private fun maybeOfferApk(cmd: String, result: String) {
        if (stopRequested) return
        if (!looksLikeAndroidBuild(cmd) || !buildSucceeded(result)) return
        setStatus(IconView.CLOCK, "Mencari file APK")
        val found = termux(
            "p=\$(find . -type f -name '*.apk' ! -path '*/intermediates/*' -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -1); printf 'APK_PATH:%s\\n' \"\$p\""
        )
        if (stopRequested) return
        val line = commandOutput(found).lineSequence().map { it.trim() }.lastOrNull { it.startsWith("APK_PATH:") } ?: return
        var p = line.removePrefix("APK_PATH:").trim()
        if (p.isEmpty() || !p.endsWith(".apk")) return
        if (!p.startsWith("/")) p = workDirAbs().trimEnd('/') + "/" + p.removePrefix("./")
        chatApk = p
        showApkButton(p, true)
        saveChat()
    }

    private fun showApkButton(path: String, scroll: Boolean) = runOnUiThread {
        if (!path.endsWith(".apk")) return@runOnUiThread
        emptyState.visibility = View.GONE
        for (i in chatBox.childCount - 1 downTo 0) {
            if (chatBox.getChildAt(i).tag == "apk-btn") chatBox.removeViewAt(i)
        }
        val btn = TextView(this).apply {
            tag = "apk-btn"
            text = "Pasang APK"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(col("#0B72D9"))
                cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { startInstallFlow(path) }
        }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
        insertBeforeStatus(btn, lp)
        if (scroll) scrollDown()
    }

    private fun startInstallFlow(termuxPath: String) {
        if (busy) { toast("Hentikan proses dulu"); return }
        stopRequested = false
        busy = true
        refreshAction()
        setStatus(KIND_DOTS, "Kita Agent menyiapkan APK")
        worker = Thread {
            try {
                val file = materializeApk(termuxPath)
                runOnUiThread {
                    hideStatus()
                    busy = false
                    refreshAction()
                    if (stopRequested) {
                        noteStopped()
                        return@runOnUiThread
                    }
                    if (file == null || !file.exists() || file.length() == 0L) {
                        toast("APK tidak ditemukan")
                        return@runOnUiThread
                    }
                    beginInstall(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideStatus()
                    busy = false
                    refreshAction()
                    toast("Gagal menyiapkan APK: " + (e.message ?: "").take(100))
                }
            }
        }
        worker?.start()
    }

    private fun materializeApk(termuxPath: String): File? {
        val abs = if (termuxPath.startsWith("/")) termuxPath else workDirAbs().trimEnd('/') + "/" + termuxPath.removePrefix("./")
        val dest = File(cacheDir, "install.apk")
        val direct = File(abs)
        if (direct.isFile && direct.canRead() && direct.length() > 0L) {
            direct.copyTo(dest, overwrite = true)
            return dest
        }
        val shared = "/sdcard/Download/KitaAgent/install-latest.apk"
        termux("mkdir -p /sdcard/Download/KitaAgent && cp ${sh(abs)} ${sh(shared)} && chmod a+r ${sh(shared)}")
        if (stopRequested) return null
        val sharedFile = File(shared)
        if (sharedFile.isFile && sharedFile.canRead() && sharedFile.length() > 0L) {
            sharedFile.copyTo(dest, overwrite = true)
            return dest
        }
        val sizeText = commandOutput(termux("wc -c < ${sh(abs)}")).trim().lineSequence().firstOrNull { it.all { ch -> ch.isDigit() } }
        val size = sizeText?.toLongOrNull() ?: return null
        if (size <= 0L || size > 180L * 1024 * 1024) return null
        val chunk = 200000
        var off = 0L
        var block = 0
        FileOutputStream(dest).use { out ->
            while (off < size) {
                if (stopRequested) return null
                val pct = ((off * 100) / size).toInt()
                setStatus(KIND_DOTS, "Menyalin APK $pct%")
                val res = termux(
                    "dd if=${sh(abs)} bs=$chunk skip=$block count=1 status=none | base64 | tr -d '\\n'",
                    500000
                )
                if (stopRequested) return null
                val b64 = commandOutput(res).trim()
                if (b64.isEmpty()) return null
                val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
                if (bytes.isEmpty()) return null
                out.write(bytes)
                off += bytes.size
                block++
            }
        }
        return if (dest.length() > 0L) dest else null
    }

    private fun beginInstall(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            AlertDialog.Builder(this)
                .setTitle("Izin memasang APK")
                .setMessage(
                    "Android membutuhkan izin agar Kita Agent boleh memasang aplikasi dari sumber ini.\n\n" +
                        "1. Ketuk Buka pengaturan.\n" +
                        "2. Aktifkan izinkan dari sumber ini.\n" +
                        "3. Kembali ke Kita Agent. Pemasangan akan dilanjutkan.\n\n" +
                        "Jika halaman pengaturan tidak terbuka, buka Pengaturan > Aplikasi > Kita Agent > Instal aplikasi tidak dikenal."
                )
                .setOnCancelListener { pendingInstall = null; awaitingInstallPermission = false }
                .setPositiveButton("Buka pengaturan") { _, _ ->
                    awaitingInstallPermission = true
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    try {
                        startActivity(i)
                    } catch (_: Exception) {
                        toast("Buka Pengaturan > Aplikasi > Kita Agent > Instal aplikasi tidak dikenal")
                    }
                }
                .setNegativeButton("Batal") { _, _ -> pendingInstall = null; awaitingInstallPermission = false }
                .show()
            return
        }
        try {
            installWithSession(file)
        } catch (e: Exception) {
            toast("Gagal membuka pemasang: " + (e.message ?: "").take(120))
        }
    }

    private fun installWithSession(file: File) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installSessionId = sessionId
        val session = installer.openSession(sessionId)
        file.inputStream().use { input ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val callback = Intent("com.kita.agent.INSTALL_STATUS").setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 9001, callback, flags)
        session.commit(pi.intentSender)
        session.close()
        toast("Membuka pemasang paket Android")
    }

    private fun registerInstallReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != installSessionId) return
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= 33) {
                            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            i.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null && confirm.component?.packageName?.contains("packageinstaller") == true) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { startActivity(confirm) } catch (e: Exception) {
                                toast("Tidak bisa membuka pemasang: ${e.message}")
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> toast("APK berhasil dipasang")
                    PackageInstaller.STATUS_FAILURE_ABORTED -> toast("Pemasangan dibatalkan")
                    else -> {
                        if (status != -999) {
                            val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "kode $status"
                            toast("Pemasangan gagal: $msg")
                        }
                    }
                }
            }
        }
        installReceiver = r
        val filter = IntentFilter("com.kita.agent.INSTALL_STATUS")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, filter)
        }
    }
}
