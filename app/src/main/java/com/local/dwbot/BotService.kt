package com.local.dwbot

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/**
 * Accessibility service: takes screenshots, taps, and shows the floating control bar over the game.
 * Settings live in the "cfg" preferences; the bar's gear panel and [MainActivity] edit the same values.
 */
class BotService : AccessibilityService() {
    companion object {
        const val TAG = "DWHunt"
        const val ACTION_START = "com.local.dwbot.START"
        const val ACTION_STOP = "com.local.dwbot.STOP"

        @Volatile
        var instance: BotService? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ui = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var dev: Device? = null
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var toggle: Button? = null

    val running get() = job?.isActive == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_START -> startFromSettings(intent)
                ACTION_STOP -> stopBot()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!OpenCVLoader.initLocal()) say("OpenCV 加载失败")
        dev = Device(this)
        buildOverlay()
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        say("就绪，点 ▶ 开始")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopBot()
        runCatching { unregisterReceiver(receiver) }
        panel?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        panel = null
        instance = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    /** Starts a run from the saved settings; the optional extras of a START broadcast override them for that run. */
    private fun startFromSettings(extra: Intent? = null) {
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val wander = extra?.getBooleanExtra("wander", cfg.getBoolean("wander", false)) ?: cfg.getBoolean("wander", false)
        val level = extra?.getIntExtra("level", -1)?.takeIf { it >= 0 }
            ?: cfg.getInt(if (wander) "level_wander" else "level_giant", if (wander) 40 else 35)
        startBot(
            extra?.getIntExtra("count", cfg.getInt("count", 50)) ?: cfg.getInt("count", 50),
            extra?.getIntExtra("packs", cfg.getInt("packs", 100)) ?: cfg.getInt("packs", 100),
            extra?.getBooleanExtra("free", cfg.getBoolean("free", false)) ?: cfg.getBoolean("free", false),
            wander,
            level,
        )
    }

    fun startBot(count: Int, packs: Int, free: Boolean, wander: Boolean, level: Int) {
        if (running) return
        val d = dev ?: return
        setRunning(true)
        job = scope.launch {
            try {
                Bot(d, ::say, packs, free, wander, level).hunt(count)
            } catch (e: CancellationException) {
                say("已停止")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "bot crashed", e)
                say("出错：${e.message}")
            } finally {
                setRunning(false)
            }
        }
    }

    fun stopBot() {
        job?.cancel()
    }

    fun say(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            val f = java.io.File(filesDir, "bot.log")
            if (f.length() > 512 * 1024) f.writeText("")
            f.appendText(java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + "  " + msg + "\n")
        }
        ui.post { statusView?.text = msg }
    }

    private fun setRunning(on: Boolean) {
        ui.post { toggle?.text = if (on) "■" else "▶" }
    }

    private fun buildOverlay() {
        val dp = resources.displayMetrics.density
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)

        fun smallBtn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { onClick() }
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        toggle = smallBtn("▶") {
            if (running) stopBot() else startFromSettings()
        }
        statusView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            maxLines = 2
            maxWidth = (230 * dp).toInt()
            setPadding((6 * dp).toInt(), 0, 0, 0)
        }
        val handle = smallBtn("◂") {}

        // settings panel under the bar: saved as soon as a value changes, used by the next ▶
        val sp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            visibility = View.GONE
        }
        val packsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val modeBtn = smallBtn("") {}
        fun refreshMode() {
            val free = cfg.getBoolean("free", false)
            modeBtn.text = if (free) "体力：只用免费体力（点击切换）" else "体力：用背包体力包（点击切换）"
            packsRow.visibility = if (free) View.GONE else View.VISIBLE
        }
        modeBtn.setOnClickListener {
            cfg.edit().putBoolean("free", !cfg.getBoolean("free", false)).apply()
            refreshMode()
        }

        val targetBtn = smallBtn("") {}
        val levelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val levelVal = label("")
        fun levelKey() = if (cfg.getBoolean("wander", false)) "level_wander" else "level_giant"
        fun levelDef() = if (cfg.getBoolean("wander", false)) 40 else 35
        fun showLevel() { levelVal.text = "Lv." + cfg.getInt(levelKey(), levelDef()) }
        fun refreshTarget() {
            targetBtn.text = if (cfg.getBoolean("wander", false)) "目标：游荡丧尸（点击切换）" else "目标：巨型丧尸（点击切换）"
            showLevel()
        }
        targetBtn.setOnClickListener {
            cfg.edit().putBoolean("wander", !cfg.getBoolean("wander", false)).apply()
            refreshTarget()
        }
        levelRow.addView(label("目标等级"))
        levelRow.addView(smallBtn("－") {
            cfg.edit().putInt(levelKey(), (cfg.getInt(levelKey(), levelDef()) - 1).coerceAtLeast(1)).apply()
            showLevel()
        })
        levelRow.addView(levelVal)
        levelRow.addView(smallBtn("＋") {
            cfg.edit().putInt(levelKey(), (cfg.getInt(levelKey(), levelDef()) + 1).coerceAtMost(60)).apply()
            showLevel()
        })

        fun stepRow(row: LinearLayout, title: String, key: String, def: Int, step: Int, min: Int, max: Int) {
            val value = label("")
            fun show() { value.text = cfg.getInt(key, def).toString() }
            row.addView(label(title))
            row.addView(smallBtn("－") {
                cfg.edit().putInt(key, (cfg.getInt(key, def) - step).coerceAtLeast(min)).apply()
                show()
            })
            row.addView(value)
            row.addView(smallBtn("＋") {
                cfg.edit().putInt(key, (cfg.getInt(key, def) + step).coerceAtMost(max)).apply()
                show()
            })
            show()
        }
        val roundsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        stepRow(roundsRow, "打几轮", "count", 50, 10, 10, 999)
        stepRow(packsRow, "最多用体力包", "packs", 100, 20, 20, 5000)
        sp.addView(modeBtn)
        sp.addView(targetBtn)
        sp.addView(levelRow)
        sp.addView(roundsRow)
        sp.addView(packsRow)
        sp.addView(label("改动即时保存，下次点 ▶ 生效"))
        refreshMode()
        refreshTarget()

        val gear = smallBtn("⚙") {
            sp.visibility = if (sp.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        fun applyCollapsed(collapsed: Boolean) {
            val vis = if (collapsed) View.GONE else View.VISIBLE
            toggle?.visibility = vis
            statusView?.visibility = vis
            gear.visibility = vis
            if (collapsed) sp.visibility = View.GONE
            handle.text = if (collapsed) "DW" else "◂"
            bar.setBackgroundColor(if (collapsed) 0x80000000.toInt() else 0xB3000000.toInt())
        }
        handle.setOnClickListener {
            val now = !cfg.getBoolean("collapsed", false)
            cfg.edit().putBoolean("collapsed", now).apply()
            applyCollapsed(now)
        }
        bar.addView(handle)
        bar.addView(toggle)
        bar.addView(gear)
        bar.addView(statusView)
        applyCollapsed(cfg.getBoolean("collapsed", false))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(sp)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
        panel = root
    }
}
