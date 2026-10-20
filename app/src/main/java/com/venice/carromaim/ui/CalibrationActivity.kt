package com.venice.carromaim.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.venice.carromaim.capture.CaptureService
import com.venice.carromaim.core.BoardProfile
import com.venice.carromaim.core.Detector
import com.venice.carromaim.core.Found
import com.venice.carromaim.core.Homography
import com.venice.carromaim.core.PixelSource
import com.venice.carromaim.core.ProfileStore
import kotlin.math.abs
import kotlin.math.min

private class BitmapPixels(override val width: Int, override val height: Int, val px: IntArray) : PixelSource {
    override fun rgb(x: Int, y: Int): Int = px[y * width + x] and 0xFFFFFF
}

/**
 * Guided set-up on a frozen screenshot: board corners, then one ring around a white coin, black coin,
 * queen, striker and a pocket (this measures every size and samples every colour), then a live test.
 */
class CalibrationActivity : Activity() {
    private class StepDef(val title: String, val help: String, val mode: CalView.Mode, val optional: Boolean = false)

    private val steps = listOf(
        StepDef("Board corners", "Drag the 4 green handles onto the INNER corners of the playing surface (where the wooden frame ends and the board begins). The white grid should follow the board's own lines.", CalView.Mode.CORNERS),
        StepDef("White coin", "Move the ring onto one WHITE coin. Drag from the middle to move it, drag the green dot to resize it until it hugs the coin's edge.", CalView.Mode.RING),
        StepDef("Black coin", "Same for one BLACK coin.", CalView.Mode.RING),
        StepDef("Red queen", "Same for the red QUEEN. Skip if it is not on the board.", CalView.Mode.RING, true),
        StepDef("Your striker", "Same for YOUR STRIKER sitting on your baseline.", CalView.Mode.RING),
        StepDef("A pocket", "Fit the ring to the visible opening of one pocket (the dark hole).", CalView.Mode.RING),
        StepDef("Board colour", "Tap an empty, plain spot of the board surface (not a line or a marking).", CalView.Mode.POINT),
        StepDef("Test", "Check the detection. Rings must sit on every coin. Count at the start of a match: 9 white, 9 black, 1 queen, 1 striker.", CalView.Mode.CORNERS)
    )
    private val TEST = 7

    private lateinit var bmp: Bitmap
    private lateinit var view: CalView
    private lateinit var prof: BoardProfile
    private lateinit var title: TextView
    private lateinit var help: TextView
    private lateinit var back: Button
    private lateinit var skip: Button
    private lateinit var next: Button
    private lateinit var ringRow: LinearLayout
    private lateinit var testBox: LinearLayout
    private lateinit var counts: TextView
    private var step = 0
    private val rings = arrayOfNulls<FloatArray>(8)
    private var surface: FloatArray? = null
    private var pixels: IntArray? = null
    private var temp: BoardProfile? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = BitmapFactory.decodeFile(CaptureService.framePath(this).absolutePath)
        if (b == null) { Toast.makeText(this, "No screenshot found", Toast.LENGTH_LONG).show(); finish(); return }
        bmp = b
        prof = ProfileStore.active(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        view = CalView(this, bmp)
        if (prof.calibrated && prof.screenW == bmp.width && prof.screenH == bmp.height) {
            for (i in 0 until 8) view.corners[i] = prof.corners[i]
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 12f)
        panel.setPadding(p, p, p, p)
        panel.setBackgroundColor(Ui.PANEL)
        title = Ui.text(this, "", 17f, Ui.ACCENT, true)
        help = Ui.text(this, "", 13f, Ui.TEXT)
        panel.addView(title)
        panel.addView(help)

        ringRow = LinearLayout(this); ringRow.orientation = LinearLayout.HORIZONTAL
        ringRow.addView(Ui.button(this, "Ring smaller") { view.nudgeRadius(-0.5f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ringRow.addView(Ui.button(this, "Ring bigger") { view.nudgeRadius(0.5f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        Ui.add(panel, ringRow, 6f, this)

        testBox = LinearLayout(this); testBox.orientation = LinearLayout.VERTICAL
        counts = Ui.text(this, "", 14f, Ui.TEXT, true)
        testBox.addView(counts)
        val tolLabel = Ui.text(this, "Colour tolerance (more coins ↔ fewer false ones)", 12f, Ui.DIM)
        testBox.addView(tolLabel)
        val sb = SeekBar(this)
        sb.max = 100
        sb.progress = (((prof.tol - 0.4f) / 2.1f) * 100f).toInt().coerceIn(0, 100)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                temp?.tol = 0.4f + 2.1f * (s?.progress ?: 50) / 100f
                runTest()
            }
        })
        testBox.addView(sb)
        val sizeRow = LinearLayout(this); sizeRow.orientation = LinearLayout.HORIZONTAL
        sizeRow.addView(Ui.button(this, "Coin size −") { temp?.let { it.coinR *= 0.98f }; runTest() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeRow.addView(Ui.button(this, "Coin size +") { temp?.let { it.coinR *= 1.02f }; runTest() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        testBox.addView(sizeRow)
        Ui.add(panel, testBox, 6f, this)

        val nav = LinearLayout(this); nav.orientation = LinearLayout.HORIZONTAL
        back = Ui.button(this, "Back") { go(step - 1) }
        skip = Ui.button(this, "Skip") { go(step + 1) }
        next = Ui.button(this, "Next", true) { onNext() }
        for (bt in listOf(back, skip, next)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = Ui.dp(this, 6f)
            nav.addView(bt, lp)
        }
        Ui.add(panel, nav, 8f, this)
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        enter(0)
    }

    private fun defaultRadiusPx(s: Int): Float {
        val side = view.boardSide()
        return when (s) {
            4 -> prof.strikerR * side
            5 -> prof.pocketR * side
            else -> prof.coinR * side
        }
    }

    private fun enter(s: Int) {
        step = s
        val d = steps[s]
        title.text = "Step ${s + 1} of ${steps.size}:  ${d.title}"
        help.text = d.help
        view.mode = if (s == TEST) CalView.Mode.TEST else d.mode
        ringRow.visibility = if (d.mode == CalView.Mode.RING && s != TEST) View.VISIBLE else View.GONE
        testBox.visibility = if (s == TEST) View.VISIBLE else View.GONE
        counts.text = ""
        back.visibility = if (s == 0) View.INVISIBLE else View.VISIBLE
        skip.visibility = if (d.optional) View.VISIBLE else View.GONE
        next.text = if (s == TEST) "Save profile" else "Next"
        if (d.mode == CalView.Mode.RING && s != TEST) {
            val saved = rings[s]
            if (saved != null) { for (i in 0..2) view.ring[i] = saved[i] }
            else {
                val h = Homography.boardToScreen(view.corners)
                val cx: Double; val cy: Double
                when (s) {
                    4 -> { cx = 0.5; cy = prof.baselineY.toDouble() }
                    5 -> { cx = 0.04; cy = 0.04 }
                    else -> { cx = 0.5; cy = 0.5 }
                }
                view.ring[0] = (h?.mapX(cx, cy) ?: (bmp.width / 2.0)).toFloat()
                view.ring[1] = (h?.mapY(cx, cy) ?: (bmp.height / 2.0)).toFloat()
                view.ring[2] = defaultRadiusPx(s)
            }
        }
        if (d.mode == CalView.Mode.POINT) {
            val sp = surface
            if (sp != null) { view.point[0] = sp[0]; view.point[1] = sp[1]; view.hasPoint = true }
        }
        if (s == TEST) buildTemp()
        view.invalidate()
    }

    private fun go(s: Int) { if (s in 0..TEST) enter(s) }

    private fun onNext() {
        val d = steps[step]
        when {
            step == 0 -> {
                if (Homography.boardToScreen(view.corners) == null) { toast("The corners make no valid square. Move them."); return }
                if (view.boardSide() < bmp.width * 0.4f) { toast("Board looks too small. Place the handles on the board corners."); return }
            }
            step == TEST -> { save(); return }
            d.mode == CalView.Mode.RING -> rings[step] = view.ring.copyOf()
            d.mode == CalView.Mode.POINT -> {
                if (!view.hasPoint) { toast("Tap a spot on the board first"); return }
                surface = view.point.copyOf()
            }
        }
        go(step + 1)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun sampleMedian(cx: Float, cy: Float, rad: Float): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        val r = Math.max(2f, rad)
        val x0 = Math.max(0, (cx - r).toInt()); val x1 = Math.min(bmp.width - 1, (cx + r).toInt())
        val y0 = Math.max(0, (cy - r).toInt()); val y1 = Math.min(bmp.height - 1, (cy + r).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy > r * r) continue
            val c = bmp.getPixel(x, y)
            rs.add(Color.red(c)); gs.add(Color.green(c)); bs.add(Color.blue(c))
        }
        if (rs.isEmpty()) return 0
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    /** Turn everything marked so far into a profile (not saved yet). */
    private fun buildTemp() {
        val t = prof.copyAs(prof.name)
        t.corners = view.corners.copyOf()
        t.screenW = bmp.width; t.screenH = bmp.height
        val h = Homography.boardToScreen(t.corners) ?: return
        val inv = h.inverse()
        val side = t.boardSidePx().toFloat()
        val coinRs = ArrayList<Float>()
        rings[1]?.let { r -> t.colors[1] = sampleMedian(r[0], r[1], r[2] * 0.5f); coinRs.add(r[2] / side) }
        rings[2]?.let { r -> t.colors[2] = sampleMedian(r[0], r[1], r[2] * 0.5f); coinRs.add(r[2] / side) }
        rings[3]?.let { r -> t.colors[3] = sampleMedian(r[0], r[1], r[2] * 0.5f) }
        rings[4]?.let { r ->
            t.colors[4] = sampleMedian(r[0], r[1], r[2] * 0.5f)
            t.strikerR = r[2] / side
            val by = inv.mapY(r[0].toDouble(), r[1].toDouble()).toFloat()
            if (by in 0.6f..0.97f) t.baselineY = by
        }
        rings[5]?.let { r ->
            val px = inv.mapX(r[0].toDouble(), r[1].toDouble())
            val py = inv.mapY(r[0].toDouble(), r[1].toDouble())
            val ix = min(px, 1 - px); val iy = min(py, 1 - py)
            t.pocketInset = ((ix + iy) / 2).toFloat().coerceIn(0.005f, 0.09f)
            t.pocketR = (r[2] / side).coerceIn(0.012f, 0.07f)
        }
        surface?.let { t.colors[0] = sampleMedian(it[0], it[1], 4f) }
        if (coinRs.isNotEmpty()) t.coinR = coinRs.average().toFloat()
        temp = t
        if (pixels == null) {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            pixels = px
        }
        if (t.colors[0] == 0 || t.colors[1] == 0 || t.colors[2] == 0 || t.colors[4] == 0) {
            counts.text = "Missing: ${listOfNotNull(if (t.colors[0] == 0) "board colour" else null, if (t.colors[1] == 0) "white coin" else null, if (t.colors[2] == 0) "black coin" else null, if (t.colors[4] == 0) "striker" else null).joinToString(", ")}. Go Back and mark them."
        }
        runTest()
    }

    private fun runTest() {
        val t = temp ?: return
        val px = pixels ?: return
        if (t.colors[0] == 0 || t.colors[1] == 0 || t.colors[2] == 0 || t.colors[4] == 0) return
        if (busy) return
        busy = true
        counts.text = "Detecting…"
        val h = t.boardToScreen() ?: run { busy = false; return }
        Thread {
            val src = BitmapPixels(bmp.width, bmp.height, px)
            val det = Detector.detect(src, t, h)
            runOnUiThread {
                busy = false
                val all: List<Found> = det.all
                view.found = all
                view.testHom = h
                val side = t.boardSidePx().toFloat()
                view.coinPx = t.coinR * side
                view.strikerPx = t.strikerR * side
                view.pocketPx = t.pocketR * side
                val pc = t.phys()
                view.pocketCentres = (0 until 4).map { k -> floatArrayOf(h.mapX(pc.pocketCx[k], pc.pocketCy[k]).toFloat(), h.mapY(pc.pocketCx[k], pc.pocketCy[k]).toFloat()) }
                val w = all.count { it.kind == 1 }; val bl = all.count { it.kind == 2 }
                val q = all.count { it.kind == 3 }; val s = all.count { it.kind == 4 }
                counts.text = "White $w · Black $bl · Queen $q · Striker $s"
                view.invalidate()
            }
        }.start()
    }

    private fun save() {
        val t = temp ?: return
        if (t.colors[0] == 0 || t.colors[1] == 0 || t.colors[2] == 0 || t.colors[4] == 0) { toast("Mark board colour, white coin, black coin and striker first"); return }
        ProfileStore.save(this, t)
        ProfileStore.setActive(this, t.name)
        Toast.makeText(this, "Saved “${t.name}”. Tap Rescan on the floating bar.", Toast.LENGTH_LONG).show()
        finish()
    }
}
