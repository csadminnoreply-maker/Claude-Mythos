package com.kita.agent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Feed aktivitas langsung: satu blok yang bisa dibuka-tutup menampung semua langkah
 * AI untuk satu balasan, mirip pola "Menjalankan perintah" pada asisten agen populer.
 * Tetap terbuka selagi berjalan, lalu otomatis mengecil jadi satu baris ringkasan.
 */
internal object ActivityUi {
    fun goodOut(out: String) = out.startsWith("exit=0")
    fun deniedOut(out: String) = out.startsWith("Ditolak") || out.startsWith("Dihentikan")

    private fun stepTitle(c: Call): String {
        val d = c.args.optString("deskripsi")
        if (d.isNotBlank()) return d.take(80)
        return if (c.name == "write_file") "Menulis " + c.args.optString("path").substringAfterLast('/')
        else c.args.optString("command").lineSequence().firstOrNull().orEmpty().take(70)
    }

    private fun counts(results: List<Pair<Call, String>>): String {
        val files = results.count { it.first.name == "write_file" && goodOut(it.second) }
        val builds = results.count {
            it.first.name == "run_command" && Approval.classify(it.first.args.optString("command")).second == "Membangun proyek"
        }
        val failed = results.count { !goodOut(it.second) && !deniedOut(it.second) }
        val skipped = results.count { deniedOut(it.second) }
        val parts = arrayListOf("${results.size} langkah")
        if (files > 0) parts.add("$files file")
        if (builds > 0) parts.add("$builds build")
        if (failed > 0) parts.add("$failed gagal")
        if (skipped > 0) parts.add("$skipped dilewati")
        return parts.joinToString(" · ")
    }

    /** Satu baris langkah: ikon status, judul, dan rincian yang bisa dibuka lewat ketukan. */
    fun stepRow(ctx: Context, dp: (Int) -> Int, c: Call, out: String): View {
        val skipped = deniedOut(out)
        val ok = goodOut(out)
        val cmd = c.args.optString("command")
        val danger = c.name == "run_command" && Approval.classify(cmd).first == Risk.DANGER
        val glyphText = if (skipped) "–" else if (ok) "✓" else "✗"
        val glyphColor = if (skipped) "#8A8A85" else if (ok) "#1E8E3E" else "#C5221F"
        val titleColor = if (danger && !skipped) "#C5221F" else "#4A4A46"

        val glyph = TextView(ctx).apply {
            text = glyphText
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(glyphColor))
            gravity = Gravity.CENTER
        }
        val ttl = TextView(ctx).apply {
            text = stepTitle(c)
            textSize = 13f
            setTextColor(Color.parseColor(titleColor))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(glyph, LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ttl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (c.name == "write_file") {
                val n = c.args.optString("content").lines().size
                addView(TextView(ctx).apply {
                    text = "+$n baris"
                    textSize = 11f
                    setTextColor(Color.parseColor("#1E8E3E"))
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        }
        val body = if (c.name == "write_file")
            c.args.optString("path") + "\n\n" + c.args.optString("content").take(800)
        else "$ $cmd"
        val detail = TextView(ctx).apply {
            text = body + "\n\n" + out.takeLast(1200)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#3C3C38"))
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F6F8"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) })
            setOnClickListener {
                detail.visibility = if (detail.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
    }

    /** Blok feed yang menampung langkah-langkah satu balasan; bisa dibuka-tutup. */
    class FeedGroup(private val ctx: Context, private val dp: (Int) -> Int) {
        private val results = ArrayList<Pair<Call, String>>()
        private var expanded = true
        private var running = true
        private var pulse: ValueAnimator? = null

        private val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A73E8"))
            }
        }
        private val headerText = TextView(ctx).apply {
            text = "Menjalankan perintah"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#3C3C38"))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        private val chevron = TextView(ctx).apply {
            text = "▾"
            textSize = 12f
            setTextColor(Color.parseColor("#8A8A85"))
        }
        private val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), 0, 0)
        }
        private val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
            addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(dp(18), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val root: LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7F8F9"))
                cornerRadius = dp(12).toFloat()
            }
            addView(header)
            addView(list)
            header.setOnClickListener { toggle() }
        }

        init {
            pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
                duration = 650
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        fun addStep(c: Call, out: String) {
            results.add(Pair(c, out))
            list.addView(
                stepRow(ctx, dp, c, out),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            headerText.text = "Menjalankan perintah · " + counts(results)
            list.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        /** Dipanggil saat balasan sudah selesai: hentikan animasi dan ciutkan jadi ringkasan. */
        fun finish() {
            if (!running) return
            running = false
            pulse?.cancel()
            pulse = null
            val failed = results.count { !goodOut(it.second) && !deniedOut(it.second) }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (failed > 0) "#B06000" else "#1E8E3E"))
            }
            dot.alpha = 1f
            headerText.text = "Selesai · " + counts(results)
            if (expanded && results.isNotEmpty()) toggle()
        }

        private fun toggle() {
            expanded = !expanded
            list.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.text = if (expanded) "▾" else "▸"
        }
    }
}
