package com.autoclicker.floating

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickA11y"
        private const val MARKER_HALF_DP = 28f

        @Volatile var instance: ClickAccessibilityService? = null
        @Volatile var isRunning = false
        @Volatile var holdMs = 10L
        @Volatile var cps = 50
        @Volatile var points: MutableList<ClickPoint> = mutableListOf()
        @Volatile var wantPanelVisible = false
        @Volatile var userWantsClicking = false
        @Volatile var isRecording = false
        @Volatile var isPlayingRecord = false
        @Volatile var recording: MutableList<RecordedTap> = mutableListOf()

        fun startClicking() { userWantsClicking = true; instance?.startLoop() }
        fun stopClicking() { userWantsClicking = false; instance?.stopLoop() }
        fun showPanel() { wantPanelVisible = true; instance?.showControls() }
        fun hidePanel() { wantPanelVisible = false; instance?.hideAll() }
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var clickRunnable: Runnable? = null
    private var pointIndex = 0
    private var recordPlayIndex = 0

    private var windowManager: WindowManager? = null
    private var controlsView: View? = null
    private var captureView: View? = null
    private val pointViews = mutableListOf<View>()
    private val pointParamsList = mutableListOf<WindowManager.LayoutParams>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recording state
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastUpAt = 0L
    private val sessionTaps = mutableListOf<RecordedTap>()

    private fun halfPx(): Float = MARKER_HALF_DP * resources.displayMetrics.density

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        holdMs = Prefs.getHold(this).toLong()
        cps = Prefs.getCps(this)
        points = Prefs.getPoints(this)
        recording = Prefs.getRecording(this)
        if (wantPanelVisible) mainHandler.post { showControls() }
        if (userWantsClicking) mainHandler.post { startLoop() }
    }

    override fun onDestroy() {
        stopLoop()
        stopRecordingInternal()
        hideAll()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (wantPanelVisible && controlsView == null) mainHandler.post { showControls() }
        if (userWantsClicking && !isRunning && !isPlayingRecord) {
            mainHandler.postDelayed({ startLoop() }, 300)
        }
    }

    override fun onInterrupt() {
        // Do not stop the click loop — real finger touches often trigger interrupt.
        // Stopping here made finger and auto-click cancel each other.
        Log.w(TAG, "onInterrupt ignored (keep running if active)")
    }

    // ─── Normal multi-point loop ────────────────────────────────────

    private fun startLoop() {
        if (isRunning || isRecording || isPlayingRecord) return
        if (points.isEmpty()) points = Prefs.getPoints(this)
        if (points.isEmpty()) {
            toast("Add at least one point (+)")
            userWantsClicking = false
            return
        }
        // Never leave capture layer during normal click
        try { captureView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        captureView = null
        isRecording = false
        setMarkersTouchable(false)
        isRunning = true
        userWantsClicking = true
        pointIndex = 0
        ensureWorker()
        Log.i(TAG, "START points=${points.size}")

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isRunning || !userWantsClicking) return
                val pts = points
                if (pts.isEmpty()) { pauseLoop(); return }
                val currentCps = cps.coerceAtLeast(1)
                val intervalMs = (1000.0 / currentCps).toLong().coerceAtLeast(1L)
                var currentHold = holdMs.coerceAtLeast(1L)
                if (currentHold >= intervalMs) currentHold = (intervalMs - 1).coerceAtLeast(1L)
                val t0 = SystemClock.uptimeMillis()
                val idx = pointIndex % pts.size
                val p = pts[idx]
                pointIndex = (idx + 1) % pts.size
                dispatchPureTap(p.x, p.y, currentHold)
                val elapsed = SystemClock.uptimeMillis() - t0
                workerHandler?.postDelayed(this, (intervalMs - elapsed).coerceAtLeast(0L))
            }
        }
        workerHandler?.post(clickRunnable!!)
        mainHandler.post { refreshPanel() }
    }

    private fun pauseLoop() {
        isRunning = false
        isPlayingRecord = false
        clickRunnable?.let { workerHandler?.removeCallbacks(it) }
        clickRunnable = null
        mainHandler.post {
            setMarkersTouchable(true)
            refreshPanel()
        }
    }

    private fun stopLoop() {
        userWantsClicking = false
        isPlayingRecord = false
        pauseLoop()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
    }

    private fun ensureWorker() {
        if (workerThread == null || workerThread?.isAlive != true) {
            workerThread = HandlerThread("ClickWorker").apply { start() }
            workerHandler = Handler(workerThread!!.looper)
        }
    }

    private fun dispatchPureTap(x: Float, y: Float, hold: Long) {
        try {
            val path = Path().apply { moveTo(x, y) }
            // Keep holds moderate so real finger input can interleave between taps
            val duration = hold.coerceIn(1L, 500L)
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            // Callback must not stop the loop — cancelled is normal when user touches
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {}
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "gesture cancelled (user touch?) at ($x,$y) — continue")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchPureTap", e)
        }
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        val wm = windowManager ?: return
        for (i in pointViews.indices) {
            try {
                val v = pointViews[i]
                val p = pointParamsList.getOrNull(i) ?: continue
                p.flags = if (touchable) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                }
                wm.updateViewLayout(v, p)
            } catch (_: Exception) {}
        }
    }

    // ─── Record mode ────────────────────────────────────────────────

    private fun toggleRecord() {
        if (isRecording) {
            stopRecordingInternal()
            toast("Recording saved (${recording.size} taps)")
        } else {
            if (isRunning || isPlayingRecord) stopLoop()
            startRecording()
        }
        refreshPanel()
    }

    private fun startRecording() {
        val wm = windowManager ?: return
        isRecording = true
        sessionTaps.clear()
        lastUpAt = 0L
        try {
            // Full-screen capture layer (panel stays on top and usable)
            val capture = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Keep below the control panel visually by adding first
            }
            capture.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        downAt = SystemClock.uptimeMillis()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        val upAt = SystemClock.uptimeMillis()
                        val hold = (upAt - downAt).coerceAtLeast(1L)
                        val gap = if (lastUpAt > 0) (downAt - lastUpAt).coerceAtLeast(0L) else 0L
                        if (sessionTaps.isNotEmpty()) {
                            val prev = sessionTaps.last()
                            sessionTaps[sessionTaps.lastIndex] = prev.copy(gapAfterMs = gap)
                        }
                        sessionTaps.add(RecordedTap(downX, downY, hold, 0L))
                        lastUpAt = upAt
                        Log.i(TAG, "REC tap (${downX.toInt()},${downY.toInt()}) hold=$hold gap=$gap")
                        // Forward the same tap to the game underneath
                        dispatchPureTap(downX, downY, hold)
                        mainHandler.post { refreshPanel() }
                        true
                    }
                    else -> true
                }
            }
            wm.addView(capture, params)
            captureView = capture
            // Re-add panel on top so Record/Stop remain tappable
            controlsView?.let { panel ->
                try {
                    wm.removeView(panel)
                    wm.addView(panel, (panel.layoutParams as WindowManager.LayoutParams))
                } catch (e: Exception) {
                    Log.e(TAG, "re-add panel", e)
                }
            }
            toast("Recording — taps go to the game and are saved")
        } catch (e: Exception) {
            Log.e(TAG, "startRecording", e)
            isRecording = false
            toast("Record failed: ${e.javaClass.simpleName}")
        }
    }

    private fun stopRecordingInternal() {
        isRecording = false
        try { captureView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        captureView = null

        if (sessionTaps.isEmpty()) {
            refreshPanel()
            return
        }

        // Finalize: set last gap to average inter-tap gap for looping
        val gaps = sessionTaps.dropLast(1).map { it.gapAfterMs }.filter { it > 0 }
        val avgGap = if (gaps.isNotEmpty()) gaps.average().toLong() else 100L
        if (sessionTaps.isNotEmpty()) {
            val last = sessionTaps.last()
            sessionTaps[sessionTaps.lastIndex] = last.copy(gapAfterMs = avgGap)
        }

        recording = sessionTaps.toMutableList()
        Prefs.setRecording(this, recording)

        // Averages → update Hold / CPS sliders
        val avgHold = sessionTaps.map { it.holdMs }.average().toLong().coerceAtLeast(1L)
        val totalGaps = sessionTaps.sumOf { it.gapAfterMs + it.holdMs }.coerceAtLeast(1L)
        val avgCps = ((sessionTaps.size * 1000.0) / totalGaps).toInt().coerceIn(1, 500)

        holdMs = avgHold
        cps = avgCps
        Prefs.setHold(this, avgHold.toInt().coerceIn(0, 1000))
        Prefs.setCps(this, avgCps)

        // Also set points to recorded coordinates (unique-ish order)
        val pts = sessionTaps.map { ClickPoint(it.x, it.y) }.toMutableList()
        Prefs.setPoints(this, pts)
        points = pts
        // Refresh markers
        mainHandler.post {
            clearPointViews()
            pts.forEach { showMarkerAt(it.x, it.y) }
            refreshPanel()
        }
        Log.i(TAG, "Recorded ${recording.size} taps avgHold=$avgHold avgCps=$avgCps")
    }

    private fun togglePlayRecord() {
        if (isPlayingRecord) {
            stopLoop()
            toast("Playback stopped")
        } else {
            if (recording.isEmpty()) recording = Prefs.getRecording(this)
            if (recording.isEmpty()) {
                toast("No recording — press Record first")
                return
            }
            if (isRunning) stopLoop()
            if (isRecording) stopRecordingInternal()
            startPlayRecord()
        }
        refreshPanel()
    }

    private fun startPlayRecord() {
        try { captureView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        captureView = null
        isRecording = false
        isPlayingRecord = true
        userWantsClicking = false
        isRunning = true
        recordPlayIndex = 0
        setMarkersTouchable(false)
        ensureWorker()
        Log.i(TAG, "PLAY RECORD size=${recording.size}")

        clickRunnable = object : Runnable {
            override fun run() {
                if (!isPlayingRecord) return
                val seq = recording
                if (seq.isEmpty()) {
                    pauseLoop()
                    return
                }
                val t = seq[recordPlayIndex % seq.size]
                recordPlayIndex = (recordPlayIndex + 1) % seq.size

                val t0 = SystemClock.uptimeMillis()
                dispatchPureTap(t.x, t.y, t.holdMs.coerceAtLeast(1L))
                val elapsed = SystemClock.uptimeMillis() - t0
                val wait = (t.gapAfterMs - elapsed).coerceAtLeast(0L)
                workerHandler?.postDelayed(this, wait)
            }
        }
        workerHandler?.post(clickRunnable!!)
        mainHandler.post { refreshPanel() }
        toast("Playing recording…")
    }

    // ─── Panel UI ───────────────────────────────────────────────────

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    fun showControls() {
        if (controlsView != null) return
        val wm = windowManager ?: return
        try {
            val themed = ContextThemeWrapper(this, R.style.Theme_FloatingAutoClicker)
            val view = LayoutInflater.from(themed).inflate(R.layout.overlay_controls, null)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val panelW = view.measuredWidth.coerceAtLeast(200)
            val panelH = view.measuredHeight.coerceAtLeast(220)
            val dm = resources.displayMetrics
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((dm.widthPixels - panelW) / 2).coerceAtLeast(0)
                y = ((dm.heightPixels - panelH) / 2).coerceAtLeast(0)
            }
            setupPanelDrag(view, params, panelW, panelH, dm.widthPixels, dm.heightPixels)
            setupPanelButtons(view)
            wm.addView(view, params)
            controlsView = view
            wantPanelVisible = true
            loadAndShowPoints()
            refreshPanel()
            toast("Floating panel ready")
        } catch (e: Exception) {
            Log.e(TAG, "showControls", e)
            controlsView = null
        }
    }

    private fun setupPanelButtons(view: View) {
        view.findViewById<ImageButton>(R.id.btnStartStopOverlay).setOnClickListener {
            if (isPlayingRecord) {
                stopLoop()
            } else if (userWantsClicking && isRunning) {
                stopClicking()
            } else {
                if (isRecording) stopRecordingInternal()
                var pts = Prefs.getPoints(this)
                if (pts.isEmpty()) {
                    val dm = resources.displayMetrics
                    addPointAt(dm.widthPixels / 2f, dm.heightPixels / 2f)
                    pts = Prefs.getPoints(this)
                }
                points = pts.toMutableList()
                holdMs = Prefs.getHold(this).toLong()
                cps = Prefs.getCps(this)
                startClicking()
            }
            refreshPanel()
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_UI).setPackage(packageName))
        }

        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener { hideAll() }
        view.findViewById<ImageButton>(R.id.btnAddPoint).setOnClickListener {
            val dm = resources.displayMetrics
            val pts = Prefs.getPoints(this)
            val x = if (pts.isNotEmpty()) pts.last().x + 60 else dm.widthPixels / 2f
            val y = if (pts.isNotEmpty()) pts.last().y + 60 else dm.heightPixels / 2f
            addPointAt(x.coerceIn(40f, dm.widthPixels - 40f), y.coerceIn(40f, dm.heightPixels - 40f))
        }
        view.findViewById<ImageButton>(R.id.btnRemovePoint).setOnClickListener { removeLastPoint() }

        view.findViewById<MaterialButton>(R.id.btnRecord).setOnClickListener { toggleRecord() }
        view.findViewById<MaterialButton>(R.id.btnPlayRecord).setOnClickListener { togglePlayRecord() }

        val sliderHold = view.findViewById<Slider>(R.id.sliderHold)
        val sliderCps = view.findViewById<Slider>(R.id.sliderCps)
        sliderHold.value = Prefs.getHold(this).toFloat().coerceIn(0f, 1000f)
        sliderCps.value = Prefs.getCps(this).toFloat().coerceIn(1f, 500f)
        sliderHold.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.toInt()
            Prefs.setHold(this, v)
            holdMs = v.toLong()
            view.findViewById<TextView>(R.id.txtHold).text = "$v ms"
        }
        sliderCps.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.toInt()
            Prefs.setCps(this, v)
            cps = v
            view.findViewById<TextView>(R.id.txtCps).text = "$v"
        }
    }

    private fun refreshPanel() {
        try {
            val view = controlsView ?: return
            val hold = Prefs.getHold(this)
            val cpsVal = Prefs.getCps(this)
            view.findViewById<TextView>(R.id.txtHold).text = "$hold ms"
            view.findViewById<TextView>(R.id.txtCps).text = "$cpsVal"
            view.findViewById<Slider>(R.id.sliderHold)?.let {
                if (kotlin.math.abs(it.value - hold) > 0.5f) it.value = hold.toFloat().coerceIn(0f, 1000f)
            }
            view.findViewById<Slider>(R.id.sliderCps)?.let {
                if (kotlin.math.abs(it.value - cpsVal) > 0.5f) it.value = cpsVal.toFloat().coerceIn(1f, 500f)
            }
            val count = Prefs.getPoints(this).size
            view.findViewById<TextView>(R.id.txtPointsCount).text =
                when {
                    isRecording -> "REC ${sessionTaps.size}"
                    isPlayingRecord -> "PLAY ${recording.size}"
                    count == 1 -> "1 point"
                    else -> "$count points"
                }

            val btnRec = view.findViewById<MaterialButton>(R.id.btnRecord)
            if (isRecording) {
                btnRec.text = "Stop rec"
                btnRec.setBackgroundColor(0xFFDC2626.toInt())
            } else {
                btnRec.text = "Record"
                btnRec.setBackgroundColor(0xFF7F1D1D.toInt())
            }

            val btnPlay = view.findViewById<MaterialButton>(R.id.btnPlayRecord)
            if (isPlayingRecord) {
                btnPlay.text = "Stop"
            } else {
                btnPlay.text = "Play rec"
            }

            val info = view.findViewById<TextView>(R.id.txtRecordInfo)
            if (recording.isEmpty() && !isRecording) {
                info.text = "No recording"
            } else if (isRecording) {
                info.text = "Recording… taps: ${sessionTaps.size}"
            } else {
                val avgH = recording.map { it.holdMs }.average().toInt()
                info.text = "${recording.size} taps · avg hold ${avgH}ms"
            }

            val btn = view.findViewById<ImageButton>(R.id.btnStartStopOverlay)
            if ((userWantsClicking && isRunning) || isPlayingRecord) {
                btn.setImageResource(android.R.drawable.ic_media_pause)
                btn.setBackgroundResource(R.drawable.overlay_stop_btn)
            } else {
                btn.setImageResource(android.R.drawable.ic_media_play)
                btn.setBackgroundResource(R.drawable.overlay_start_btn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshPanel", e)
        }
    }

    private fun loadAndShowPoints() {
        clearPointViews()
        val pts = Prefs.getPoints(this)
        pts.forEach { showMarkerAt(it.x, it.y) }
        points = pts.toMutableList()
    }

    private fun addPointAt(x: Float, y: Float) {
        Prefs.addPoint(this, x, y)
        showMarkerAt(x, y)
        points = Prefs.getPoints(this)
        refreshPanel()
    }

    private fun removeLastPoint() {
        if (!Prefs.removeLastPoint(this)) return
        if (pointViews.isNotEmpty()) {
            val last = pointViews.removeAt(pointViews.lastIndex)
            if (pointParamsList.isNotEmpty()) pointParamsList.removeAt(pointParamsList.lastIndex)
            try { windowManager?.removeView(last) } catch (_: Exception) {}
        }
        points = Prefs.getPoints(this)
        refreshPanel()
    }

    private fun showMarkerAt(x: Float, y: Float) {
        val wm = windowManager ?: return
        try {
            val themed = ContextThemeWrapper(this, R.style.Theme_FloatingAutoClicker)
            val marker = LayoutInflater.from(themed).inflate(R.layout.click_point, null)
            val half = halfPx()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - half).toInt().coerceAtLeast(0)
                this.y = (y - half).toInt().coerceAtLeast(0)
            }
            val index = pointViews.size
            setupMarkerDrag(marker, params, index)
            wm.addView(marker, params)
            pointViews.add(marker)
            pointParamsList.add(params)
        } catch (e: Exception) {
            Log.e(TAG, "showMarkerAt", e)
        }
    }

    private fun setupMarkerDrag(view: View, params: WindowManager.LayoutParams, index: Int) {
        var iX = 0; var iY = 0; var tX = 0f; var tY = 0f
        val half = halfPx()
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iX = params.x; iY = params.y; tX = e.rawX; tY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (iX + (e.rawX - tX).toInt()).coerceAtLeast(0)
                    params.y = (iY + (e.rawY - tY).toInt()).coerceAtLeast(0)
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val cx = params.x + half
                    val cy = params.y + half
                    val pts = Prefs.getPoints(this).toMutableList()
                    if (index in pts.indices) {
                        pts[index] = ClickPoint(cx, cy)
                        Prefs.setPoints(this, pts)
                        points = pts
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clearPointViews() {
        pointViews.forEach { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        pointViews.clear()
        pointParamsList.clear()
    }

    private fun setupPanelDrag(
        view: View, params: WindowManager.LayoutParams,
        panelW: Int, panelH: Int, screenW: Int, screenH: Int
    ) {
        var iX = 0; var iY = 0; var tX = 0f; var tY = 0f
        val handle = view.findViewById<View>(R.id.btnDrag) ?: view
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iX = params.x; iY = params.y; tX = e.rawX; tY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (iX + (e.rawX - tX).toInt()).coerceIn(0, (screenW - panelW).coerceAtLeast(0))
                    params.y = (iY + (e.rawY - tY).toInt()).coerceIn(0, (screenH - panelH).coerceAtLeast(0))
                    try { windowManager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun hideAll() {
        wantPanelVisible = false
        if (isRecording) stopRecordingInternal()
        try { controlsView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        controlsView = null
        clearPointViews()
    }

    private fun toast(msg: String) {
        mainHandler.post {
            try { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        }
    }
}
