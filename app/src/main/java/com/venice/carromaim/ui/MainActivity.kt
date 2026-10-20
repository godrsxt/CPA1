package com.venice.carromaim.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.venice.carromaim.capture.CaptureService
import com.venice.carromaim.core.ProfileStore
import java.io.FileOutputStream

class MainActivity : Activity() {
    private val reqCapture = 11
    private val reqPick = 12
    private lateinit var spinner: Spinner
    private lateinit var info: TextView
    private lateinit var perm: TextView
    private var names: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (sv, col) = Ui.page(this)
        setContentView(sv)

        col.addView(Ui.text(this, "Carrom Aim", 28f, Ui.ACCENT, true))
        col.addView(Ui.text(this, "Freestyle aim lines at full power. Shows where to aim; you shoot yourself.", 14f, Ui.DIM))

        Ui.add(col, Ui.text(this, "BOARD PROFILE", 12f, Ui.DIM, true), 24f, this)
        Ui.add(col, Ui.text(this, "Each board / skin has its own sizes, pockets, colours and power pull-back. Make one profile per board.", 12f, Ui.DIM), 2f, this)
        spinner = Spinner(this)
        Ui.add(col, spinner, 6f, this)
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun small(label: String, f: () -> Unit) {
            val b = Ui.button(this, label) { f() }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = Ui.dp(this, 6f)
            row.addView(b, lp)
        }
        small("New") { askName("New profile", "Board ${names.size + 1}") { n -> val u = ProfileStore.uniqueName(this, n); ProfileStore.save(this, com.venice.carromaim.core.BoardProfile(u)); ProfileStore.setActive(this, u); refresh(true) } }
        small("Copy") { askName("Copy as", ProfileStore.active(this).name + " copy") { n -> val u = ProfileStore.uniqueName(this, n); ProfileStore.save(this, ProfileStore.active(this).copyAs(u)); ProfileStore.setActive(this, u); refresh(true) } }
        small("Delete") { deleteActive() }
        Ui.add(col, row, 6f, this)

        Ui.add(col, Ui.text(this, "SET UP THIS BOARD", 12f, Ui.DIM, true), 24f, this)
        Ui.add(col, Ui.button(this, "1.  Calibrate from a screenshot") { pickScreenshot() }, 6f, this)
        Ui.add(col, Ui.text(this, "Take a normal screenshot of Carrom Pool (full board visible), then choose it here. You mark the corners, a coin, striker, pocket and the board colour. About 2 minutes, once per board.", 12f, Ui.DIM), 2f, this)
        Ui.add(col, Ui.button(this, "2.  Sizes, power and physics") { startActivity(Intent(this, TuneActivity::class.java)) }, 10f, this)
        Ui.add(col, Ui.text(this, "Fine-tune coin / striker / pocket sizes, the pull-back distance for full power, cushion bounce and roll length.", 12f, Ui.DIM), 2f, this)

        Ui.add(col, Ui.text(this, "PLAY", 12f, Ui.DIM, true), 24f, this)
        perm = Ui.text(this, "", 12f, Ui.DIM)
        Ui.add(col, Ui.button(this, "Start aim overlay", true) { start() }, 6f, this)
        Ui.add(col, perm, 4f, this)
        Ui.add(col, Ui.button(this, "Stop overlay") { stopService(Intent(this, CaptureService::class.java)) }, 8f, this)

        info = Ui.text(this, "", 12f, Ui.DIM)
        Ui.add(col, info, 20f, this)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos in names.indices && names[pos] != ProfileStore.activeName(this@MainActivity)) {
                    ProfileStore.setActive(this@MainActivity, names[pos]); refreshInfo()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        refresh(false)
    }

    private fun refresh(rebuild: Boolean) {
        val all = ProfileStore.all(this)
        names = all.map { it.name }
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.adapter = ad
        val idx = names.indexOf(ProfileStore.active(this).name)
        if (idx >= 0) spinner.setSelection(idx)
        refreshInfo()
    }

    private fun refreshInfo() {
        val p = ProfileStore.active(this)
        val cal = if (p.calibrated) "calibrated" else "NOT calibrated yet"
        info.text = "Active: ${p.name} ($cal)\nCoin r=${"%.4f".format(p.coinR)}  striker r=${"%.4f".format(p.strikerR)}  pocket r=${"%.4f".format(p.pocketR)}  (board = 1.0)"
        val ov = if (Settings.canDrawOverlays(this)) "overlay permission OK" else "needs 'display over other apps' permission (asked on start)"
        perm.text = ov
    }

    private fun askName(title: String, def: String, ok: (String) -> Unit) {
        val et = EditText(this)
        et.setText(def); et.selectAll()
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) ok(n) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteActive() {
        if (names.size <= 1) { Toast.makeText(this, "Keep at least one profile", Toast.LENGTH_SHORT).show(); return }
        val n = ProfileStore.active(this).name
        AlertDialog.Builder(this).setTitle("Delete “$n”?")
            .setPositiveButton("Delete") { _, _ -> ProfileStore.delete(this, n); refresh(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickScreenshot() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, reqPick)
    }

    private fun start() {
        val p = ProfileStore.active(this)
        if (!p.calibrated) { Toast.makeText(this, "Calibrate this profile first (step 1)", Toast.LENGTH_LONG).show(); return }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps”, then press Start again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra(CaptureService.EXTRA_CODE, resultCode)
            i.putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "Overlay started. Open Carrom Pool.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else if (requestCode == reqPick && resultCode == RESULT_OK && data?.data != null) {
            try {
                val bmp = contentResolver.openInputStream(data.data!!).use { BitmapFactory.decodeStream(it) }
                if (bmp == null) { Toast.makeText(this, "Could not read that image", Toast.LENGTH_LONG).show(); return }
                FileOutputStream(CaptureService.framePath(this)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                startActivity(Intent(this, CalibrationActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
