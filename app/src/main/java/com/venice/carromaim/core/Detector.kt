package com.venice.carromaim.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

interface PixelSource {
    val width: Int
    val height: Int
    /** 0xRRGGBB at screen pixel (x, y). */
    fun rgb(x: Int, y: Int): Int
}

/** kind: 1 white, 2 black, 3 queen, 4 striker. quality 0..1 = how much of the rim was found. */
class Found(val x: Double, val y: Double, val kind: Int, val fill: Double, val radius: Double = 0.0)

class Detection(
    val coins: List<Found>,
    val striker: Found?,
    val all: List<Found>,
    val rejected: Int = 0,
    /** Median rim radius actually measured (board units), 0 if none. */
    val coinRadius: Double = 0.0,
    val strikerRadius: Double = 0.0
)

/**
 * Disc finder that does not depend on coin textures, shadows or glow:
 *  1. circular Hough votes from strong edges at the coin / striker radius,
 *  2. every candidate is verified and re-centred by a full-resolution rim fit (32 rays),
 *  3. only then is its kind chosen from the colour of its middle.
 */
object Detector {
    const val G = 384
    private const val RAYS = 32

    fun colorDist(a: Int, b: Int): Double {
        val r1 = (a shr 16) and 255; val g1 = (a shr 8) and 255; val b1 = a and 255
        val r2 = (b shr 16) and 255; val g2 = (b shr 8) and 255; val b2 = b and 255
        val rm = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble(); val dg = (g1 - g2).toDouble(); val db = (b1 - b2).toDouble()
        return sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)
    }

    private class Grid(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    private fun warp(src: PixelSource, h: Homography, lo: Double, span: Double): Grid {
        val r = FloatArray(G * G); val g = FloatArray(G * G); val b = FloatArray(G * G)
        for (gy in 0 until G) for (gx in 0 until G) {
            val bx = lo + (gx + 0.5) / G * span; val by = lo + (gy + 0.5) / G * span
            var sx = floor(h.mapX(bx, by)).toInt(); var sy = floor(h.mapY(bx, by)).toInt()
            if (sx < 0) sx = 0 else if (sx >= src.width) sx = src.width - 1
            if (sy < 0) sy = 0 else if (sy >= src.height) sy = src.height - 1
            val c = src.rgb(sx, sy)
            val i = gy * G + gx
            r[i] = ((c shr 16) and 255).toFloat(); g[i] = ((c shr 8) and 255).toFloat(); b[i] = (c and 255).toFloat()
        }
        return Grid(r, g, b)
    }

    private class Edges(val mag: FloatArray, val ux: FloatArray, val uy: FloatArray)

    private fun edges(gr: Grid): Edges {
        val mag = FloatArray(G * G); val ux = FloatArray(G * G); val uy = FloatArray(G * G)
        val ch = arrayOf(gr.r, gr.g, gr.b)
        for (y in 1 until G - 1) for (x in 1 until G - 1) {
            val i = y * G + x
            var bm = 0f; var bx = 0f; var by = 0f
            for (c in ch) {
                val gx = (c[i - G + 1] + 2 * c[i + 1] + c[i + G + 1] - c[i - G - 1] - 2 * c[i - 1] - c[i + G - 1]) / 4f
                val gy = (c[i + G - 1] + 2 * c[i + G] + c[i + G + 1] - c[i - G - 1] - 2 * c[i - G] - c[i - G + 1]) / 4f
                val m = sqrt(gx * gx + gy * gy)
                if (m > bm) { bm = m; bx = gx; by = gy }
            }
            mag[i] = bm
            if (bm > 1e-3f) { ux[i] = bx / bm; uy[i] = by / bm }
        }
        return Edges(mag, ux, uy)
    }

    private class Peak(val x: Int, val y: Int, val v: Float)

    /** Hough accumulator for discs of radius rc (grid cells) -> sorted peaks. */
    private fun houghPeaks(e: Edges, rc: Double, edgeThr: Float, peakThr: Float, yMin: Int, yMax: Int, maxPeaks: Int): List<Peak> {
        val acc = FloatArray(G * G)
        val radii = doubleArrayOf(rc * 0.93, rc, rc * 1.07)
        for (y in 1 until G - 1) for (x in 1 until G - 1) {
            val i = y * G + x
            val m = e.mag[i]
            if (m < edgeThr) continue
            val w = min(2f, m / edgeThr)
            for (rho in radii) for (sg in intArrayOf(-1, 1)) {
                val cx = Math.round(x + sg * rho * e.ux[i]).toInt()
                val cy = Math.round(y + sg * rho * e.uy[i]).toInt()
                if (cx < 0 || cy < 0 || cx >= G || cy >= G) continue
                acc[cy * G + cx] += w
            }
        }
        val bl = FloatArray(G * G)
        for (y in 1 until G - 1) for (x in 1 until G - 1) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) s += acc[(y + dy) * G + x + dx]
            bl[y * G + x] = s
        }
        val cands = ArrayList<Peak>()
        for (y in max(2, yMin) until min(G - 2, yMax)) for (x in 2 until G - 2) {
            val v = bl[y * G + x]
            if (v < peakThr) continue
            var isMax = true
            loop@ for (dy in -2..2) for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val o = bl[(y + dy) * G + x + dx]
                if (o > v || (o == v && (dy < 0 || (dy == 0 && dx < 0)))) { isMax = false; break@loop }
            }
            if (isMax) cands.add(Peak(x, y, v))
        }
        cands.sortByDescending { it.v }
        val out = ArrayList<Peak>()
        for (c in cands) {
            if (out.size >= maxPeaks) break
            if (out.any { hypot((it.x - c.x).toDouble(), (it.y - c.y).toDouble()) < 1.3 * rc }) continue
            out.add(c)
        }
        return out
    }

    private class Fit(val sx: Double, val sy: Double, val r: Double, val quality: Double)

    /** Fit a circle to the strongest edge on 32 rays around (cx, cy), in screen pixels. */
    private fun fitRim(src: PixelSource, cx0: Double, cy0: Double, rExp: Double, edgeThr: Double): Fit? {
        val ts = ArrayList<Double>()
        val px = ArrayList<Double>(); val py = ArrayList<Double>()
        val t0 = rExp * 0.72; val t1 = rExp * 1.35
        val n = max(4, ((t1 - t0) / 1.0).toInt()) + 1
        val col = IntArray(n)
        for (k in 0 until RAYS) {
            val th = 2 * Math.PI * k / RAYS
            val ca = cos(th); val sa = sin(th)
            var ok = true
            for (j in 0 until n) {
                val t = t0 + j * (t1 - t0) / (n - 1)
                val x = floor(cx0 + ca * t).toInt(); val y = floor(cy0 + sa * t).toInt()
                if (x < 0 || y < 0 || x >= src.width || y >= src.height) { ok = false; break }
                col[j] = src.rgb(x, y)
            }
            if (!ok) continue
            var bd = 0.0; var bj = -1
            for (j in 0 until n - 2) {
                val d = colorDist(col[j + 2], col[j])
                if (d > bd) { bd = d; bj = j + 1 }
            }
            if (bj < 0 || bd < edgeThr) continue
            val t = t0 + bj * (t1 - t0) / (n - 1)
            ts.add(t); px.add(cx0 + ca * t); py.add(cy0 + sa * t)
        }
        if (ts.size < 10) return null
        var cx = cx0; var cy = cy0
        var rr = median(ts)
        var use = BooleanArray(ts.size) { true }
        for (pass in 0 until 2) {
            repeat(5) {
                var sx = 0.0; var sy = 0.0; var cnt = 0
                for (i in ts.indices) {
                    if (!use[i]) continue
                    val dx = px[i] - cx; val dy = py[i] - cy
                    val d = hypot(dx, dy)
                    if (d < 1e-6) continue
                    sx += px[i] - rr * dx / d; sy += py[i] - rr * dy / d; cnt++
                }
                if (cnt == 0) return null
                cx = sx / cnt; cy = sy / cnt
                val ds = ArrayList<Double>()
                for (i in ts.indices) if (use[i]) ds.add(hypot(px[i] - cx, py[i] - cy))
                rr = median(ds)
            }
            if (pass == 0) {
                for (i in ts.indices) use[i] = abs(hypot(px[i] - cx, py[i] - cy) - rr) <= 0.14 * rr + 1.0
            }
        }
        var inl = 0; var sq = 0.0
        for (i in ts.indices) if (use[i]) { inl++; val e = hypot(px[i] - cx, py[i] - cy) - rr; sq += e * e }
        if (inl < 12) return null
        val rms = sqrt(sq / inl)
        if (rms > 0.10 * rr + 0.6) return null
        if (abs(rr - rExp) > 0.24 * rExp) return null
        if (hypot(cx - cx0, cy - cy0) > 0.7 * rExp) return null
        return Fit(cx, cy, rr, inl.toDouble() / RAYS)
    }

    private fun median(l: List<Double>): Double {
        val s = l.sorted()
        return s[s.size / 2]
    }

    private fun interiorColor(src: PixelSource, cx: Double, cy: Double, rad: Double): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        var dy = -rad
        while (dy <= rad) {
            var dx = -rad
            while (dx <= rad) {
                if (dx * dx + dy * dy <= rad * rad) {
                    val x = floor(cx + dx).toInt(); val y = floor(cy + dy).toInt()
                    if (x in 0 until src.width && y in 0 until src.height) {
                        val c = src.rgb(x, y)
                        rs.add((c shr 16) and 255); gs.add((c shr 8) and 255); bs.add(c and 255)
                    }
                }
                dx += 2.0
            }
            dy += 2.0
        }
        if (rs.isEmpty()) return 0
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    fun detect(src: PixelSource, prof: BoardProfile, h: Homography): Detection {
        val hi = h.inverse()
        val side = prof.boardSidePx()
        val tol = prof.tol.toDouble()
        val gr = warp(src, h, 0.0, 1.0)
        val e = edges(gr)
        val edgeThr = (26.0 / tol).toFloat()
        val rayThr = 38.0 / tol
        val refs = prof.colors
        val surf = refs[0]
        // how different from the board surface a disc's middle has to be
        var fgThr = 0.0
        if (surf != 0) {
            var mn = 1e9
            for (k in 1..3) if (refs[k] != 0) mn = min(mn, colorDist(refs[k], surf))
            if (mn < 1e8) fgThr = (0.4 * mn).coerceIn(25.0, 90.0)
        }
        val pockets = prof.phys()
        val rejected = intArrayOf(0)
        fun inPocket(bx: Double, by: Double): Boolean {
            for (k in 0 until 4) if (hypot(bx - pockets.pocketCx[k], by - pockets.pocketCy[k]) < prof.pocketR * 0.9) return true
            return false
        }
        /** A printed circle the user tapped away. Only ignored while its middle still has the remembered colour, so a real coin lying on it is still seen. */
        fun decoration(bx: Double, by: Double, mid: Int): Boolean {
            var i = 0
            while (i + 2 < prof.ignore.size) {
                if (hypot(bx - prof.ignore[i], by - prof.ignore[i + 1]) < 0.03 && colorDist(mid, prof.ignore[i + 2].toInt()) < 45.0) return true
                i += 3
            }
            return false
        }

        // ---------- striker: biggest consistent circle on your baseline ----------
        val rsCells = prof.strikerR * G.toDouble()
        val yLo = ((prof.baselineY - 0.075) * G).toInt(); val yHi = ((prof.baselineY + 0.075) * G).toInt()
        var striker: Found? = null
        var strikerScore = -1.0
        val sPeaks = houghPeaks(e, rsCells, edgeThr, (0.22 * 6 * Math.PI * rsCells).toFloat(), yLo, yHi, 12)
        for (pk in sPeaks) {
            val bx = (pk.x + 0.5) / G; val by = (pk.y + 0.5) / G
            val sx = h.mapX(bx, by); val sy = h.mapY(bx, by)
            val f = fitRim(src, sx, sy, prof.strikerR * side, rayThr) ?: continue
            val cb = hi.mapX(f.sx, f.sy); val cyb = hi.mapY(f.sx, f.sy)
            val smid = interiorColor(src, f.sx, f.sy, f.r * 0.5)
            if (abs(cyb - prof.baselineY) > 0.07 || inPocket(cb, cyb) || decoration(cb, cyb, smid)) { rejected[0]++; continue }
            // prefer the circle whose middle looks like the sampled striker (printed baseline circles lose here)
            var score = f.quality
            if (refs[4] != 0) score += 0.8 * (1.0 - min(1.0, colorDist(smid, refs[4]) / 140.0))
            if (striker == null || score > strikerScore) { striker = Found(cb, cyb, 4, f.quality, f.r / side); strikerScore = score }
        }

        // ---------- coins ----------
        val rcCells = prof.coinR * G.toDouble()
        val cPeaks = houghPeaks(e, rcCells, edgeThr, (0.22 * 6 * Math.PI * rcCells).toFloat(), 0, G, 160)
        val coins = ArrayList<Found>()
        for (pk in cPeaks) {
            val bx = (pk.x + 0.5) / G; val by = (pk.y + 0.5) / G
            if (striker != null && hypot(bx - striker.x, by - striker.y) < 0.8 * (prof.strikerR + prof.coinR)) continue
            val sx = h.mapX(bx, by); val sy = h.mapY(bx, by)
            val f = fitRim(src, sx, sy, prof.coinR * side, rayThr)
            if (f == null) { rejected[0]++; continue }
            val cb = hi.mapX(f.sx, f.sy); val cyb = hi.mapY(f.sx, f.sy)
            if (cb < 0.01 || cb > 0.99 || cyb < 0.01 || cyb > 0.99 || inPocket(cb, cyb)) { rejected[0]++; continue }
            if (coins.any { hypot(it.x - cb, it.y - cyb) < 1.5 * prof.coinR }) continue
            val mid = interiorColor(src, f.sx, f.sy, f.r * 0.5)
            if (decoration(cb, cyb, mid)) { rejected[0]++; continue }
            if (surf != 0 && colorDist(mid, surf) < fgThr) { rejected[0]++; continue }
            var kind = 0; var bd = 1e9
            for (k in 1..3) {
                if (refs[k] == 0) continue
                val d = colorDist(mid, refs[k])
                if (d < bd) { bd = d; kind = k }
            }
            if (kind == 0) { rejected[0]++; continue }
            if (surf != 0 && colorDist(mid, surf) < bd) { rejected[0]++; continue }
            coins.add(Found(cb, cyb, kind, f.quality, f.r / side))
        }
        val all = ArrayList<Found>(coins)
        if (striker != null) all.add(striker)
        val cr = if (coins.size >= 3) median(coins.map { it.radius }) else 0.0
        return Detection(coins, striker, all, rejected[0], cr, striker?.radius ?: 0.0)
    }

    /** Colour of the middle of a disc at board position (bx, by): stored with an ignore spot. */
    fun sampleMid(src: PixelSource, prof: BoardProfile, h: Homography, bx: Double, by: Double, coin: Boolean): Int {
        val r = (if (coin) prof.coinR else prof.strikerR) * prof.boardSidePx()
        return interiorColor(src, h.mapX(bx, by), h.mapY(bx, by), r * 0.5)
    }

    /** Is the striker still sitting at board position (bx, by)? Used while the overlay is shown. */
    fun strikerPresent(src: PixelSource, prof: BoardProfile, h: Homography, bx: Double, by: Double): Boolean {
        val side = prof.boardSidePx()
        val f = fitRim(src, h.mapX(bx, by), h.mapY(bx, by), prof.strikerR * side, 38.0 / prof.tol) ?: return false
        return f.quality >= 0.34 && hypot(f.sx - h.mapX(bx, by), f.sy - h.mapY(bx, by)) < 0.35 * prof.strikerR * side
    }

    /** Looks for the four dark pocket holes near the corners. Returns (bx, by, r) per pocket in board units. */
    fun proposePockets(src: PixelSource, prof: BoardProfile, h: Homography): Array<DoubleArray> {
        val lo = -0.08; val span = 1.16
        val gr = warp(src, h, lo, span)
        val lum = FloatArray(G * G) { 0.299f * gr.r[it] + 0.587f * gr.g[it] + 0.114f * gr.b[it] }
        val mid = ArrayList<Float>()
        val a0 = (0.3 * G).toInt(); val a1 = (0.7 * G).toInt()
        for (y in a0 until a1 step 3) for (x in a0 until a1 step 3) mid.add(lum[y * G + x])
        mid.sort()
        val med = mid[mid.size / 2]
        val thr = min(80f, 0.30f * med)
        val out = Array(4) { doubleArrayOf(prof.pocketX[it].toDouble(), prof.pocketY[it].toDouble(), prof.pocketR.toDouble()) }
        val reg = (0.20 * G / span * 1.0).toInt()   // search window in cells
        val cells = (G * 0.08 / span * 1.0).toInt() // cells per 0.08 board units == margin
        for (k in 0 until 4) {
            val right = (k == 1 || k == 2); val bottom = (k == 2 || k == 3)
            val x0 = if (right) G - reg else 0; val y0 = if (bottom) G - reg else 0
            val w = reg
            val dt = IntArray(w * w)
            val big = 1 shl 20
            for (y in 0 until w) for (x in 0 until w) dt[y * w + x] = if (lum[(y0 + y) * G + x0 + x] < thr) big else 0
            for (y in 0 until w) for (x in 0 until w) {
                val i = y * w + x; if (dt[i] == 0) continue
                var v = dt[i]
                v = min(v, (if (x > 0) dt[i - 1] else 0) + 3); v = min(v, (if (y > 0) dt[i - w] else 0) + 3)
                v = min(v, (if (x > 0 && y > 0) dt[i - w - 1] else 0) + 4); v = min(v, (if (x < w - 1 && y > 0) dt[i - w + 1] else 0) + 4)
                dt[i] = v
            }
            for (y in w - 1 downTo 0) for (x in w - 1 downTo 0) {
                val i = y * w + x; if (dt[i] == 0) continue
                var v = dt[i]
                v = min(v, (if (x < w - 1) dt[i + 1] else 0) + 3); v = min(v, (if (y < w - 1) dt[i + w] else 0) + 3)
                v = min(v, (if (x < w - 1 && y < w - 1) dt[i + w + 1] else 0) + 4); v = min(v, (if (x > 0 && y < w - 1) dt[i + w - 1] else 0) + 4)
                dt[i] = v
            }
            val defX = (out[k][0] - lo) / span * G - x0; val defY = (out[k][1] - lo) / span * G - y0
            var bi = -1; var bv = 0; var bs = -1e9
            for (i in dt.indices) {
                if (dt[i] == 0) continue
                val sc = dt[i] / 3.0 - 0.35 * hypot(i % w - defX, i / w - defY)
                if (sc > bs) { bs = sc; bi = i; bv = dt[i] }
            }
            val rc = bv / 3.0
            if (bi < 0 || rc < 0.012 * G / span) continue
            val cxg = x0 + bi % w + 0.5; val cyg = y0 + bi / w + 0.5
            out[k][0] = (lo + cxg / G * span).coerceIn(0.003, 0.997)
            out[k][1] = (lo + cyg / G * span).coerceIn(0.003, 0.997)
            out[k][2] = (rc / G * span).coerceIn(0.012, 0.07)
        }
        if (cells < 0) return out
        return out
    }
}
