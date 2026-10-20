package com.venice.carromaim.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** One recommended shot. Angles are radians in board space (y down), 0 = right. */
class Shot(
    val strikerX: Double,
    val strikerY: Double,
    val angle: Double,
    val score: Double,
    val rank: Double,
    /** Half-width (degrees) of the angle window that gives the same result: your aiming tolerance. */
    val halfMarginDeg: Double,
    val pottedKinds: IntArray,
    val foul: Boolean,
    val firstHitKind: Int,
    val contactX: Double,
    val contactY: Double,
    /** One polyline per moving disc (board coords, x,y,...). Index 0 = striker. Empty if never moved. */
    val paths: List<FloatArray>,
    val pathKinds: IntArray,
    val pathPotted: BooleanArray,
    val signature: Long
) {
    val points: Int get() = pottedKinds.sumOf { pointsFor(it) }
    val dirX: Double get() = cos(angle)
    val dirY: Double get() = sin(angle)
}

fun pointsFor(kind: Int): Int = when (kind) {
    Kind.WHITE -> 20
    Kind.BLACK -> 10
    Kind.QUEEN -> 50
    else -> 0
}

class SolveResult(val here: List<Shot>, val spot: Shot?)

/**
 * Freestyle, full-power shot finder.
 * Coarse sweep + exact ghost-ball candidates, then every candidate's angle window is measured
 * and the shot is aimed at the CENTRE of that window for the biggest possible error margin.
 */
class Solver(private val prof: BoardProfile) {
    private val P = prof.phys()
    private val power = prof.power.toDouble()

    private class Cand(val angle: Double, val sig: Long, val score: Double)

    private fun score(o: Outcome, kinds: IntArray): Double {
        var s = 0.0
        var cnt = 0
        for (i in 1 until o.n) if (o.potted[i]) { s += pointsFor(kinds[i]); cnt++ }
        if (o.strikerPotted) s -= 25.0
        else if (cnt > 0) s += 6.0 + 2.0 * cnt          // you keep the turn
        if (o.firstHit < 0) s -= 3.0
        return s
    }

    private fun build(coins: List<DiscState>, sx: Double, sy: Double): Pair<List<DiscState>, IntArray> {
        val list = ArrayList<DiscState>(coins.size + 1)
        list.add(DiscState(sx, sy, prof.strikerR.toDouble(), Kind.STRIKER))
        list.addAll(coins)
        return Pair(list, IntArray(list.size) { list[it].kind })
    }

    /** Best shots from the striker's current spot (top 3 distinct) and the best spot along the baseline. */
    fun solve(
        coins: List<DiscState>, sx: Double, sy: Double,
        withSpot: Boolean, cancel: () -> Boolean,
        onHere: ((List<Shot>) -> Unit)? = null
    ): SolveResult {
        val (discs, kinds) = build(coins, sx, sy)
        val sim = Sim(P, discs.size)
        sim.load(discs)
        val o = Outcome(discs.size)

        val here = shotsAt(sim, o, discs, kinds, sx, sy, fullCircle = true, cancel = cancel, want = 3)
        onHere?.invoke(here)
        if (!withSpot || cancel()) return SolveResult(here, null)

        // ---- search other striker positions along the baseline ----
        var bestX = sx; var bestS = here.firstOrNull()?.rank ?: -1e9
        var bestShot: Shot? = null
        val lo = prof.slideMin.toDouble(); val hi = prof.slideMax.toDouble()
        val spotCands = ArrayList<Pair<Double, Double>>()   // (x, coarse score)
        var x = lo
        while (x <= hi + 1e-9) {
            if (cancel()) return SolveResult(here, null)
            if (abs(x - sx) > 0.03 && !blocked(coins, x, sy)) {
                sim.setStriker(x, sy)
                val c = coarse(sim, o, discs, kinds, x, sy, fullCircle = false, step = 2.5, cancel = cancel)
                val top = c.maxByOrNull { it.score }
                if (top != null) spotCands.add(Pair(x, top.score))
            }
            x += 0.06
        }
        spotCands.sortByDescending { it.second }
        for ((px, _) in spotCands.take(2)) {
            // refine the spot locally (exact candidates only)
            for (dx in doubleArrayOf(-0.03, -0.02, -0.01, 0.0, 0.01, 0.02, 0.03)) {
                if (cancel()) return SolveResult(here, null)
                val xx = px + dx
                if (xx < lo || xx > hi || blocked(coins, xx, sy)) continue
                sim.setStriker(xx, sy)
                val c = ghostCands(sim, o, discs, kinds, xx, sy, false)
                val top = c.maxByOrNull { it.score } ?: continue
                if (top.score > bestS - 1e-9 && top.score > (here.firstOrNull()?.score ?: -1e9) + 1e-9) {
                    val shots = shotsFromCands(sim, o, discs, kinds, xx, sy, listOf(top), cancel, 1)
                    val s = shots.firstOrNull() ?: continue
                    if (s.rank > bestS) { bestS = s.rank; bestX = xx; bestShot = s }
                }
            }
        }
        val h0 = here.firstOrNull()
        val spot = if (bestShot != null && (h0 == null || bestShot.rank > h0.rank + 8.0)) bestShot else null
        return SolveResult(here, spot)
    }

    private fun blocked(coins: List<DiscState>, x: Double, y: Double): Boolean {
        val rs = prof.strikerR.toDouble()
        for (c in coins) if (hypot(c.x - x, c.y - y) < c.r + rs + 0.002) return true
        return false
    }

    private fun shotsAt(
        sim: Sim, o: Outcome, discs: List<DiscState>, kinds: IntArray, sx: Double, sy: Double,
        fullCircle: Boolean, cancel: () -> Boolean, want: Int
    ): List<Shot> {
        sim.setStriker(sx, sy)
        val cands = coarse(sim, o, discs, kinds, sx, sy, fullCircle, 1.0, cancel)
        return shotsFromCands(sim, o, discs, kinds, sx, sy, cands, cancel, want)
    }

    private fun angleList(fullCircle: Boolean, stepDeg: Double): DoubleArray {
        val out = ArrayList<Double>()
        if (fullCircle) {
            var a = 0.0
            while (a < 360.0) { out.add(Math.toRadians(a)); a += stepDeg }
        } else {
            var a = 200.0                       // upward half (y down) with a little margin
            while (a <= 340.0) { out.add(Math.toRadians(a)); a += stepDeg }
        }
        return out.toDoubleArray()
    }

    private fun ghostCands(
        sim: Sim, o: Outcome, discs: List<DiscState>, kinds: IntArray, sx: Double, sy: Double, fullCircle: Boolean
    ): List<Cand> {
        val res = ArrayList<Cand>()
        val rs = discs[0].r
        for (i in 1 until discs.size) {
            val c = discs[i]
            for (k in 0 until 4) {
                val px = P.pocketCx[k]; val py = P.pocketCy[k]
                val dx = px - c.x; val dy = py - c.y
                val d = hypot(dx, dy)
                if (d < 1e-6) continue
                val gx = c.x - dx / d * (c.r + rs); val gy = c.y - dy / d * (c.r + rs)
                val a = atan2(gy - sy, gx - sx)
                if (!fullCircle && sin(a) > 0.3) continue
                sim.run(a, power, o, false)
                res.add(Cand(a, o.signature(), score(o, kinds)))
            }
        }
        return res
    }

    private fun coarse(
        sim: Sim, o: Outcome, discs: List<DiscState>, kinds: IntArray, sx: Double, sy: Double,
        fullCircle: Boolean, step: Double, cancel: () -> Boolean
    ): List<Cand> {
        val res = ArrayList<Cand>()
        for (a in angleList(fullCircle, step)) {
            if (cancel()) return res
            sim.run(a, power, o, false)
            res.add(Cand(a, o.signature(), score(o, kinds)))
        }
        res.addAll(ghostCands(sim, o, discs, kinds, sx, sy, fullCircle))
        return res
    }

    private fun shotsFromCands(
        sim: Sim, o: Outcome, discs: List<DiscState>, kinds: IntArray, sx: Double, sy: Double,
        cands: List<Cand>, cancel: () -> Boolean, want: Int
    ): List<Shot> {
        sim.setStriker(sx, sy)
        // best representative per distinct outcome, best outcomes first
        val bySig = HashMap<Long, Cand>()
        for (c in cands) {
            val cur = bySig[c.sig]
            if (cur == null || c.score > cur.score) bySig[c.sig] = c
        }
        val ordered = bySig.values.filter { !isFoul(it.sig) }.sortedByDescending { it.score }.take(12)
        val shots = ArrayList<Shot>()
        val intervals = ArrayList<Triple<Long, Double, Double>>()
        for (c in ordered) {
            if (cancel()) break
            val (lo, hi) = window(sim, o, c.angle, c.sig)
            // all samples of this outcome that fall in an already measured window are the same shot
            if (intervals.any { it.first == c.sig && c.angle >= it.second - 1e-9 && c.angle <= it.third + 1e-9 }) continue
            intervals.add(Triple(c.sig, lo, hi))
            var centre = (lo + hi) / 2
            sim.run(centre, power, o, false)
            if (o.signature() != c.sig) centre = c.angle
            sim.run(centre, power, o, true)
            val half = Math.toDegrees(hi - lo) / 2
            val sc = score(o, kinds)
            val rank = sc * (0.6 + 0.4 * min(1.0, half / 0.30))
            shots.add(makeShot(sim, o, discs, kinds, sx, sy, centre, sc, rank, half))
        }
        shots.sortByDescending { it.rank }
        // keep distinct first targets / outcomes
        val out = ArrayList<Shot>()
        for (s in shots) {
            if (out.size >= want) break
            if (out.none { it.signature == s.signature }) out.add(s)
        }
        return out
    }

    private fun isFoul(sig: Long) = (sig and (1L shl 50)) != 0L

    /** Angle interval around a0 where the outcome signature stays identical (capped at +/-4 deg). */
    private fun window(sim: Sim, o: Outcome, a0: Double, sig: Long): Pair<Double, Double> {
        fun edge(dir: Int): Double {
            var good = a0
            var bad = Double.NaN
            val step = Math.toRadians(0.1)
            for (k in 1..40) {
                val a = a0 + dir * step * k
                sim.run(a, power, o, false)
                if (o.signature() == sig) good = a else { bad = a; break }
            }
            if (bad.isNaN()) return good
            repeat(7) {
                val m = (good + bad) / 2
                sim.run(m, power, o, false)
                if (o.signature() == sig) good = m else bad = m
            }
            return good
        }
        return Pair(edge(-1), edge(1))
    }

    private fun makeShot(
        sim: Sim, o: Outcome, discs: List<DiscState>, kinds: IntArray, sx: Double, sy: Double,
        angle: Double, sc: Double, rank: Double, half: Double
    ): Shot {
        val paths = ArrayList<FloatArray>()
        val pk = ArrayList<Int>(); val pp = ArrayList<Boolean>()
        val ps = o.paths!!
        for (i in discs.indices) {
            if (ps[i].size > 2 && (i == 0 || o.potted[i] || moved(ps[i]))) {
                paths.add(ps[i].toFloatArray()); pk.add(kinds[i]); pp.add(if (i == 0) o.strikerPotted else o.potted[i])
            }
        }
        val potted = ArrayList<Int>()
        for (i in 1 until discs.size) if (o.potted[i]) potted.add(kinds[i])
        return Shot(
            sx, sy, angle, sc, rank, half, potted.toIntArray(), o.strikerPotted,
            if (o.firstHit >= 0) kinds[o.firstHit] else -1, o.contactX, o.contactY,
            paths, pk.toIntArray(), pp.toBooleanArray(), o.signature()
        )
    }

    private fun moved(l: ArrayList<Float>): Boolean {
        val dx = l[l.size - 2] - l[0]; val dy = l[l.size - 1] - l[1]
        return hypot(dx.toDouble(), dy.toDouble()) > 0.01
    }
}
