package com.venice.carromaim.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/** What the UI shows. */
class AimResult(val here: List<Shot>, val spot: Shot?, val striker: Found)

interface EngineListener {
    fun onStatus(text: String)
    /** Called from any thread. null = clear the overlay. */
    fun onResult(result: AimResult?)
}

/**
 * scanning (overlay hidden, clean frames) -> stable board + striker found -> solving -> showing.
 * While showing, only the striker's presence is watched; if it leaves, scan again.
 */
class Engine(private val prof: BoardProfile, private val h: Homography, private val listener: EngineListener) {
    private enum class Mode { SCANNING, SOLVING, SHOWING }

    @Volatile private var mode = Mode.SCANNING
    @Volatile private var gen = 0
    private var last: Detection? = null
    private var stable = 0
    private var shownStriker: Found? = null
    private var missCount = 0
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    private val solver = Solver(prof)

    fun forceRescan() {
        gen++
        mode = Mode.SCANNING; last = null; stable = 0; missCount = 0
        listener.onResult(null)
    }

    fun shutdown() { gen++; exec.shutdownNow() }

    fun onFrame(src: PixelSource) {
        when (mode) {
            Mode.SOLVING -> return
            Mode.SHOWING -> {
                val s = shownStriker ?: return
                if (Detector.strikerPresent(src, prof, h, s.x, s.y)) { missCount = 0; return }
                if (++missCount >= 2) {
                    forceRescan()
                    listener.onStatus("Scanning…")
                }
            }
            Mode.SCANNING -> {
                val d = Detector.detect(src, prof, h)
                val st = d.striker
                if (st == null) {
                    stable = 0; last = null
                    listener.onStatus("Waiting for your striker…")
                    return
                }
                stable = if (same(last, d)) stable + 1 else 0
                last = d
                if (stable >= 2) startSolve(d, st)
                else listener.onStatus("Reading board… ${d.coins.size} coins")
            }
        }
    }

    private fun same(a: Detection?, b: Detection): Boolean {
        if (a == null || a.coins.size != b.coins.size) return false
        val sa = a.striker ?: return false
        val sb = b.striker ?: return false
        if (hypot(sa.x - sb.x, sa.y - sb.y) > 0.004) return false
        for (c in b.coins) {
            if (a.coins.none { it.kind == c.kind && hypot(it.x - c.x, it.y - c.y) < 0.004 }) return false
        }
        return true
    }

    private fun startSolve(d: Detection, st: Found) {
        mode = Mode.SOLVING
        val myGen = ++gen
        listener.onStatus("Calculating…")
        val coins = d.coins.map {
            DiscState(it.x, it.y, prof.coinR.toDouble(), it.kind)
        }
        exec.submit {
            try {
                val cancel = { gen != myGen }
                val res = solver.solve(coins, st.x, st.y, true, cancel) { here ->
                    if (gen == myGen) {
                        shownStriker = st; missCount = 0
                        mode = Mode.SHOWING
                        listener.onResult(AimResult(here, null, st))
                    }
                }
                if (gen == myGen) {
                    shownStriker = st; missCount = 0
                    mode = Mode.SHOWING
                    listener.onResult(AimResult(res.here, res.spot, st))
                }
            } catch (e: Throwable) {
                if (gen == myGen) { mode = Mode.SCANNING; listener.onStatus("Solver error: ${e.javaClass.simpleName}") }
            }
        }
    }
}
