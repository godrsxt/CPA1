package com.venice.carromaim.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object Kind {
    const val STRIKER = 0
    const val WHITE = 1
    const val BLACK = 2
    const val QUEEN = 3
}

class DiscState(val x: Double, val y: Double, val r: Double, val kind: Int)

class PhysParams(
    val wallRest: Double,
    val coinRest: Double,
    val strikerMass: Double,
    /** Striker speed at 100% power. Deceleration is fixed at 1.0, so v0 = sqrt(2 * rollLen). */
    val v0: Double,
    val pocketCx: DoubleArray,
    val pocketCy: DoubleArray,
    val pocketCap: Double
)

class Outcome(val n: Int) {
    val potted = BooleanArray(n)
    var strikerPotted = false
    var firstHit = -1
    var wallBeforeHit = false
    var contactX = 0.0
    var contactY = 0.0
    var paths: Array<ArrayList<Float>>? = null

    fun reset() {
        java.util.Arrays.fill(potted, false)
        strikerPotted = false; firstHit = -1; wallBeforeHit = false
    }

    /** Identity of the result: which discs dropped and whether the striker did. */
    fun signature(): Long {
        var s = 0L
        for (i in 1 until n) if (potted[i]) s = s or (1L shl (i - 1))
        if (strikerPotted) s = s or (1L shl 50)
        if (firstHit < 0) s = s or (1L shl 51)
        return s
    }
}

/**
 * Continuous-collision carrom simulator in board units (square side 1).
 * Constant deceleration, exact time-of-impact for disc/disc, disc/cushion and disc/pocket,
 * impulse collisions with restitution. Index 0 is the striker.
 */
class Sim(private val P: PhysParams, private val n: Int) {
    private val ix = DoubleArray(n); private val iy = DoubleArray(n)
    private val x = DoubleArray(n); private val y = DoubleArray(n)
    private val vx = DoubleArray(n); private val vy = DoubleArray(n)
    private val r = DoubleArray(n); private val mass = DoubleArray(n)
    private val alive = BooleanArray(n)

    fun load(discs: List<DiscState>) {
        require(discs.size == n)
        for (i in 0 until n) {
            ix[i] = discs[i].x; iy[i] = discs[i].y; r[i] = discs[i].r
            mass[i] = if (i == 0) P.strikerMass else 1.0
        }
        // separate tiny detection overlaps so nothing starts interpenetrating
        for (pass in 0 until 3) for (i in 1 until n) for (j in i + 1 until n) {
            val dx = ix[j] - ix[i]; val dy = iy[j] - iy[i]
            val d = hypot(dx, dy); val minD = r[i] + r[j]
            if (d < minD && d > 1e-9) {
                val push = (minD - d) / 2 + 1e-6
                ix[i] -= dx / d * push; iy[i] -= dy / d * push
                ix[j] += dx / d * push; iy[j] += dy / d * push
            }
        }
    }

    fun setStriker(px: Double, py: Double) { ix[0] = px; iy[0] = py }

    fun run(angle: Double, power: Double, o: Outcome, record: Boolean) {
        for (i in 0 until n) { x[i] = ix[i]; y[i] = iy[i]; vx[i] = 0.0; vy[i] = 0.0; alive[i] = true }
        o.reset()
        val sp = P.v0 * power
        vx[0] = cos(angle) * sp; vy[0] = sin(angle) * sp
        if (record) {
            val ps = o.paths ?: Array(n) { ArrayList<Float>() }.also { o.paths = it }
            for (i in 0 until n) { ps[i].clear(); ps[i].add(x[i].toFloat()); ps[i].add(y[i].toFloat()) }
        }
        var steps = 0
        while (steps < MAX_STEPS) {
            var any = false
            for (i in 0 until n) if (alive[i] && (vx[i] != 0.0 || vy[i] != 0.0)) { any = true; break }
            if (!any) break
            steps++
            var rem = DT
            var guard = 0
            while (rem > 1e-12 && guard < 40) {
                guard++
                var bt = rem; var type = 0; var bi = -1; var bj = -1; var axis = 0
                for (i in 0 until n) {
                    if (!alive[i]) continue
                    val ux = vx[i]; val uy = vy[i]
                    if (ux == 0.0 && uy == 0.0) continue
                    val ri = r[i]
                    if (ux > 0) {
                        val tw = max((1.0 - ri - x[i]) / ux, 0.0)
                        if (tw < bt) { bt = tw; type = 1; bi = i; axis = 0 }
                    } else if (ux < 0) {
                        val tw = max((ri - x[i]) / ux, 0.0)
                        if (tw < bt) { bt = tw; type = 1; bi = i; axis = 0 }
                    }
                    if (uy > 0) {
                        val tw = max((1.0 - ri - y[i]) / uy, 0.0)
                        if (tw < bt) { bt = tw; type = 1; bi = i; axis = 1 }
                    } else if (uy < 0) {
                        val tw = max((ri - y[i]) / uy, 0.0)
                        if (tw < bt) { bt = tw; type = 1; bi = i; axis = 1 }
                    }
                    for (k in 0 until 4) {
                        val tp = hitTime(P.pocketCx[k] - x[i], P.pocketCy[k] - y[i], ux, uy, P.pocketCap)
                        if (tp >= 0 && tp < bt) { bt = tp; type = 2; bi = i; bj = k }
                    }
                    for (j in 0 until n) {
                        if (j == i || !alive[j]) continue
                        if (j < i && (vx[j] != 0.0 || vy[j] != 0.0)) continue
                        val tj = hitTime(x[j] - x[i], y[j] - y[i], ux - vx[j], uy - vy[j], ri + r[j])
                        if (tj >= 0 && tj < bt) { bt = tj; type = 3; bi = i; bj = j }
                    }
                }
                if (bt > 0) {
                    for (m in 0 until n) if (alive[m] && (vx[m] != 0.0 || vy[m] != 0.0)) {
                        x[m] += vx[m] * bt; y[m] += vy[m] * bt
                    }
                }
                rem -= bt
                if (type == 0) break
                when (type) {
                    1 -> {
                        if (bi == 0 && o.firstHit < 0) o.wallBeforeHit = true
                        if (axis == 0) {
                            x[bi] = if (vx[bi] > 0) 1.0 - r[bi] else r[bi]
                            vx[bi] = -vx[bi] * P.wallRest; vy[bi] *= WALL_TANGENT
                        } else {
                            y[bi] = if (vy[bi] > 0) 1.0 - r[bi] else r[bi]
                            vy[bi] = -vy[bi] * P.wallRest; vx[bi] *= WALL_TANGENT
                        }
                        if (hypot(vx[bi], vy[bi]) < V_STOP) { vx[bi] = 0.0; vy[bi] = 0.0 }
                        if (record) addPoint(o, bi)
                    }
                    2 -> {
                        alive[bi] = false; vx[bi] = 0.0; vy[bi] = 0.0
                        if (bi == 0) o.strikerPotted = true else o.potted[bi] = true
                        x[bi] = P.pocketCx[bj]; y[bi] = P.pocketCy[bj]
                        if (record) addPoint(o, bi)
                    }
                    3 -> {
                        collide(bi, bj, o)
                        if (record) { addPoint(o, bi); addPoint(o, bj) }
                    }
                }
            }
            // constant deceleration
            for (i in 0 until n) {
                if (!alive[i]) continue
                val s = hypot(vx[i], vy[i])
                if (s == 0.0) continue
                val ns = s - DT
                if (ns <= V_STOP) { vx[i] = 0.0; vy[i] = 0.0 } else { val f = ns / s; vx[i] *= f; vy[i] *= f }
            }
        }
        if (record) for (i in 0 until n) if (alive[i]) addPoint(o, i)
    }

    fun finalX(i: Int) = x[i]
    fun finalY(i: Int) = y[i]

    private fun addPoint(o: Outcome, i: Int) {
        val l = o.paths!![i]
        val px = x[i].toFloat(); val py = y[i].toFloat()
        if (l.size >= 2 && l[l.size - 2] == px && l[l.size - 1] == py) return
        l.add(px); l.add(py)
    }

    private fun collide(i: Int, j: Int, o: Outcome) {
        var nx = x[j] - x[i]; var ny = y[j] - y[i]
        val d = hypot(nx, ny)
        if (d < 1e-12) return
        nx /= d; ny /= d
        val vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
        if (vn <= 0) return
        val mi = mass[i]; val mj = mass[j]
        val jj = (1 + P.coinRest) * vn / (1 / mi + 1 / mj)
        vx[i] -= jj / mi * nx; vy[i] -= jj / mi * ny
        vx[j] += jj / mj * nx; vy[j] += jj / mj * ny
        if ((i == 0 || j == 0) && o.firstHit < 0) {
            o.firstHit = if (i == 0) j else i
            o.contactX = x[0]; o.contactY = y[0]
        }
    }

    companion object {
        const val DT = 1.0 / 90.0
        const val MAX_STEPS = 90 * 14
        const val V_STOP = 0.02
        const val WALL_TANGENT = 0.99

        /** First tau >= 0 where |d - w*tau| == R while closing; -1 if never. */
        fun hitTime(dx: Double, dy: Double, wx: Double, wy: Double, rr: Double): Double {
            val a = wx * wx + wy * wy
            if (a < 1e-18) return -1.0
            val b = dx * wx + dy * wy
            if (b <= 0) return -1.0
            val c = dx * dx + dy * dy - rr * rr
            if (c <= 0) return 0.0
            val disc = b * b - a * c
            if (disc < 0) return -1.0
            return (b - sqrt(disc)) / a
        }
    }
}
