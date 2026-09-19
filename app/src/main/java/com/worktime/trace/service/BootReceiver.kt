package com.worktime.trace.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.worktime.trace.WorkTimeTraceApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后自动恢复考勤记录服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as? WorkTimeTraceApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = app.settingsStore.settings.first()
                if (settings.trackingEnabled) {
                    TrackingService.start(context)
                }
            } catch (_: Exception) {
                // 读取失败则不启动，等用户手动打开
            } finally {
                pendingResult.finish()
            }
        }
    }
}
