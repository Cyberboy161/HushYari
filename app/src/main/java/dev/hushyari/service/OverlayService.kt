package dev.hushyari.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.hushyari.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var panelView: View? = null
    private var miniView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var miniParams: WindowManager.LayoutParams? = null
    private var isMinimized = false

    private var isDragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var commandReceiver: BroadcastReceiver? = null

    // View references
    private var titleText: TextView? = null
    private var screenText: TextView? = null
    private var screenDescText: TextView? = null
    private var actionText: TextView? = null
    private var planText: TextView? = null
    private var skillText: TextView? = null
    private var worldText: TextView? = null
    private var thinkingText: TextView? = null
    private var statusText: TextView? = null
    private var stepText: TextView? = null
    private var commandInput: EditText? = null

    companion object {
        const val ACTION_COMMAND = "dev.hushyari.action.OVERLAY_COMMAND"
        const val ACTION_TOGGLE_PAUSE = "dev.hushyari.action.OVERLAY_TOGGLE_PAUSE"
        const val ACTION_STOP_AGENT = "dev.hushyari.action.OVERLAY_STOP_AGENT"
        const val ACTION_TOGGLE_MINIMIZE = "dev.hushyari.action.OVERLAY_TOGGLE_MINIMIZE"
        const val ACTION_STATE_UPDATE = "dev.hushyari.action.OVERLAY_STATE_UPDATE"
        const val EXTRA_COMMAND = "command"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_STATE_UPDATE -> {
                        OverlayState.update {
                            copy(
                                gameName = intent.getStringExtra("game_name") ?: gameName,
                                agentStatus = intent.getStringExtra("status") ?: agentStatus,
                                stepCount = intent.getIntExtra("step_count", stepCount),
                            )
                        }
                    }
                }
            }
        }
        registerReceiver(commandReceiver, IntentFilter(ACTION_STATE_UPDATE),
            RECEIVER_NOT_EXPORTED)

        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (panelView != null) return START_STICKY

        createPanel()
        createMini()
        showPanel()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        commandReceiver?.let { unregisterReceiver(it) }
        removeViews()
        super.onDestroy()
    }

    private fun observeState() {
        stateJob = scope.launch {
            OverlayState.state.collectLatest { state ->
                titleText?.text = state.gameName.ifEmpty { "HushYari" }
                screenText?.text = "Screen: ${state.currentScreen}"
                screenDescText?.text = state.screenDescription
                actionText?.text = "Last: ${state.lastAction}"
                planText?.text = state.currentPlan
                skillText?.text = state.activeSkill
                worldText?.text = state.worldSummary
                statusText?.text = state.agentStatus
                stepText?.text = "Step ${state.stepCount}"
                thinkingText?.visibility = if (state.llmThinking) View.VISIBLE else View.GONE

                val statusColor = when {
                    state.error != null -> Color.parseColor("#FF6666")
                    state.popupDetected -> Color.parseColor("#FFCC00")
                    state.agentStatus == "Running" -> Color.parseColor("#66FF66")
                    else -> Color.parseColor("#CCCCCC")
                }
                statusText?.setTextColor(statusColor)
            }
        }
    }

    // ── Layout types ───────────────────────────────

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private val overlayFlags: Int
        get() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ── Panel ──────────────────────────────────────

    private fun createPanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1A1A2E"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#4433AAFF"))
            }
        }

        // Header: game name + minimize button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleText = TextView(this).apply {
            text = "HushYari"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minBtn = Button(this).apply {
            text = "—"
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#88AACC"))
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28))
            setOnClickListener { minimize() }
        }
        headerRow.addView(titleText)
        headerRow.addView(minBtn)
        container.addView(headerRow)
        container.addView(divider())

        // Screen info
        screenText = infoRow(container, "Screen: —")
        screenDescText = descRow(container, "No data yet")
        actionText = infoRow(container, "Last action: —")
        planText = descRow(container, "Plan: —")
        skillText = infoRow(container, "Skill: —")
        worldText = descRow(container, "World: —")

        // Thinking indicator
        thinkingText = TextView(this).apply {
            text = "🧠 Thinking..."
            textSize = 10f
            setTextColor(Color.parseColor("#66CCFF"))
            visibility = View.GONE
        }
        container.addView(thinkingText)

        // Status footer
        container.addView(divider())

        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusText = infoInRow(footerRow, "Status", "Idle")
        stepText = infoInRow(footerRow, "", "0")

        val pauseBtn = smallBtn("⏯", "#FFFFFF") {
            sendBroadcast(Intent(ACTION_TOGGLE_PAUSE))
        }
        val stopBtn = smallBtn("⏹", "#FF6666") {
            sendBroadcast(Intent(ACTION_STOP_AGENT))
        }
        footerRow.addView(pauseBtn)
        footerRow.addView(stopBtn)
        container.addView(footerRow)

        // Command input
        commandInput = EditText(this).apply {
            hint = "Tell the agent what to do..."
            setHintTextColor(Color.parseColor("#6688AA"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#331A2A4E"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val cmd = text.toString().trim()
                    if (cmd.isNotEmpty()) {
                        sendCommand(cmd)
                        text.clear()
                    }
                    true
                } else false
            }
        }
        container.addView(commandInput)

        // Drag via header only
        headerRow.setOnTouchListener { _, event -> handleDrag(event) }

        panelView = container

        panelParams = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            overlayFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(40)
            y = dp(120)
        }
    }

    // ── Mini view (when collapsed) ────────────────

    private fun createMini() {
        miniView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DD1A1A2E"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#4433AAFF"))
            }

            val label = TextView(this@OverlayService).apply {
                text = "H"
                textSize = 14f
                setTextColor(Color.parseColor("#66CCFF"))
                gravity = Gravity.CENTER
            }

            val status = TextView(this@OverlayService).apply {
                text = "•"
                textSize = 10f
                setTextColor(Color.parseColor("#66FF66"))
                gravity = Gravity.CENTER
                tag = "mini_status"
            }

            addView(label)
            addView(status)

            var touchDownTime = 0L
            var wasDragged = false

            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownTime = System.currentTimeMillis()
                        wasDragged = false
                        handleMiniDrag(ev)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(ev.rawX - touchStartX) > 5 ||
                            kotlin.math.abs(ev.rawY - touchStartY) > 5
                        ) wasDragged = true
                        handleMiniDrag(ev)
                    }
                    MotionEvent.ACTION_UP -> {
                        handleMiniDrag(ev)
                        if (!wasDragged && System.currentTimeMillis() - touchDownTime < 300) {
                            maximize()
                        }
                    }
                }
                true
            }
        }

        miniParams = WindowManager.LayoutParams(
            dp(48),
            dp(48),
            overlayType,
            overlayFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(40)
            y = dp(120)
        }
    }

    // ── Minimize / Maximize ───────────────────────

    private fun minimize() {
        if (isMinimized) return
        removePanelView()
        miniParams?.let { p ->
            panelParams?.let { pp ->
                p.x = pp.x
                p.y = pp.y
            }
            windowManager?.addView(miniView, p)
        }
        isMinimized = true
    }

    private fun maximize() {
        if (!isMinimized) return
        miniView?.let { windowManager?.removeView(it) }
        panelParams?.let { windowManager?.addView(panelView, it) }
        isMinimized = false
    }

    private fun removePanelView() {
        try { panelView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
    }

    private fun removeViews() {
        try {
            panelView?.let { windowManager?.removeView(it) }
            miniView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
    }

    private fun showPanel() {
        panelParams?.let { windowManager?.addView(panelView, it) }
    }

    // ── Drag handling ─────────────────────────────

    private fun handleDrag(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = panelParams ?: return false
                dragStartX = p.x; dragStartY = p.y
                touchStartX = event.rawX; touchStartY = event.rawY
                isDragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()
                if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) isDragging = true
                if (isDragging && panelParams != null) {
                    panelParams?.x = dragStartX + dx
                    panelParams?.y = dragStartY + dy
                    windowManager?.updateViewLayout(panelView, panelParams)
                }
                true
            }
            MotionEvent.ACTION_UP -> !isDragging
            else -> false
        }
    }

    private fun handleMiniDrag(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = miniParams ?: return false
                dragStartX = p.x; dragStartY = p.y
                touchStartX = event.rawX; touchStartY = event.rawY
                isDragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()
                if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) isDragging = true
                if (isDragging && miniParams != null) {
                    miniParams?.x = dragStartX + dx
                    miniParams?.y = dragStartY + dy
                    windowManager?.updateViewLayout(miniView, miniParams)
                }
                true
            }
            MotionEvent.ACTION_UP -> !isDragging
            else -> false
        }
    }

    // ── Helpers ───────────────────────────────────

    private fun infoRow(parent: LinearLayout, label: String): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#AABBCC"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        parent.addView(tv)
        return tv
    }

    private fun descRow(parent: LinearLayout, label: String): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.parseColor("#778899"))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(tv)
        return tv
    }

    private fun infoInRow(parent: LinearLayout, label: String, value: String): TextView {
        val tv = TextView(this).apply {
            text = if (label.isNotEmpty()) "$label: $value" else value
            textSize = 11f
            setTextColor(Color.parseColor("#AABBCC"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        parent.addView(tv)
        return tv
    }

    private fun smallBtn(text: String, color: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor(color))
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)
            )
            setOnClickListener { onClick() }
        }
    }

    private fun divider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(6); bottomMargin = dp(6)
            }
            setBackgroundColor(Color.parseColor("#22334455"))
        }
    }

    private fun sendCommand(text: String) {
        val intent = Intent(ACTION_COMMAND).apply {
            putExtra(EXTRA_COMMAND, text)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
