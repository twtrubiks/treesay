package com.twtrubiks.treesay

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer

class MainActivity : ComponentActivity() {
    private val motionScale by lazy { AppMotionScale(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 背景是淺色大地色，系統列圖示固定用深色
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 跟 Compose 預設的 Recomposer 一樣（跟著 lifecycle 暫停、銷毀），只把動畫速度換成 AppMotionScale：
        // 系統移除動畫時，樹要不要動照 App 的「畫面動態」開關
        val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(motionScale, lifecycle)
        setContent(parent = recomposer) { TreeSayApp() }
    }

    override fun onStart() {
        super.onStart()
        motionScale.refresh()
    }
}
