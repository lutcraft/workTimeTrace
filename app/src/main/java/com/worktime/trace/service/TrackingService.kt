package com.worktime.trace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.worktime.trace.MainActivity
import com.worktime.trace.R
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.EventType
import com.worktime.trace.geo.GeoUtils
import com.worktime.trace.geo.Locator
import com.worktime.trace.geo.LocatorFactory
import com.worktime.trace.geo.ZoneStateTracker
import com.worktime.trace.stats.OvertimeCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻前台服务：周期性获取定位，判定进出工作地点围栏并记录事件。
 *
 * 不依赖 Google Play Services（国产 ROM 兼容），使用高德融合定位。
 */
class TrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val STATE_CHANNEL_ID = "state_alerts"
        private const val NOTIFICATION_ID = 1001
        private const val STATE_NOTIFICATION_ID = 1002
        const val ACTION_START = "com.worktime.trace.action.START"
        const val ACTION_STOP = "com.worktime.trace.action.STOP"

        /** 进程内服务存活标志（进程重启后自动复位为 false）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 定位回调专用单线程：保证围栏判定与事件写入严格串行，避免竞态重复记录。 */
    private val positionDispatcher = Dispatchers.Default.limitedParallelism(1)

    private lateinit var app: WorkTimeTraceApp

    private var locator: Locator? = null
    private val tracker = ZoneStateTracker(exitConfirmations = 2)

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        app = application as WorkTimeTraceApp
        com.worktime.trace.util.AppLogger.d("前台服务创建")
        createChannel()
        // 尽快进入前台，满足系统对前台服务的时限要求
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_title_tracking), "正在获取定位…"),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    app.settingsStore.setTrackingEnabled(false)
                }
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                scope.launch {
                    app.settingsStore.setTrackingEnabled(true)
                    if (!running) {
                        running = true
                        startTrackingLoop()
                    } else {
                        // 已在运行中收到 START：视为配置变更（如定位间隔调整），
                        // 用最新设置重启定位链路
                        val intervalMs = (app.settingsStore.settings.first()
                            .locationIntervalSec * 1000L).coerceAtLeast(30_000L)
                        withContext(Dispatchers.Main) { startLocator(intervalMs) }
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startTrackingLoop() {
        val settings = app.settingsStore.settings.first()
        val intervalMs = (settings.locationIntervalSec * 1000L).coerceAtLeast(30_000L)

        // 从数据库恢复各工作地点的“是否在岗”状态
        val latestPerWorkplace = withContext(Dispatchers.IO) {
            app.repository.latestEventPerWorkplace()
        }
        latestPerWorkplace.forEach {
            tracker.initInside(it.workplaceId, it.type == EventType.ENTER)
        }

        withContext(Dispatchers.Main) { startLocator(intervalMs) }
        updateNotification()
    }

    /** 用给定间隔（重新）启动定位链路。须在主线程调用。 */
    private fun startLocator(intervalMs: Long) {
        locator?.destroy()
        locator = LocatorFactory.create(applicationContext).also {
            it.startPeriodic(intervalMs) { lat, lng -> onPosition(lat, lng) }
        }
    }

    private fun stopTracking() {
        running = false
        scope.launch(Dispatchers.Main) {
            locator?.destroy()
            locator = null
        }
    }

    override fun onDestroy() {
        isRunning = false
        com.worktime.trace.util.AppLogger.w("前台服务销毁")
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    // ---- 定位回调（可能在任意线程）----

    private fun onPosition(lat: Double, lng: Double) {
        com.worktime.trace.util.AppLogger.d("定位回调: lat=$lat lng=$lng")
        scope.launch(positionDispatcher) {
            val workplaces = app.repository.getEnabledWorkplaces()
            for (wp in workplaces) {
                val dist = GeoUtils.distanceMeters(lat, lng, wp.latitude, wp.longitude)
                val insideNow = dist <= wp.radiusMeters
                val changed = tracker.onReading(wp.id, insideNow)
                if (changed != null) {
                    val type = if (changed) EventType.ENTER else EventType.EXIT
                    app.repository.addEvent(
                        workplaceId = wp.id,
                        type = type,
                        timestamp = System.currentTimeMillis(),
                        latitude = lat,
                        longitude = lng
                    )
                    com.worktime.trace.util.AppLogger.d(
                        "记录" + (if (changed) "上班(ENTER)" else "下班(EXIT)") +
                            ": 地点=${wp.name} 距离=${dist.toInt()}m"
                    )
                    notifyStateChange(changed, wp.name)
                }
            }
            updateNotification()
        }
    }

    // ---- 通知 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tracking_channel_desc)
                setShowBadge(false)
            }
        )
        // 上下班提醒：比常驻通知更高优先级（有提示音），进入/离开工作范围时弹出
        nm.createNotificationChannel(
            NotificationChannel(
                STATE_CHANNEL_ID,
                "上下班提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "进入或离开工作范围时提醒"
                setShowBadge(false)
            }
        )
    }

    /** 进出围栏时的临时提醒通知（点击打开 App、自动消失），附简短鼓励语。 */
    private fun notifyStateChange(enter: Boolean, workplaceName: String?) {
        val place = workplaceName?.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: "工作范围"
        val enterGreetings = listOf(
            "开始记录今日工时，新的一天加油！",
            "到岗打卡成功，今天也要元气满满！",
            "上班啦，愿今天高效又顺利！"
        )
        val exitGreetings = listOf(
            "辛苦了，好好放松一下吧～",
            "下班啦，去享受属于你的时间吧！",
            "今日努力已记录，快去休息吧～"
        )
        val title = if (enter) "已进入$place" else "已离开$place"
        val text = if (enter) enterGreetings.random() else exitGreetings.random()
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, STATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        getSystemService(NotificationManager::class.java).notify(STATE_NOTIFICATION_ID, notification)
    }

    /** 根据当日统计刷新常驻通知内容。 */
    fun updateNotification() {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = app.settingsStore.settings.first()
                val events = app.repository.observeEvents().first()
                val now = System.currentTimeMillis()
                val stats = OvertimeCalculator.computeDayStats(events, settings, now)
                val today = stats[java.time.Instant.ofEpochMilli(now)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()]
                val inside = OvertimeCalculator.currentlyInside(events)
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

                val title: String
                val text: String
                if (inside) {
                    title = "在岗中 · " + getString(R.string.notif_title_tracking)
                    val firstIn = today?.firstInMs
                    text = if (firstIn != null) {
                        "${timeFmt.format(Date(firstIn))} 到岗，今日累计 " +
                            OvertimeCalculator.formatDuration(today.workMs)
                    } else {
                        "正在记录…"
                    }
                } else {
                    title = getString(R.string.notif_title_tracking)
                    text = if (today != null && today.workMs > 0 && today.lastOutMs != null) {
                        "${timeFmt.format(Date(today.lastOutMs))} 离开，今日在岗 " +
                            OvertimeCalculator.formatDuration(today.workMs)
                    } else {
                        "等待进入工作范围…"
                    }
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
            } catch (_: Exception) {
                // 通知刷新失败不影响记录
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
