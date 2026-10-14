package com.kita.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class Risk(val label: String, val fg: String, val bg: String) {
    READ("AMAN · HANYA MEMBACA", "#1E8E3E", "#E6F4EA"),
    CHANGE("MENGUBAH", "#B06000", "#FEF7E0"),
    DANGER("BERISIKO", "#C5221F", "#FCE8E6")
}

/** Bantuan untuk dialog persetujuan: klasifikasi risiko, diff baris, dan tampilan. */
internal object Approval {
    private val sep = """(^|[\s;&|(])"""
    private val del = Regex(sep + """(rm|rmdir|unlink|shred|truncate|dd|mkfs\S*)(\s|$)""")
    private val inst = Regex(sep + """(pkg|apt|apt-get|pip|pip3|npm|yarn|pnpm|gem|cargo)\s+(install|i|add|remove|uninstall|upgrade)\b""")
    private val net = Regex(sep + """(curl|wget|ssh|scp|sftp|nc|ncat|ftp|rsync)(\s|$)""")
    private val git = Regex(sep + """git\s+(push|reset|clean|rebase|checkout\s+--|restore)\b""")
    private val perm = Regex(sep + """(chmod|chown|kill|pkill|killall)(\s|$)""")
    private val build = Regex("""gradle|assemble|\bmake\b|\bcmake\b""")

    fun classify(cmd: String): Pair<Risk, String> = when {
        del.containsMatchIn(cmd) -> Risk.DANGER to "Menghapus file atau folder"
        inst.containsMatchIn(cmd) -> Risk.DANGER to "Memasang atau menghapus paket"
        net.containsMatchIn(cmd) -> Risk.DANGER to "Mengakses jaringan"
        git.containsMatchIn(cmd) -> Risk.DANGER to "Mengubah riwayat atau mengirim kode Git"
        perm.containsMatchIn(cmd) -> Risk.DANGER to "Mengubah izin atau menghentikan proses"
        build.containsMatchIn(cmd) -> Risk.CHANGE to "Membangun proyek"
        else -> Risk.CHANGE to "Menjalankan perintah"
    }

    /** Diff per baris. old == null berarti file baru. Mengembalikan teks berwarna dan ringkasan. */
    fun diff(old: String?, tooBig: Boolean, new: String): Pair<CharSequence, String> {
        val nl = new.lines()
        val sb = SpannableStringBuilder()
        fun line(prefix: String, t: String, bg: String?) {
            val s = sb.length
            sb.append(prefix).append(t.take(300)).append('\n')
            if (bg != null) sb.setSpan(BackgroundColorSpan(Color.parseColor(bg)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (tooBig || (old != null && (old.lines().size > 400 || nl.size > 400))) {
            nl.take(40).forEach { line("  ", it, null) }
            if (nl.size > 40) sb.append("⋯ ${nl.size - 40} baris lagi\n")
            return sb to "file besar, diff tidak ditampilkan (${nl.size} baris baru)"
        }
        if (old == null) {
            nl.take(60).forEach { line("+ ", it, "#E6F4EA") }
            if (nl.size > 60) sb.append("⋯ ${nl.size - 60} baris lagi\n")
            return sb to "file baru, ${nl.size} baris"
        }
        val ol = old.lines()
        val n = ol.size
        val m = nl.size
        val t = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            t[i][j] = if (ol[i] == nl[j]) t[i + 1][j + 1] + 1 else maxOf(t[i + 1][j], t[i][j + 1])
        val ops = ArrayList<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                ol[i] == nl[j] -> { ops.add(' ' to ol[i]); i++; j++ }
                t[i + 1][j] >= t[i][j + 1] -> { ops.add('-' to ol[i]); i++ }
                else -> { ops.add('+' to nl[j]); j++ }
            }
        }
        while (i < n) { ops.add('-' to ol[i]); i++ }
        while (j < m) { ops.add('+' to nl[j]); j++ }
        val add = ops.count { it.first == '+' }
        val rem = ops.count { it.first == '-' }
        if (add == 0 && rem == 0) return "Tidak ada perubahan.\n" to "isi sama dengan file sekarang"
        val keep = BooleanArray(ops.size) { k ->
            ops[k].first != ' ' || (k > 0 && ops[k - 1].first != ' ') || (k + 1 < ops.size && ops[k + 1].first != ' ')
        }
        var shown = 0
        var gap = false
        for (k in ops.indices) {
            if (!keep[k]) { gap = true; continue }
            if (shown >= 80) { sb.append("⋯ perubahan lain tidak ditampilkan\n"); break }
            if (gap) { sb.append("⋯\n"); gap = false }
            val (c, txt) = ops[k]
            line("$c ", txt, when (c) { '+' -> "#E6F4EA"; '-' -> "#FCE8E6"; else -> null })
            shown++
        }
        return sb to "+$add −$rem baris"
    }

    fun view(ctx: Context, dp: (Int) -> Int, risk: Risk, title: String, sub: String, body: CharSequence): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(4))
        }
        root.addView(TextView(ctx).apply {
            text = risk.label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(risk.fg))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(risk.bg))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1F2328"))
            setPadding(0, dp(10), 0, 0)
        })
        if (sub.isNotBlank()) root.addView(TextView(ctx).apply {
            text = sub
            textSize = 13f
            setTextColor(Color.parseColor("#5F6B7A"))
            setPadding(0, dp(2), 0, 0)
        })
        val code = TextView(ctx).apply {
            text = body
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1F2328"))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val long = body.length > 500 || body.count { it == '\n' } > 9
        val sv = ScrollView(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F6F8"))
                cornerRadius = dp(10).toFloat()
            }
            addView(code)
        }
        root.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (long) dp(240) else LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        return root
    }
}
