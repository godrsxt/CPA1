package com.venice.carromaim.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Tiny helpers so every screen can be built in code (no XML layouts to break). */
object Ui {
    const val BG = 0xFF12161C.toInt()
    const val PANEL = 0xFF1C232C.toInt()
    const val TEXT = 0xFFEAEFF5.toInt()
    const val DIM = 0xFF93A1B3.toInt()
    const val ACCENT = 0xFF3DDC84.toInt()

    fun dp(a: Activity, v: Float) = (v * a.resources.displayMetrics.density).toInt()

    fun text(a: Activity, s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(a)
        t.text = s; t.textSize = size; t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun button(a: Activity, label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(a)
        b.text = label; b.isAllCaps = false; b.textSize = 15f
        b.setTextColor(if (primary) Color.BLACK else TEXT)
        val g = GradientDrawable()
        g.setColor(if (primary) ACCENT else 0xFF2A3440.toInt())
        g.cornerRadius = dp(a, 12f).toFloat()
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    fun page(a: Activity): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(a)
        sv.setBackgroundColor(BG)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val p = dp(a, 16f)
        col.setPadding(p, p * 2, p, p * 2)
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(sv, col)
    }

    fun gap(a: Activity, h: Float = 10f): View {
        val v = View(a)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(a, h))
        return v
    }

    fun add(col: LinearLayout, v: View, topDp: Float = 8f, a: Activity) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(a, topDp)
        col.addView(v, lp)
    }

    fun slider(
        a: Activity, col: LinearLayout, label: String, hint: String,
        min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, decimals: Int = 3
    ) {
        val title = text(a, "", 15f, TEXT, true)
        fun show(v: Float) { title.text = label + ":  " + String.format("%." + decimals + "f", v) }
        show(get())
        add(col, title, 16f, a)
        add(col, text(a, hint, 12f, DIM), 2f, a)
        val sb = SeekBar(a)
        sb.max = 1000
        sb.progress = (((get() - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + (max - min) * p / 1000f
                set(v); show(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        add(col, sb, 2f, a)
    }
}
