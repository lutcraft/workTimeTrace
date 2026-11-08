package com.worktime.trace.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机/应用升级/关机广播接收器：
 * - 关机时冻结在岗工作地点的最后在线心跳时间戳，供开机后若已离开围栏时回溯结算下班；
 * - 开机/升级后若此前用户启用了记录服务，则自动拉起前台服务。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        com.worktime.trace.util.AppLogger.w("BootReceiver 收到广播: $action")
        val app = context.applicationContext as? WorkTimeTraceApp ?: run {
            com.worktime.trace.util.AppLogger.w("BootReceiver: Application 转型失败，跳过")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_SHUTDOWN,
                    "android.intent.action.QUICKBOOT_POWEROFF" -> {
                        val now = System.currentTimeMillis()
                        val latest = app.repository.latestEventPerWorkplace()
                        com.worktime.trace.util.AppLogger.w(
                            "关机广播：查询到 ${latest.size} 个地点的最新事件"
                        )
                        latest.filter { it.type == EventType.ENTER }.forEach { ev ->
                            app.settingsStore.setLastInsideBeat(ev.workplaceId, now)
                            com.worktime.trace.util.AppLogger.w(
                                "捕获系统关机广播：已保存在岗地点 #${ev.workplaceId} 最后心跳 @ $now"
                            )
                        }
                        if (latest.none { it.type == EventType.ENTER }) {
                            com.worktime.trace.util.AppLogger.w("关机广播：当前无未闭合的在岗(ENTER)地点，无需冻结心跳")
                        }
                    }
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        val settings = app.settingsStore.settings.first()
                        if (settings.trackingEnabled) {
                            TrackingService.start(context)
                        }
                    }
                }
            } catch (e: Exception) {
                com.worktime.trace.util.AppLogger.w("BootReceiver 处理广播异常", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
