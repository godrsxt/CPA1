package com.venice.carromaim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.venice.carromaim.core.AimResult
import com.venice.carromaim.core.BoardProfile
import com.venice.carromaim.core.Homography
import com.venice.carromaim.core.Kind
import com.venice.carromaim.core.Shot

/** Full-screen, non-touchable layer that draws the recommended shots over the game. */
class OverlayView(ctx: Context) : View(ctx) {
    private var hom: Homography? = null
    private var prof: BoardProfile? = null
    private var side = 1.0
    private var result: AimResult? = null
    var selected = 0
        private set

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun setBoard(h: Homography, p: BoardProfile) {
        hom = h; prof = p; side = p.boardSidePx(); invalidate()
    }

    fun setResult(r: AimResult?) {
        result = r; selected = 0; invalidate()
    }

    fun items(): List<Shot> {
        val r = result ?: return emptyList()
        return if (r.spot != null) r.here + r.spot else r.here
    }

    fun select(i: Int) {
        val n = items().size
        selected = if (n == 0) 0 else ((i % n) + n) % n
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val h = hom ?: return
        val p = prof ?: return
        val list = items()
        if (list.isEmpty()) return
        val w = Math.max(3f, (side * 0.0055).toFloat())
        // faint alternatives first, chosen shot on top
        for (i in list.indices) if (i != selected) drawShot(c, h, p, list[i], false, w)
        drawShot(c, h, p, list[selected], true, w)
    }

    private fun sx(h: Homography, x: Double, y: Double) = h.mapX(x, y).toFloat()
    private fun sy(h: Homography, x: Double, y: Double) = h.mapY(x, y).toFloat()

    private fun drawShot(c: Canvas, h: Homography, p: BoardProfile, s: Shot, strong: Boolean, w: Float) {
        val isSpot = s === result?.spot
        val alpha = if (strong) 255 else 85
        for (k in s.paths.indices) {
            val pts = s.paths[k]
            if (pts.size < 4) continue
            val kind = s.pathKinds[k]
            val potted = s.pathPotted[k]
            line.pathEffect = null
            line.strokeWidth = if (kind == Kind.STRIKER) w else w * 0.8f
            line.color = when {
                kind == Kind.STRIKER -> argb(alpha, 61, 220, 132)
                potted -> argb(alpha, 255, 213, 79)
                else -> argb((alpha * 0.8f).toInt(), 120, 200, 255)
            }
            if (!potted && kind != Kind.STRIKER) line.pathEffect = DashPathEffect(floatArrayOf(w * 2.5f, w * 2f), 0f)
            path.reset()
            path.moveTo(sx(h, pts[0].toDouble(), pts[1].toDouble()), sy(h, pts[0].toDouble(), pts[1].toDouble()))
            var i = 2
            while (i < pts.size) {
                path.lineTo(sx(h, pts[i].toDouble(), pts[i + 1].toDouble()), sy(h, pts[i].toDouble(), pts[i + 1].toDouble()))
                i += 2
            }
            c.drawPath(path, line)
            if (potted && strong) {
                // ring at the pocket the coin falls into
                val ex = pts[pts.size - 2].toDouble(); val ey = pts[pts.size - 1].toDouble()
                line.pathEffect = null
                line.strokeWidth = w
                line.color = argb(255, 255, 213, 79)
                c.drawCircle(sx(h, ex, ey), sy(h, ex, ey), (p.pocketR * side).toFloat(), line)
            }
        }
        // ghost striker where it first touches a coin
        if (s.firstHitKind >= 0) {
            line.pathEffect = null
            line.strokeWidth = w * 0.9f
            line.color = argb(alpha, 255, 255, 255)
            c.drawCircle(sx(h, s.contactX, s.contactY), sy(h, s.contactX, s.contactY), (p.strikerR * side).toFloat(), line)
        }
        if (strong) {
            // pull-back guide: where to drag for full power
            val bx = s.strikerX - s.dirX * p.maxDrag
            val by = s.strikerY - s.dirY * p.maxDrag
            line.color = argb(200, 255, 255, 255)
            line.strokeWidth = w * 0.7f
            line.pathEffect = DashPathEffect(floatArrayOf(w * 2f, w * 2f), 0f)
            c.drawLine(sx(h, s.strikerX, s.strikerY), sy(h, s.strikerX, s.strikerY), sx(h, bx, by), sy(h, bx, by), line)
            line.pathEffect = null
            fillP.color = argb(220, 255, 255, 255)
            c.drawCircle(sx(h, bx, by), sy(h, bx, by), w * 1.7f, fillP)
        }
        if (isSpot) {
            // striker spot suggestion: ring on the baseline
            line.pathEffect = null
            line.strokeWidth = w * 1.2f
            line.color = argb(alpha, 255, 82, 82)
            c.drawCircle(sx(h, s.strikerX, s.strikerY), sy(h, s.strikerX, s.strikerY), (p.strikerR * side).toFloat() * 1.1f, line)
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
}
