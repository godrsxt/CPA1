package com.venice.carromaim.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.venice.carromaim.core.AimResult
import com.venice.carromaim.core.BoardProfile
import com.venice.carromaim.core.Engine
import com.venice.carromaim.core.EngineListener
import com.venice.carromaim.core.Homography
import com.venice.carromaim.core.Kind
import com.venice.carromaim.core.PixelSource
import com.venice.carromaim.core.ProfileStore
import com.venice.carromaim.core.Shot
import com.venice.carromaim.ui.CalibrationActivity
import com.venice.carromaim.ui.OverlayView
import java.io.File
import java.io.FileOutputStream

/** One captured screen frame, readable straight from the RGBA buffer. */
class RawFrame(override val width: Int, override val height: Int, val rowStride: Int) : PixelSource {
    val data = ByteArray(rowStride * height)
    override fun rgb(x: Int, y: Int): Int {
        val o = y * rowStride + x * 4
        return ((data[o].toInt() and 255) shl 16) or ((data[o + 1].toInt() and 255) shl 8) or (data[o + 2].toInt() and 255)
    }
}

class CaptureService : Service(), EngineListener {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "carrom_aim"
        @Volatile var running = false
        fun framePath(c: Context): File = File(c.filesDir, "frame.png")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var frame: RawFrame? = null
    private var lastProc = 0L
    @Volatile private var lastFrameReady = false

    private var overlay: OverlayView? = null
    private var bar: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var hideBtn: Button? = null

    private var engine: Engine? = null
    private var prof: BoardProfile? = null
    private var screenW = 0
    private var screenH = 0
    private var overlayHidden = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        if (intent == null || !intent.hasExtra(EXTRA_DATA) || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startCapture(code, data)
            buildWindows()
            running = true
            reloadProfile()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Carrom Aim", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Carrom Aim is running")
            .setContentText("Aim lines are shown over the game")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun startCapture(code: Int, data: Intent) {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels; screenH = dm.heightPixels
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        val t = HandlerThread("aim-frames").also { it.start() }
        thread = t
        val hnd = Handler(t.looper)
        handler = hnd
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, hnd)
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = proj.createVirtualDisplay(
            "aim", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, hnd
        )
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, hnd)
    }

    private fun onImage(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val plane = img.planes[0]
            var f = frame
            if (f == null || f.width != img.width || f.height != img.height || f.rowStride != plane.rowStride) {
                f = RawFrame(img.width, img.height, plane.rowStride)
                frame = f
            }
            // keep the newest pixels around for the "Setup" screenshot, but only analyse ~7 times a second
            val buf = plane.buffer
            buf.rewind()
            buf.get(f.data, 0, Math.min(buf.remaining(), f.data.size))
            lastFrameReady = true
            if (now - lastProc < 140) return
            lastProc = now
            engine?.onFrame(f)
        } catch (e: Exception) {
            // ignore a bad frame
        } finally {
            img.close()
        }
    }

    // ------------------------------------------------------------------ windows

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun buildWindows() {
        val ov = OverlayView(this)
        overlay = ov
        val op = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) op.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        op.gravity = Gravity.TOP or Gravity.START
        wm.addView(ov, op)

        val b = LinearLayout(this)
        b.orientation = LinearLayout.VERTICAL
        b.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(225, 18, 22, 28)); bg.cornerRadius = dp(14f).toFloat()
        b.background = bg
        val st = TextView(this)
        st.setTextColor(Color.WHITE); st.textSize = 13f; st.typeface = Typeface.DEFAULT_BOLD
        st.text = "Carrom Aim  (drag me)"
        st.setPadding(dp(4f), dp(2f), dp(4f), dp(6f))
        status = st
        b.addView(st)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun btn(label: String, onClick: () -> Unit): Button {
            val x = Button(this)
            x.text = label; x.textSize = 12f; x.isAllCaps = false
            x.minWidth = 0; x.minimumWidth = 0
            x.setPadding(dp(10f), 0, dp(10f), 0)
            x.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38f))
            lp.marginEnd = dp(4f)
            row.addView(x, lp)
            return x
        }
        btn("Next shot") { overlay?.let { it.select(it.selected + 1); describe() } }
        hideBtn = btn("Hide") { toggleHide() }
        btn("Rescan") { reloadProfile() }
        btn("Setup") { openSetup() }
        btn("Stop") { stopSelf() }
        b.addView(row)
        bar = b

        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = dp(8f); bp.y = dp(40f)
        barParams = bp
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
        st.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = bp.x; sy = bp.y }
                MotionEvent.ACTION_MOVE -> {
                    bp.x = sx + (e.rawX - dx).toInt(); bp.y = sy + (e.rawY - dy).toInt()
                    wm.updateViewLayout(b, bp)
                }
            }
            true
        }
        wm.addView(b, bp)
    }

    private fun toggleHide() {
        overlayHidden = !overlayHidden
        overlay?.visibility = if (overlayHidden) View.INVISIBLE else View.VISIBLE
        hideBtn?.text = if (overlayHidden) "Show" else "Hide"
    }

    /** (Re)load the active profile and restart scanning with it. */
    private fun reloadProfile() {
        engine?.shutdown(); engine = null
        overlay?.setResult(null)
        val p = ProfileStore.active(this)
        prof = p
        val h = p.boardToScreen()
        if (h == null) {
            setStatus("Profile “${p.name}” is not calibrated. Tap Setup.")
            return
        }
        if (p.screenW != 0 && (p.screenW != screenW || p.screenH != screenH)) {
            setStatus("Screen size changed since calibration. Tap Setup.")
        }
        overlay?.setBoard(h, p)
        engine = Engine(p, h, this)
        setStatus("“${p.name}” – scanning…")
    }

    private fun openSetup() {
        val f = frame
        if (f == null || !lastFrameReady) { setStatus("No frame yet – wait a second"); return }
        try {
            val bmp = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
            val row = IntArray(f.width)
            for (y in 0 until f.height) {
                for (x in 0 until f.width) row[x] = 0xFF000000.toInt() or f.rgb(x, y)
                bmp.setPixels(row, 0, f.width, 0, y, f.width, 1)
            }
            FileOutputStream(framePath(this)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val i = Intent(this, CalibrationActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            setStatus("Setup failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ engine callbacks

    private fun setStatus(t: String) { main.post { status?.text = t } }

    override fun onStatus(text: String) {
        main.post {
            if (overlay?.items().isNullOrEmpty()) status?.text = text
        }
    }

    override fun onResult(result: AimResult?) {
        main.post {
            overlay?.setResult(result)
            if (result == null) return@post
            if (result.here.isEmpty() && result.spot == null) status?.text = "No shot found"
            else describe()
        }
    }

    private fun describe() {
        val ov = overlay ?: return
        val list = ov.items()
        if (list.isEmpty()) return
        val i = ov.selected
        val s: Shot = list[i]
        val isSpot = i == list.size - 1 && s.strikerX != list[0].strikerX
        val what = StringBuilder()
        if (s.pottedKinds.isEmpty()) what.append("no pot") else {
            val q = s.pottedKinds.count { it == Kind.QUEEN }
            val w = s.pottedKinds.count { it == Kind.WHITE }
            val bl = s.pottedKinds.count { it == Kind.BLACK }
            if (q > 0) what.append("Q ")
            if (w > 0) what.append("${w}W ")
            if (bl > 0) what.append("${bl}B ")
            what.append("= ${s.points} pts")
        }
        val tol = String.format("±%.2f°", s.halfMarginDeg)
        val head = if (isSpot) "Move striker to the red ring" else "Shot ${i + 1}/${list.size}"
        status?.text = "$head: $what  $tol"
    }

    override fun onDestroy() {
        running = false
        engine?.shutdown()
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { bar?.let { wm.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
