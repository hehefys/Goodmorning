package com.test.island

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 上岛测试主页：三个按钮（开始 / 暂停继续 / 停止）+ 状态轮询。
 * 无布局 XML、无 Compose——纯程序化 UI，保持工程最小化。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val uiTick = object : Runnable {
        override fun run() {
            statusText.text = IslandService.status
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ 通知运行时权限（不授予则 MediaStyle 通知不可见，影响上岛判断）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        layout.addView(
            button("① 开始播放（上岛测试）") { IslandService.start(this, IslandService.ACTION_START) }
        )
        layout.addView(
            button("② 暂停 / 继续") { IslandService.start(this, IslandService.ACTION_TOGGLE) }
        )
        layout.addView(
            button("③ 停止") { IslandService.start(this, IslandService.ACTION_STOP) }
        )

        statusText = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            textSize = 15f
            text = "空闲。点「开始播放」后依次检查：\n\n" +
                "1. 下拉状态栏 → 有没有媒体样式通知\n" +
                "2. 屏幕顶部超级岛 → 有没有媒体小卡（点开可展开）\n" +
                "3. 锁屏界面 → 有没有媒体控件\n\n" +
                "三条里有任意一条 = 通道可用，闹钟可复用同款实现。"
        }
        layout.addView(statusText)

        val hint = TextView(this).apply {
            setPadding(0, pad, 0, 0)
            textSize = 12f
            gravity = Gravity.CENTER
            text = "音频为 60 秒合成和弦（自动循环）\n仅用于验证 MediaSession 上岛通道"
        }
        layout.addView(hint)

        setContentView(layout)
        handler.post(uiTick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(uiTick)
        super.onDestroy()
    }
}
