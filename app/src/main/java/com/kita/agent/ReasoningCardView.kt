package com.kita.agent

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Komponen UI untuk menampilkan langkah penalaran (Chain-of-Thought) secara live di chat.
 */
class ReasoningCardView(ctx: Context) : LinearLayout(ctx) {
    private val titleView = TextView(ctx).apply {
        textSize = 13f
        setTextColor(Color.parseColor("#0B72D9"))
        setTypeface(null, android.graphics.Typeface.BOLD)
        text = "🧠 Sedang Melakukan Penalaran Mendalam..."
    }

    private val stepView = TextView(ctx).apply {
        textSize = 12f
        setTextColor(Color.parseColor("#4A4A4A"))
        setPadding(0, 4, 0, 0)
        text = "Menganalisis konteks dan menyusun rencana eksekusi..."
    }

    init {
        orientation = VERTICAL
        setPadding(24, 16, 24, 16)
        setBackgroundColor(Color.parseColor("#F4F8FB"))
        
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(16, 8, 16, 8)
        }
        layoutParams = params

        addView(titleView)
        addView(stepView)
    }

    fun updateStep(title: String, detail: String) {
        titleView.text = title
        stepView.text = detail
    }
}
