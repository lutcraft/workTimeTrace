package com.worktime.trace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.worktime.trace.service.TrackingService
import com.worktime.trace.ui.AppRoot
import com.worktime.trace.ui.theme.WorkTimeTraceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        resumeTrackingIfNeeded()
        setContent {
            WorkTimeTraceTheme {
                AppRoot()
            }
        }
    }

    /**
     * 自愈：设置状态为“记录中”但服务未运行（应用更新、系统查杀、
     * 厂商拦截开机广播等）时，打开 App 即自动恢复服务。
     */
    private fun resumeTrackingIfNeeded() {
        val app = application as WorkTimeTraceApp
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) {
                app.settingsStore.settings.first().trackingEnabled
            }
            if (enabled && !TrackingService.isRunning) {
                com.worktime.trace.util.AppLogger.w("检测到服务未运行（被杀/重启），自动恢复")
                TrackingService.start(this@MainActivity)
            }
        }
    }
}
