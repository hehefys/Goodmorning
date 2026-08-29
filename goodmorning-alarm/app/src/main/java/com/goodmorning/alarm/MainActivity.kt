package com.goodmorning.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.goodmorning.alarm.ui.navigation.AppNavHost
import com.goodmorning.alarm.ui.theme.AppTheme

/**
 * 唯一入口 Activity：挂 Compose 导航（主页/设置/权限引导）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppNavHost()
            }
        }
    }
}
