package com.venice.carromaim.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.venice.carromaim.core.Found
import com.venice.carromaim.core.Homography
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Shows the frozen game frame; lets you drag corner handles, a fit-ring, or tap a point. A loupe helps precision. */
class CalView(ctx: Context, val bmp: Bitmap) : View(ctx) {
    enum class Mode { CORNERS, RING, POINT, TEST }

    var mode = Mode.CORNERS
        set(v) { field = v; invalidate() }
    val corners = FloatArray(8)
    val ring = FloatArray(3)          // cx, cy, r  (bitmap px)
    val point = FloatArray(2)
    var hasPoint = false

    // TEST overlay
    var found: List<Found> = emptyList()
    var testHom: Homography? = null
    var coinPx = 0f
    var strikerPx = 0f
    var pocketCentres: List<FloatArray> = emptyList()
    var pocketPx = 0f
    var ignoredPts: List<FloatArray> = emptyList()
    var onTap: ((Float, Float) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    private val density = ctx.resources.displayMetrics.density
    private var s = 1f
    private var ox = 0f
    private var oy = 0f
    private var touching = false
    private var focusX = 0f
    private var focusY = 0f
    private var drag = -1                 // corner index, or ring: 0 move, 1 resize
    private var grabDx = 0f
    private var grabDy = 0f

    private val bmpPaint = Paint().apply { isFilterBitmap = false }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val path = Path()

    init {
        setBackgroundColor(Color.BLACK)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        // sensible starting corners: a square about the width of the screen
        val side = w * 0.92f
        val l = (w - side) / 2f
        val t = h * 0.5f - side / 2f
        corners[0] = l; corners[1] = t
        corners[2] = l + side; corners[3] = t
        corners[4] = l + side; corners[5] = t + side
        corners[6] = l; corners[7] = t + side
    }

    fun boardSide(): Float {
        fun d(a: Int, b: Int) = hypot(corners[2 * a] - corners[2 * b], corners[2 * a + 1] - corners[2 * b + 1])
        return (d(0, 1) + d(1, 2) + d(2, 3) + d(3, 0)) / 4f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        s = min(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        ox = (w - bmp.width * s) / 2f
        oy = (h - bmp.height * s) / 2f
    }

    private fun dp(v: Float) = v * density

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(ox, oy); c.scale(s, s)
        drawWorld(c, s)
        c.restore()
        if (touching && mode != Mode.TEST) drawLoupe(c)
    }

    private fun drawWorld(c: Canvas, scale: Float) {
        c.drawBitmap(bmp, 0f, 0f, bmpPaint)
        val u = 1f / scale                     // one view pixel in bitmap px
        stroke.strokeWidth = dp(2f) * u
        when (mode) {
            Mode.CORNERS -> drawCorners(c, u)
            Mode.RING -> {
                stroke.color = Color.parseColor("#3DDC84")
                c.drawCircle(ring[0], ring[1], ring[2], stroke)
                stroke.strokeWidth = dp(1f) * u
                c.drawLine(ring[0] - dp(8f) * u, ring[1], ring[0] + dp(8f) * u, ring[1], stroke)
                c.drawLine(ring[0], ring[1] - dp(8f) * u, ring[0], ring[1] + dp(8f) * u, stroke)
                fill.color = Color.parseColor("#3DDC84")
                c.drawCircle(ring[0] + ring[2], ring[1], dp(7f) * u, fill)
            }
            Mode.POINT -> if (hasPoint) {
                stroke.color = Color.parseColor("#3DDC84")
                c.drawCircle(point[0], point[1], dp(10f) * u, stroke)
                c.drawLine(point[0] - dp(18f) * u, point[1], point[0] + dp(18f) * u, point[1], stroke)
                c.drawLine(point[0], point[1] - dp(18f) * u, point[0], point[1] + dp(18f) * u, stroke)
            }
            Mode.TEST -> drawTest(c, u)
        }
    }

    private fun drawCorners(c: Canvas, u: Float) {
        val h = Homography.boardToScreen(corners)
        stroke.color = Color.parseColor("#3DDC84")
        path.reset()
        path.moveTo(corners[0], corners[1])
        for (i in 1..3) path.lineTo(corners[2 * i], corners[2 * i + 1])
        path.close()
        c.drawPath(path, stroke)
        if (h != null) {
            stroke.color = Color.argb(110, 255, 255, 255)
            stroke.strokeWidth = dp(1f) * u
            for (i in 1..7) {
                val f = i / 8.0
                c.drawLine(h.mapX(f, 0.0).toFloat(), h.mapY(f, 0.0).toFloat(), h.mapX(f, 1.0).toFloat(), h.mapY(f, 1.0).toFloat(), stroke)
                c.drawLine(h.mapX(0.0, f).toFloat(), h.mapY(0.0, f).toFloat(), h.mapX(1.0, f).toFloat(), h.mapY(1.0, f).toFloat(), stroke)
            }
        }
        for (i in 0..3) {
            fill.color = Color.parseColor("#3DDC84")
            c.drawCircle(corners[2 * i], corners[2 * i + 1], dp(9f) * u, fill)
            txt.textSize = dp(13f) * u
            txt.color = Color.BLACK
            c.drawText("${i + 1}", corners[2 * i], corners[2 * i + 1] + dp(4.5f) * u, txt)
        }
    }

    private fun drawTest(c: Canvas, u: Float) {
        stroke.strokeWidth = dp(2f) * u
        val h = testHom
        if (h != null) {
            stroke.color = Color.parseColor("#FFA726")
            for (p in pocketCentres) c.drawCircle(p[0], p[1], pocketPx, stroke)
            stroke.color = Color.parseColor("#FF5252")
            for (p in ignoredPts) {
                val k = dp(9f) * u
                c.drawLine(p[0] - k, p[1] - k, p[0] + k, p[1] + k, stroke)
                c.drawLine(p[0] - k, p[1] + k, p[0] + k, p[1] - k, stroke)
            }
            for (f in found) {
                val x = h.mapX(f.x, f.y).toFloat(); val y = h.mapY(f.x, f.y).toFloat()
                stroke.color = when (f.kind) {
                    1 -> Color.parseColor("#00E5FF")
                    2 -> Color.parseColor("#FF4081")
                    3 -> Color.parseColor("#FFEB3B")
                    else -> Color.parseColor("#3DDC84")
                }
                c.drawCircle(x, y, if (f.kind == 4) strikerPx else coinPx, stroke)
            }
        }
    }

    private fun drawLoupe(c: Canvas) {
        val size = dp(150f)
        val z = 5f
        val onRight = (focusX * s + ox) < width / 2f
        val lx = if (onRight) width - size - dp(8f) else dp(8f)
        val ly = dp(8f)
        c.save()
        c.translate(lx, ly)
        c.clipRect(0f, 0f, size, size)
        c.drawColor(Color.BLACK)
        c.translate(size / 2f, size / 2f)
        c.scale(z, z)
        c.translate(-focusX, -focusY)
        drawWorld(c, z)
        c.restore()
        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(2f)
        c.drawRect(lx, ly, lx + size, ly + size, stroke)
        stroke.color = Color.argb(160, 255, 255, 255)
        stroke.strokeWidth = 1f
        c.drawLine(lx + size / 2f, ly, lx + size / 2f, ly + size, stroke)
        c.drawLine(lx, ly + size / 2f, lx + size, ly + size / 2f, stroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val bx = (e.x - ox) / s
        val by = (e.y - oy) / s
        if (mode == Mode.TEST) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }
            if (e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - downX, e.y - downY) < dp(12f)) onTap?.invoke(bx, by)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; onDown(bx, by, e.x, e.y) }
            MotionEvent.ACTION_MOVE -> onMove(bx, by)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        invalidate()
        return true
    }

    private fun onDown(bx: Float, by: Float, vx: Float, vy: Float) {
        when (mode) {
            Mode.CORNERS -> {
                var best = 0; var bd = Float.MAX_VALUE
                for (i in 0..3) {
                    val d = hypot(corners[2 * i] - bx, corners[2 * i + 1] - by)
                    if (d < bd) { bd = d; best = i }
                }
                drag = best
                corners[2 * best] = bx; corners[2 * best + 1] = by
                focusX = bx; focusY = by
            }
            Mode.RING -> {
                val cxv = ring[0] * s + ox; val cyv = ring[1] * s + oy
                val hxv = (ring[0] + ring[2]) * s + ox
                val dc = hypot(vx - cxv, vy - cyv)
                val dh = hypot(vx - hxv, vy - cyv)
                if (dh < dc && dh < dp(44f)) {
                    drag = 1
                } else {
                    drag = 0
                    if (dc < dp(60f)) { grabDx = ring[0] - bx; grabDy = ring[1] - by } else { grabDx = 0f; grabDy = 0f }
                    ring[0] = bx + grabDx; ring[1] = by + grabDy
                }
                focusX = if (drag == 1) ring[0] + ring[2] else ring[0]; focusY = ring[1]
            }
            Mode.POINT -> { point[0] = bx; point[1] = by; hasPoint = true; focusX = bx; focusY = by }
            Mode.TEST -> {}
        }
    }

    private fun onMove(bx: Float, by: Float) {
        when (mode) {
            Mode.CORNERS -> if (drag >= 0) {
                corners[2 * drag] = bx.coerceIn(0f, bmp.width.toFloat()); corners[2 * drag + 1] = by.coerceIn(0f, bmp.height.toFloat())
                focusX = corners[2 * drag]; focusY = corners[2 * drag + 1]
            }
            Mode.RING -> {
                if (drag == 0) { ring[0] = bx + grabDx; ring[1] = by + grabDy; focusX = ring[0]; focusY = ring[1] }
                else if (drag == 1) {
                    ring[2] = max(3f, hypot(bx - ring[0], by - ring[1]))
                    focusX = ring[0] + ring[2]; focusY = ring[1]
                }
            }
            Mode.POINT -> { point[0] = bx; point[1] = by; focusX = bx; focusY = by }
            Mode.TEST -> {}
        }
    }

    fun nudgeRadius(d: Float) { ring[2] = max(3f, ring[2] + d); invalidate() }
}
