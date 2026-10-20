package com.venice.carromaim.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface PixelSource {
    val width: Int
    val height: Int
    /** 0xRRGGBB at screen pixel (x, y). */
    fun rgb(x: Int, y: Int): Int
}

class Found(val x: Double, val y: Double, val kind: Int, val fill: Double)

class Detection(val coins: List<Found>, val striker: Found?, val all: List<Found>)

/**
 * Finds discs on the calibrated board by colour classes the user sampled
 * (surface / white / black / queen / striker) and a distance-transform disc fit,
 * so touching coins are separated and thin printed lines are ignored.
 */
object Detector {
    const val G = 320
    private const val BASE_TOL = 70.0

    fun colorDist(a: Int, b: Int): Double {
        val r1 = (a shr 16) and 255; val g1 = (a shr 8) and 255; val b1 = a and 255
        val r2 = (b shr 16) and 255; val g2 = (b shr 8) and 255; val b2 = b and 255
        val rm = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble(); val dg = (g1 - g2).toDouble(); val db = (b1 - b2).toDouble()
        return sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)
    }

    /** class id per grid cell: 0 none/surface, 1 white, 2 black, 3 queen, 4 striker */
    fun classify(src: PixelSource, prof: BoardProfile, h: Homography): ByteArray {
        val cls = ByteArray(G * G)
        val tol = BASE_TOL * prof.tol
        val refs = prof.colors
        val active = BooleanArray(5) { it == 0 || refs[it] != 0 }
        for (gy in 0 until G) for (gx in 0 until G) {
            val bx = (gx + 0.5) / G; val by = (gy + 0.5) / G
            var sx = Math.floor(h.mapX(bx, by)).toInt(); var sy = Math.floor(h.mapY(bx, by)).toInt()
            if (sx < 0) sx = 0 else if (sx >= src.width) sx = src.width - 1
            if (sy < 0) sy = 0 else if (sy >= src.height) sy = src.height - 1
            val c = src.rgb(sx, sy)
            var best = -1; var bd = 1e9
            for (k in 0 until 5) {
                if (!active[k]) continue
                val d = colorDist(c, refs[k])
                if (d < bd) { bd = d; best = k }
            }
            if (best > 0 && bd < tol) cls[gy * G + gx] = best.toByte()
        }
        return cls
    }

    fun detect(src: PixelSource, prof: BoardProfile, h: Homography): Detection {
        val cls = classify(src, prof, h)
        val all = ArrayList<Found>()
        for (k in 1..4) {
            if (prof.colors[k] == 0) continue
            val rc = (if (k == 4) prof.strikerR else prof.coinR) * G.toDouble()
            findDiscs(cls, k, rc, prof, all)
        }
        // cross-class suppression: two discs cannot overlap
        all.sortByDescending { it.fill }
        val kept = ArrayList<Found>()
        for (f in all) {
            val rf = (if (f.kind == 4) prof.strikerR else prof.coinR).toDouble()
            if (kept.none { hypot(it.x - f.x, it.y - f.y) < 0.8 * (rf + (if (it.kind == 4) prof.strikerR else prof.coinR)) }) kept.add(f)
        }
        // sub-pixel pass at full screen resolution
        val hi = h.inverse()
        val side = prof.boardSidePx()
        for (idx in kept.indices) kept[idx] = refine(src, prof, h, hi, kept[idx], side)
        var striker: Found? = null
        for (f in kept) if (f.kind == 4 && abs(f.y - prof.baselineY) < 0.09) {
            if (striker == null || f.fill > striker.fill) striker = f
        }
        val coins = kept.filter { it.kind != 4 }
        return Detection(coins, striker, kept)
    }

    private fun pixelClass(c: Int, prof: BoardProfile, tol: Double): Int {
        var best = -1; var bd = 1e9
        for (k in 0 until 5) {
            if (k != 0 && prof.colors[k] == 0) continue
            val d = colorDist(c, prof.colors[k])
            if (d < bd) { bd = d; best = k }
        }
        return if (best > 0 && bd < tol) best else 0
    }

    /** Centroid of this disc's colour inside a circle of its true radius, in real screen pixels. */
    private fun refine(src: PixelSource, prof: BoardProfile, h: Homography, hi: Homography, f: Found, side: Double): Found {
        val tol = BASE_TOL * prof.tol
        val rpx = (if (f.kind == 4) prof.strikerR else prof.coinR) * side
        if (rpx < 3) return f
        val sx0 = h.mapX(f.x, f.y); val sy0 = h.mapY(f.x, f.y)
        var cx = sx0; var cy = sy0
        for (it in 0 until 4) {
            var sx = 0.0; var sy = 0.0; var n = 0
            val x0 = max(0, Math.floor(cx - rpx).toInt()); val x1 = min(src.width - 1, Math.ceil(cx + rpx).toInt())
            val y0 = max(0, Math.floor(cy - rpx).toInt()); val y1 = min(src.height - 1, Math.ceil(cy + rpx).toInt())
            for (yy in y0..y1) for (xx in x0..x1) {
                val dx = xx + 0.5 - cx; val dy = yy + 0.5 - cy
                if (dx * dx + dy * dy > rpx * rpx) continue
                if (pixelClass(src.rgb(xx, yy), prof, tol) == f.kind) { sx += xx + 0.5; sy += yy + 0.5; n++ }
            }
            if (n < 0.35 * Math.PI * rpx * rpx) return f
            cx = sx / n; cy = sy / n
        }
        if (hypot(cx - sx0, cy - sy0) > 0.6 * rpx) return f
        return Found(hi.mapX(cx, cy), hi.mapY(cx, cy), f.kind, f.fill)
    }

    private fun findDiscs(cls0: ByteArray, k: Int, rc: Double, prof: BoardProfile, out: MutableList<Found>) {
        val kb: Byte = 1
        val cls = closeMask(cls0, k.toByte(), 3)
        // chamfer 3-4 distance transform (units of 1/3 px)
        val dt = IntArray(G * G)
        val big = 1 shl 20
        for (i in 0 until G * G) dt[i] = if (cls[i] == kb) big else 0
        for (y in 0 until G) for (x in 0 until G) {
            val i = y * G + x
            if (dt[i] == 0) continue
            var v = dt[i]
            v = min(v, (if (x > 0) dt[i - 1] else 0) + 3)
            v = min(v, (if (y > 0) dt[i - G] else 0) + 3)
            v = min(v, (if (x > 0 && y > 0) dt[i - G - 1] else 0) + 4)
            v = min(v, (if (x < G - 1 && y > 0) dt[i - G + 1] else 0) + 4)
            dt[i] = v
        }
        for (y in G - 1 downTo 0) for (x in G - 1 downTo 0) {
            val i = y * G + x
            if (dt[i] == 0) continue
            var v = dt[i]
            v = min(v, (if (x < G - 1) dt[i + 1] else 0) + 3)
            v = min(v, (if (y < G - 1) dt[i + G] else 0) + 3)
            v = min(v, (if (x < G - 1 && y < G - 1) dt[i + G + 1] else 0) + 4)
            v = min(v, (if (x > 0 && y < G - 1) dt[i + G - 1] else 0) + 4)
            dt[i] = v
        }
        val minDt = (0.62 * rc * 3).toInt()
        val maxDt = ((1.30 * rc + 0.7) * 3).toInt()
        class Cand(val i: Int, val d: Int)
        val cands = ArrayList<Cand>()
        for (y in 1 until G - 1) for (x in 1 until G - 1) {
            val i = y * G + x
            val d = dt[i]
            if (d < minDt) continue
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val dj = dt[i + dy * G + dx]
                if (dj > d || (dj == d && (dy < 0 || (dy == 0 && dx < 0)))) { isMax = false; break@loop }
            }
            if (isMax) cands.add(Cand(i, d))
        }
        cands.sortByDescending { it.d }
        val accepted = ArrayList<DoubleArray>()
        val ir = (0.85 * rc).toInt() + 1
        val pockets = prof.phys()
        for (c in cands) {
            if (c.d > maxDt) continue
            val cx0 = (c.i % G).toDouble(); val cy0 = (c.i / G).toDouble()
            if (accepted.any { hypot(it[0] - cx0, it[1] - cy0) < 1.5 * rc }) continue
            // fill ratio inside the expected disc
            var tot = 0; var hit = 0
            val r2 = (0.85 * rc) * (0.85 * rc)
            for (dy in -ir..ir) for (dx in -ir..ir) {
                if (dx * dx + dy * dy > r2) continue
                val xx = cx0.toInt() + dx; val yy = cy0.toInt() + dy
                if (xx < 0 || yy < 0 || xx >= G || yy >= G) continue
                tot++
                if (cls[yy * G + xx] == kb) hit++
            }
            if (tot == 0) continue
            val fill = hit.toDouble() / tot
            if (fill < 0.66) continue
            // sub-pixel centre: centroid of this class inside the disc window, iterated
            var mx = cx0; var my = cy0
            val wr = rc * 1.0; val wr2 = wr * wr
            for (it in 0 until 3) {
                var sx = 0.0; var sy = 0.0; var n = 0
                val x0 = max(0, (mx - wr).toInt()); val x1 = min(G - 1, (mx + wr).toInt() + 1)
                val y0 = max(0, (my - wr).toInt()); val y1 = min(G - 1, (my + wr).toInt() + 1)
                for (yy in y0..y1) for (xx in x0..x1) {
                    val dx = xx - mx; val dy = yy - my
                    if (dx * dx + dy * dy > wr2) continue
                    if (cls[yy * G + xx] == kb) { sx += xx; sy += yy; n++ }
                }
                if (n > 0) { mx = sx / n; my = sy / n }
            }
            val bx = (mx + 0.5) / G; val by = (my + 0.5) / G
            if (bx < 0.01 || bx > 0.99 || by < 0.01 || by > 0.99) continue
            // pocket holes are dark discs too: ignore anything sitting in a pocket
            var inPocket = false
            for (p in 0 until 4) if (hypot(bx - pockets.pocketCx[p], by - pockets.pocketCy[p]) < prof.pocketR * 0.75) inPocket = true
            if (inPocket) continue
            accepted.add(doubleArrayOf(cx0, cy0))
            out.add(Found(bx, by, k, fill))
        }
    }

    /** Binary mask of class k, morphologically closed so printed patterns inside a coin do not punch holes. */
    private fun closeMask(cls: ByteArray, k: Byte, rad: Int): ByteArray {
        val a = ByteArray(G * G); val b = ByteArray(G * G)
        for (i in 0 until G * G) if (cls[i] == k) a[i] = 1
        // dilate horizontally then vertically
        for (y in 0 until G) for (x in 0 until G) {
            var v = 0
            for (d in -rad..rad) { val xx = x + d; if (xx in 0 until G && a[y * G + xx].toInt() == 1) { v = 1; break } }
            b[y * G + x] = v.toByte()
        }
        for (y in 0 until G) for (x in 0 until G) {
            var v = 0
            for (d in -rad..rad) { val yy = y + d; if (yy in 0 until G && b[yy * G + x].toInt() == 1) { v = 1; break } }
            a[y * G + x] = v.toByte()
        }
        // erode (outside the grid counts as set)
        for (y in 0 until G) for (x in 0 until G) {
            var v = 1
            for (d in -rad..rad) { val xx = x + d; if (xx in 0 until G && a[y * G + xx].toInt() == 0) { v = 0; break } }
            b[y * G + x] = v.toByte()
        }
        for (y in 0 until G) for (x in 0 until G) {
            var v = 1
            for (d in -rad..rad) { val yy = y + d; if (yy in 0 until G && b[yy * G + x].toInt() == 0) { v = 0; break } }
            a[y * G + x] = v.toByte()
        }
        return a
    }

    /** Is the striker still sitting at board position (bx, by)? Cheap check used while the overlay is shown. */
    fun strikerPresent(src: PixelSource, prof: BoardProfile, h: Homography, bx: Double, by: Double): Boolean {
        val ref = prof.colors[4]
        if (ref == 0) return true
        val tol = BASE_TOL * prof.tol
        var tot = 0; var hit = 0
        val rr = prof.strikerR * 0.55
        var a = -rr
        val step = rr / 5
        while (a <= rr) {
            var b = -rr
            while (b <= rr) {
                if (a * a + b * b <= rr * rr) {
                    val sx = Math.floor(h.mapX(bx + a, by + b)).toInt()
                    val sy = Math.floor(h.mapY(bx + a, by + b)).toInt()
                    if (sx in 0 until src.width && sy in 0 until src.height) {
                        tot++
                        val c = src.rgb(sx, sy)
                        val ds = colorDist(c, ref)
                        if (ds < tol && (prof.colors[0] == 0 || ds < colorDist(c, prof.colors[0]))) hit++
                    }
                }
                b += step
            }
            a += step
        }
        return tot > 0 && hit.toDouble() / tot >= 0.5
    }
}
