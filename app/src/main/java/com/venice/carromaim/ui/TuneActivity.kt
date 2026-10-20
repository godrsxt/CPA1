package com.venice.carromaim.ui

import android.app.Activity
import android.os.Bundle
import com.venice.carromaim.core.BoardProfile
import com.venice.carromaim.core.ProfileStore

/** Manual numbers for the active board profile. Everything is saved the moment you move a slider. */
class TuneActivity : Activity() {
    private lateinit var p: BoardProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = ProfileStore.active(this)
        val (sv, col) = Ui.page(this)
        setContentView(sv)
        col.addView(Ui.text(this, "Sizes, power & physics", 24f, Ui.ACCENT, true))
        col.addView(Ui.text(this, "Profile: ${p.name}.  All sizes are fractions of the board side (board = 1.0). The calibration screen already measured these for you; fix them here only if the Test view showed a wrong circle. After changing, tap Rescan on the floating bar.", 13f, Ui.DIM))

        fun sec(t: String) = Ui.add(col, Ui.text(this, t, 12f, Ui.ACCENT, true), 28f, this)
        fun sl(label: String, hint: String, min: Float, max: Float, get: () -> Float, set: (Float) -> Unit, dec: Int = 3) =
            Ui.slider(this, col, label, hint, min, max, get, { v -> set(v); ProfileStore.save(this, p) }, dec)

        sec("SIZES")
        sl("Coin radius", "Radius of one coin. Detection and the physics both use it.", 0.012f, 0.040f, { p.coinR }, { p.coinR = it }, 4)
        sl("Striker radius", "Usually a bit bigger than a coin. Sets the ghost circle.", 0.015f, 0.050f, { p.strikerR }, { p.strikerR = it }, 4)
        sl("Pocket radius", "Visible opening of a pocket.", 0.015f, 0.060f, { p.pocketR }, { p.pocketR = it }, 4)
        sl("Pocket centre inset", "Distance of the pocket centre from the board corner (each axis).", 0.005f, 0.080f, { p.pocketInset }, { p.pocketInset = it }, 4)
        sl("Pocket forgiveness", "1.00 = a coin drops when its centre is inside the opening. Lower = stricter, higher = coins drop easier.", 0.60f, 1.30f, { p.pocketCapture }, { p.pocketCapture = it }, 2)

        sec("STRIKER")
        sl("Baseline height", "Vertical position of your striker line (0 = top, 1 = bottom).", 0.70f, 0.95f, { p.baselineY }, { p.baselineY = it }, 3)
        sl("Slide range: left end", "Leftmost spot the striker can slide to.", 0.05f, 0.50f, { p.slideMin }, { p.slideMin = it }, 3)
        sl("Slide range: right end", "Rightmost spot the striker can slide to.", 0.50f, 0.95f, { p.slideMax }, { p.slideMax = it }, 3)

        sec("POWER")
        sl("Pull-back for full power", "How far you drag back to reach max power in the game. Only the white dashed guide uses this.", 0.05f, 0.50f, { p.maxDrag }, { p.maxDrag = it }, 3)
        sl("Shot power used", "Leave at 1.00 for full power.", 0.30f, 1.00f, { p.power }, { p.power = it }, 2)

        sec("PHYSICS (affects bank shots and what happens after contact)")
        sl("Roll length at full power", "How many board lengths the striker would roll on an empty board before stopping. Bigger = faster striker.", 1.5f, 12f, { p.rollLen }, { p.rollLen = it }, 1)
        sl("Cushion bounce", "Speed kept after hitting the wall. 1.00 = perfectly bouncy.", 0.40f, 1.00f, { p.wallRest }, { p.wallRest = it }, 2)
        sl("Coin bounce", "How lively coin-to-coin hits are.", 0.60f, 1.00f, { p.coinRest }, { p.coinRest = it }, 2)
        sl("Striker weight", "Striker mass compared with a coin. Heavier = coins fly faster off a hit.", 0.8f, 3.0f, { p.strikerMass }, { p.strikerMass = it }, 2)

        sec("DETECTION")
        sl("Colour tolerance", "Higher finds more coins but can also pick up board markings. Use the Test step in calibration to judge.", 0.4f, 2.5f, { p.tol }, { p.tol = it }, 2)

        Ui.add(col, Ui.button(this, "Done") { finish() }, 28f, this)
    }

    override fun onPause() {
        super.onPause()
        ProfileStore.save(this, p)
    }
}
