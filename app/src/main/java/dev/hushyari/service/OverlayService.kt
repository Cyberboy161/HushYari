package dev.hushyari.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import dev.hushyari.R
import timber.log.Timber

/**
 * Floating control pill overlay that shows game name, agent status, step count,
 * and provides stop/pause buttons for quick agent control.
 *
 * Draggable by the user around the screen. Reports tap events back to the
 * agent via a callback interface. Requires SYSTEM_ALERT_WINDOW permission.
 *
 * **Mechanics:**
 * - PokeClaw: Floating overlay pattern — gives the user always-visible
 *   agent status and control without switching to the app.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var gameName: String = "HushYari"
    private var agentStatus: String = "Idle"
    private var stepCount: Int = 0
    private var isDragging: Boolean = false
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        /** Broadcast action to toggle pause/resume from overlay. */
        const val ACTION_TOGGLE_PAUSE = "dev.hushyari.action.OVERLAY_TOGGLE_PAUSE"
        /** Broadcast action to stop agent from overlay. */
        const val ACTION_STOP_AGENT = "dev.hushyari.action.OVERLAY_STOP_AGENT"
        /** Broadcast action to open the main app from overlay. */
        const val ACTION_OPEN_APP = "dev.hushyari.action.OVERLAY_OPEN_APP"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Timber.d("OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Timber.w("Overlay permission not granted, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView != null) {
            // Already showing, update state from intent
            intent?.let {
                gameName = it.getStringExtra("game_name") ?: gameName
                agentStatus = it.getStringExtra("status") ?: agentStatus
                stepCount = it.getIntExtra("step_count", stepCount)
            }
            updateOverlayContent()
            return START_STICKY
        }

        intent?.let {
            gameName = it.getStringExtra("game_name") ?: gameName
            agentStatus = it.getStringExtra("status") ?: agentStatus
            stepCount = it.getIntExtra("step_count", stepCount)
        }

        createOverlay()
        Timber.d("OverlayService started: $gameName [$agentStatus]")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        Timber.d("OverlayService destroyed")
        super.onDestroy()
    }

    /**
     * Update the game name displayed in the overlay.
     */
    fun setGameName(name: String) {
        gameName = name
        updateOverlayContent()
    }

    /**
     * Update the agent status text displayed in the overlay.
     */
    fun setAgentStatus(status: String) {
        agentStatus = status
        updateOverlayContent()
    }

    /**
     * Update the step count displayed in the overlay.
     */
    fun setStepCount(count: Int) {
        stepCount = count
        updateOverlayContent()
    }

    // ── Overlay creation ────────────────────────────────────────────────

    private fun createOverlay() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E8222222"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
        }

        val titleText = TextView(this).apply {
            text = gameName
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            alpha = 0.9f
        }
        container.addView(titleText)

        val statusText = TextView(this).apply {
            text = agentStatus
            textSize = 11f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
            alpha = 0.8f
        }
        container.addView(statusText)

        val stepText = TextView(this).apply {
            text = "Step: $stepCount"
            textSize = 10f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        container.addView(stepText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        val pauseBtn = Button(this).apply {
            text = "⏯"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            setOnClickListener {
                sendBroadcast(Intent(ACTION_TOGGLE_PAUSE))
            }
        }

        val stopBtn = Button(this).apply {
            text = "⏹"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#FF6666"))
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            setOnClickListener {
                sendBroadcast(Intent(ACTION_STOP_AGENT))
            }
        }

        val appBtn = Button(this).apply {
            text = "H"
            textSize = 14f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#66CCFF"))
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            setOnClickListener {
                sendBroadcast(Intent(ACTION_OPEN_APP))
            }
        }

        buttonRow.addView(pauseBtn)
        buttonRow.addView(stopBtn)
        buttonRow.addView(appBtn)
        container.addView(buttonRow)

        // Store references for updates
        container.setTag(R.id.overlay_title, titleText)
        container.setTag(R.id.overlay_status, statusText)
        container.setTag(R.id.overlay_steps, stepText)

        // Drag handling
        container.setOnTouchListener { view, event ->
            handleTouch(view, event)
        }

        overlayView = container

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }

        windowManager?.addView(overlayView, overlayParams)
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = overlayParams?.x ?: 0
                initialY = overlayParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                if (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10) {
                    isDragging = true
                }
                if (isDragging) {
                    overlayParams?.x = initialX + deltaX
                    overlayParams?.y = initialY + deltaY
                    windowManager?.updateViewLayout(overlayView, overlayParams)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                !isDragging
            }
            else -> false
        }
    }

    private fun updateOverlayContent() {
        handler.post {
            val container = overlayView ?: return@post
            val titleText = container.getTag(R.id.overlay_title) as? TextView ?: return@post
            val statusText = container.getTag(R.id.overlay_status) as? TextView ?: return@post
            val stepText = container.getTag(R.id.overlay_steps) as? TextView ?: return@post

            titleText.text = gameName
            statusText.text = agentStatus
            stepText.text = "Step: $stepCount"
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
