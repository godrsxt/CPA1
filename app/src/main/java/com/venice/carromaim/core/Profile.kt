package com.venice.carromaim.core

/**
 * Everything that differs from one board / striker / skin to the next.
 * All lengths are in BOARD UNITS: the playing square has side 1.0, origin top-left,
 * x to the right, y downward (your baseline is near y = 0.85).
 */
class BoardProfile(var name: String) {
    /** 8 floats: screen px of playfield TL, TR, BR, BL. Empty = not calibrated. */
    var corners: FloatArray = FloatArray(0)
    var screenW = 0
    var screenH = 0

    var coinR = 0.0215f
    var strikerR = 0.0280f
    var pocketR = 0.0300f
    var pocketInset = 0.0330f
    /** Pocket centres in board units (TL, TR, BR, BL). Marked one by one during calibration. */
    var pocketX = FloatArray(4)
    var pocketY = FloatArray(4)
    /** Printed circles that look like coins, as triples (x, y, 0xRRGGBB of the middle). Board units. */
    var ignore = FloatArray(0)
    /** A coin drops when its centre comes within pocketR * pocketCapture of the pocket centre. */
    var pocketCapture = 1.00f

    var baselineY = 0.855f
    var slideMin = 0.16f
    var slideMax = 0.84f

    /** surface, white, black, queen, striker as 0xRRGGBB; 0 = not set. */
    var colors = IntArray(5)
    var tol = 1.0f

    var wallRest = 0.80f
    var coinRest = 0.95f
    var strikerMass = 1.6f
    /** Board lengths the striker rolls before stopping when shot at full power. */
    var rollLen = 6.0f
    var power = 1.0f
    /** Pull-back distance (board units) that gives full power in the game. */
    var maxDrag = 0.20f

    init { resetPockets() }

    fun resetPockets() {
        pocketX = floatArrayOf(pocketInset, 1 - pocketInset, 1 - pocketInset, pocketInset)
        pocketY = floatArrayOf(pocketInset, pocketInset, 1 - pocketInset, 1 - pocketInset)
    }

    val calibrated: Boolean get() = corners.size == 8

    fun copyAs(newName: String): BoardProfile {
        val p = BoardProfile(newName)
        p.corners = corners.copyOf(); p.screenW = screenW; p.screenH = screenH
        p.coinR = coinR; p.strikerR = strikerR; p.pocketR = pocketR; p.pocketInset = pocketInset
        p.pocketCapture = pocketCapture; p.baselineY = baselineY; p.slideMin = slideMin; p.slideMax = slideMax
        p.colors = colors.copyOf(); p.tol = tol
        p.pocketX = pocketX.copyOf(); p.pocketY = pocketY.copyOf(); p.ignore = ignore.copyOf()
        p.wallRest = wallRest; p.coinRest = coinRest; p.strikerMass = strikerMass
        p.rollLen = rollLen; p.power = power; p.maxDrag = maxDrag
        return p
    }

    fun boardToScreen(): Homography? = if (calibrated) Homography.boardToScreen(corners) else null

    /** Average side length of the calibrated board quad in screen px. */
    fun boardSidePx(): Double {
        if (!calibrated) return 1.0
        fun d(a: Int, b: Int) = Math.hypot((corners[2 * a] - corners[2 * b]).toDouble(), (corners[2 * a + 1] - corners[2 * b + 1]).toDouble())
        return (d(0, 1) + d(1, 2) + d(2, 3) + d(3, 0)) / 4.0
    }

    fun phys(): PhysParams {
        val cx = DoubleArray(4) { pocketX[it].toDouble() }
        val cy = DoubleArray(4) { pocketY[it].toDouble() }
        val l = rollLen.toDouble()
        return PhysParams(
            wallRest = wallRest.toDouble(), coinRest = coinRest.toDouble(),
            strikerMass = strikerMass.toDouble(),
            v0 = Math.sqrt(2.0 * l),
            pocketCx = cx, pocketCy = cy,
            pocketCap = pocketR.toDouble() * pocketCapture
        )
    }
}
