package com.local.dwbot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings screen, built in code (no layout files). Shows how to enable the accessibility service and edits the same
 * "cfg" preferences as the gear panel of the floating bar.
 */
class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var count: EditText
    private lateinit var packs: EditText
    private lateinit var levelGiant: EditText
    private lateinit var levelWander: EditText
    private lateinit var modeFree: RadioButton
    private lateinit var targetWander: RadioButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)

        fun label(text: String, size: Float = 15f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        }

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

        fun number(value: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }

        state = label("", 16f)
        count = number(cfg.getInt("count", 50))
        packs = number(cfg.getInt("packs", 100))
        levelGiant = number(cfg.getInt("level_giant", 35))
        levelWander = number(cfg.getInt("level_wander", 40))

        modeFree = RadioButton(this).apply { text = "只用免费体力（英雄免费体力 + 125 体力补给），领不到就暂停 10 分钟" }
        val modePacks = RadioButton(this).apply { text = "用背包体力包（体力不足时每次用 20 个）" }
        val modeGroup = RadioGroup(this).apply {
            addView(modeFree)
            addView(modePacks)
            check(if (cfg.getBoolean("free", false)) modeFree.id else modePacks.id)
        }
        val targetGiant = RadioButton(this).apply { text = "巨型丧尸（集结，最高 Lv.35）" }
        targetWander = RadioButton(this).apply { text = "游荡丧尸（单人攻击，最高 Lv.40）" }
        val targetGroup = RadioGroup(this).apply {
            addView(targetGiant)
            addView(targetWander)
            check(if (cfg.getBoolean("wander", false)) targetWander.id else targetGiant.id)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (40 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            addView(label("DW 打僵尸：自动打丧尸（只用第一队，从不花钻石）", 18f))
            addView(state)
            addView(label("1. 先给本应用开启「受限制的设置」（安卓 13+ 侧载应用必须），再到无障碍里打开本应用。"))
            addView(button("打开应用详情（右上角菜单 → 允许受限制的设置）") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(button("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(label("2. 打游戏，回到世界地图，点屏幕左上角悬浮条的 ▶ 开始，■ 停止，⚙ 随时改设置，◂ 收起。"))
            addView(label("体力"))
            addView(modeGroup)
            addView(label("目标"))
            addView(targetGroup)
            addView(label("巨型丧尸等级"))
            addView(levelGiant)
            addView(label("游荡丧尸等级"))
            addView(levelWander)
            addView(label("打几轮"))
            addView(count)
            addView(label("最多用几个体力包（每次补满用 20 个，只在用背包体力包时有效）"))
            addView(packs)
            addView(button("保存设置") { save() })
        }
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        state.text = if (BotService.instance != null) "无障碍服务：已连接" else "无障碍服务：未开启"
    }

    /** Writes the form to the preferences; levels are clamped to the game's 1..60 slider range. */
    private fun save() {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putInt("count", count.text.toString().toIntOrNull() ?: 50)
            .putInt("packs", packs.text.toString().toIntOrNull() ?: 100)
            .putInt("level_giant", (levelGiant.text.toString().toIntOrNull() ?: 35).coerceIn(1, 60))
            .putInt("level_wander", (levelWander.text.toString().toIntOrNull() ?: 40).coerceIn(1, 60))
            .putBoolean("free", modeFree.isChecked)
            .putBoolean("wander", targetWander.isChecked)
            .apply()
        state.text = "已保存"
    }
}
