package com.kita.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.view.View
import android.widget.TextView
import kotlin.math.sin

/** Ikon vektor yang digambar lewat kode: tajam di semua kepadatan layar, tanpa file gambar. */
class IconView(ctx: Context, t: Int, c: Int = Color.parseColor("#1F1F1F")) : View(ctx) {
    var type: Int = t
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val path = Path()
    private val rect = RectF()

    companion object {
        const val MENU = 0
        const val PLUS = 1
        const val MIC = 2
        const val SEND = 3
        const val WAVE = 4
        const val NEW = 5
        const val CHEVRON = 6
        const val TERMINAL = 7
        const val CODE = 8
        const val CLOCK = 9
        const val TRASH = 10
        const val STOP = 11
        const val FOLDER = 12
        const val SPARKLE = 13
        const val BUBBLE = 14
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = s * 0.08f
        p.color = color
        when (type) {
            MENU -> {
                // dua garis rata kiri: atas panjang, bawah pendek
                p.strokeWidth = s * 0.058f
                c.drawLine(cx - s * 0.17f, cy - s * 0.09f, cx + s * 0.21f, cy - s * 0.09f, p)
                c.drawLine(cx - s * 0.17f, cy + s * 0.09f, cx + s * 0.04f, cy + s * 0.09f, p)
            }
            PLUS -> {
                p.strokeWidth = s * 0.05f
                c.drawLine(cx - s * 0.195f, cy, cx + s * 0.195f, cy, p)
                c.drawLine(cx, cy - s * 0.195f, cx, cy + s * 0.195f, p)
            }
            MIC -> {
                p.strokeWidth = s * 0.05f
                rect.set(cx - s * 0.135f, cy - s * 0.23f, cx + s * 0.135f, cy + s * 0.06f)
                c.drawRoundRect(rect, s * 0.135f, s * 0.135f, p)
                rect.set(cx - s * 0.23f, cy - s * 0.07f, cx + s * 0.23f, cy + s * 0.2f)
                c.drawArc(rect, 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.27f, p)
                c.drawLine(cx - s * 0.09f, cy + s * 0.27f, cx + s * 0.09f, cy + s * 0.27f, p)
            }
            SEND -> {
                p.strokeWidth = s * 0.075f
                c.drawLine(cx, cy + s * 0.2f, cx, cy - s * 0.2f, p)
                c.drawLine(cx - s * 0.17f, cy - s * 0.03f, cx, cy - s * 0.2f, p)
                c.drawLine(cx + s * 0.17f, cy - s * 0.03f, cx, cy - s * 0.2f, p)
            }
            WAVE -> {
                // lima batang gelombang suara
                p.strokeWidth = s * 0.066f
                val hs = floatArrayOf(0.07f, 0.15f, 0.21f, 0.13f, 0.07f)
                for (i in 0..4) {
                    val x = cx + (i - 2) * s * 0.1f
                    c.drawLine(x, cy - s * hs[i], x, cy + s * hs[i], p)
                }
            }
            SPARKLE -> {
                // bintang empat sudut (terisi)
                val r = s * 0.47f
                val k = r * 0.17f
                path.reset()
                path.moveTo(cx, cy - r)
                path.quadTo(cx + k, cy - k, cx + r, cy)
                path.quadTo(cx + k, cy + k, cx, cy + r)
                path.quadTo(cx - k, cy + k, cx - r, cy)
                path.quadTo(cx - k, cy - k, cx, cy - r)
                path.close()
                p.style = Paint.Style.FILL_AND_STROKE
                p.strokeWidth = s * 0.05f
                c.drawPath(path, p)
            }
            BUBBLE -> {
                // gelembung obrolan putus-putus dengan ekor kiri bawah
                p.strokeWidth = s * 0.05f
                val r = s * 0.215f
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, 203f, 66f, false, p)
                c.drawArc(rect, 292f, 56f, false, p)
                c.drawArc(rect, 12f, 56f, false, p)
                c.drawArc(rect, 88f, 36f, false, p)
                val a1 = Math.toRadians(128.0)
                val a2 = Math.toRadians(172.0)
                path.reset()
                path.moveTo(cx + r * Math.cos(a1).toFloat(), cy + r * Math.sin(a1).toFloat())
                path.lineTo(cx - r * 1.28f, cy + r * 1.22f)
                path.lineTo(cx + r * Math.cos(a2).toFloat(), cy + r * Math.sin(a2).toFloat())
                c.drawPath(path, p)
            }
            NEW -> {
                c.drawRoundRect(RectF(cx - s * 0.26f, cy - s * 0.2f, cx + s * 0.2f, cy + s * 0.26f), s * 0.08f, s * 0.08f, p)
                p.strokeWidth = s * 0.1f
                c.drawLine(cx - s * 0.04f, cy + s * 0.04f, cx + s * 0.26f, cy - s * 0.26f, p)
            }
            CHEVRON -> {
                c.drawLine(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.1f, cy, p)
                c.drawLine(cx + s * 0.1f, cy, cx - s * 0.1f, cy + s * 0.2f, p)
            }
            TERMINAL -> {
                p.strokeWidth = s * 0.06f
                c.drawRoundRect(RectF(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.24f), s * 0.06f, s * 0.06f, p)
                c.drawLine(cx - s * 0.18f, cy - s * 0.09f, cx - s * 0.08f, cy, p)
                c.drawLine(cx - s * 0.08f, cy, cx - s * 0.18f, cy + s * 0.09f, p)
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.16f, cy + s * 0.1f, p)
            }
            CODE -> {
                c.drawLine(cx - s * 0.12f, cy - s * 0.16f, cx - s * 0.28f, cy, p)
                c.drawLine(cx - s * 0.28f, cy, cx - s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.12f, cy - s * 0.16f, cx + s * 0.28f, cy, p)
                c.drawLine(cx + s * 0.28f, cy, cx + s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.04f, cy - s * 0.2f, cx - s * 0.04f, cy + s * 0.2f, p)
            }
            CLOCK -> {
                p.strokeWidth = s * 0.06f
                c.drawCircle(cx, cy, s * 0.3f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.16f, p)
                c.drawLine(cx, cy, cx + s * 0.12f, cy + s * 0.06f, p)
            }
            TRASH -> {
                c.drawLine(cx - s * 0.27f, cy - s * 0.17f, cx + s * 0.27f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.17f, cx - s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.17f, cx + s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.27f, cx + s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.17f, cx - s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx - s * 0.16f, cy + s * 0.28f, cx + s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx + s * 0.16f, cy + s * 0.28f, cx + s * 0.2f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.06f, cy - s * 0.06f, cx - s * 0.06f, cy + s * 0.18f, p)
                c.drawLine(cx + s * 0.06f, cy - s * 0.06f, cx + s * 0.06f, cy + s * 0.18f, p)
            }
            STOP -> {
                p.style = Paint.Style.FILL
                c.drawRoundRect(
                    RectF(cx - s * 0.15f, cy - s * 0.15f, cx + s * 0.15f, cy + s * 0.15f),
                    s * 0.045f, s * 0.045f, p
                )
            }
            FOLDER -> {
                c.drawRoundRect(RectF(cx - s * 0.3f, cy - s * 0.12f, cx + s * 0.3f, cy + s * 0.26f), s * 0.06f, s * 0.06f, p)
                c.drawLine(cx - s * 0.3f, cy - s * 0.06f, cx - s * 0.12f, cy - s * 0.06f, p)
                c.drawLine(cx - s * 0.12f, cy - s * 0.06f, cx - s * 0.04f, cy - s * 0.2f, p)
                c.drawLine(cx - s * 0.04f, cy - s * 0.2f, cx + s * 0.16f, cy - s * 0.2f, p)
            }
        }
    }
}

/**
 * Latar gradasi: putih di atas, perlahan menjadi biru langit di bawah.
 * Memakai dithering agar tidak ada pita warna (banding) pada layar OLED.
 */
class SkyDrawable : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val colors = intArrayOf(
        Color.parseColor("#FDFEFF"), Color.parseColor("#FCFDFE"), Color.parseColor("#F3F9FE"),
        Color.parseColor("#E0EFFC"), Color.parseColor("#C9E4FB"), Color.parseColor("#B1D9FB"),
        Color.parseColor("#99CEFC")
    )
    private val pos = floatArrayOf(0f, 0.35f, 0.5f, 0.61f, 0.72f, 0.84f, 1f)

    override fun onBoundsChange(b: Rect) {
        p.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(), colors, pos, Shader.TileMode.CLAMP
        )
    }

    override fun draw(c: Canvas) { c.drawRect(bounds, p) }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** Tiga titik oranye yang berdenyut: penanda "sedang berpikir/menganalisa". */
class DotsView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B72D9") }
    private var t = 0f
    private var anim: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cy = height / 2f
        val r0 = height * 0.11f
        for (i in 0..2) {
            val x = width * (0.2f + 0.3f * i)
            val r = r0 * (1f + 0.5f * sin(t - i * 1.2f))
            c.drawCircle(x, cy, r, p)
        }
    }
}

/** Teks dengan efek kilau yang bergerak (shimmer) saat aktif. */
class ShimmerText(ctx: Context) : TextView(ctx) {
    private var anim: ValueAnimator? = null
    private var shader: LinearGradient? = null
    private val m = Matrix()
    private var t = 0f
    private var active = false
    private val base = Color.parseColor("#A8A8A2")
    private val hi = Color.parseColor("#3A3A36")

    fun setShimmer(v: Boolean) {
        active = v
        shader = null
        if (v && isAttachedToWindow) {
            startAnim()
        } else {
            stopAnim()
            paint.shader = null
            invalidate()
        }
    }

    private fun startAnim() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (active && width > 0) {
            val w = width.toFloat()
            val sh = shader ?: LinearGradient(
                0f, 0f, w * 0.6f, 0f,
                intArrayOf(base, hi, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            ).also { shader = it }
            m.setTranslate(-w * 0.6f + t * w * 1.8f, 0f)
            sh.setLocalMatrix(m)
            paint.shader = sh
        }
        super.onDraw(c)
    }
}


/** Tanda Kita Agent. */
class MarkView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, w, h,
            Color.parseColor("#6CC4FF"), Color.parseColor("#3F6FF5"), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(RectF(0f, 0f, w, h), h * 0.28f, h * 0.28f, p)
        p.shader = null
        t.textSize = h * 0.58f
        val y = h / 2f - (t.descent() + t.ascent()) / 2f
        c.drawText("M", w / 2f, y, t)
    }
}
