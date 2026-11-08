package com.worktime.trace.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.worktime.trace.service.TrackingService
import com.worktime.trace.stats.OvertimeCalculator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun fmtTime(ms: Long, zone: ZoneId): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(timeFmt)

/** 大字紧凑时长：7h 00m（状态卡核心数值用）。 */
private fun bigDurationText(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${"%02d".format(m)}m" else "${m}m"
}

/** 权限授权状态（回到前台时自动刷新）。 */
data class PermState(
    val fine: Boolean,
    val background: Boolean,
    val notifications: Boolean,
    val batteryIgnored: Boolean
)

@Composable
fun rememberPermState(): PermState {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return remember(refresh) {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        PermState(
            fine = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            background = Build.VERSION.SDK_INT < 30 ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = Build.VERSION.SDK_INT < 33 ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onOpenMap: (Long) -> Unit) {
    val state by vm.uiState.collectAsState()
    val perm = rememberPermState()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var confirmStop by remember { mutableStateOf(false) }

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 停止记录确认：破坏性操作防误触
    if (confirmStop) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("停止记录服务？") },
            text = { Text("停止后将不再自动记录上下班打卡，今日之后的工时不会统计。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    TrackingService.stop(context)
                }) { Text("停止", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            StatusCard(state, zone)
        }

        item {
            val canStart = perm.fine
            if (state.trackingEnabled) {
                // 停止：破坏性操作，描边警示样式 + 确认对话框
                OutlinedButton(
                    onClick = { confirmStop = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "停止记录",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                // 开始：主操作，品牌绿实心
                Button(
                    onClick = {
                        if (canStart) {
                            TrackingService.start(context)
                        } else {
                            fineLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("开始记录")
                }
            }
        }

        // 当日工时计算模式快捷切换（Niu 视觉稿：主页直接切换「首进末出 / 多段并集」）
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "当日工时算法",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            "切换后全 App 立即生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            )
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        com.worktime.trace.data.prefs.CalcMode.entries.forEach { mode ->
                            val selected = state.settings.calcMode == mode
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                                    )
                                    .clickable { vm.setCalcMode(mode) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    mode.label.replace("模式", ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // 权限引导卡（全部满足时隐藏）
        if (!perm.fine || !perm.background || !perm.notifications || !perm.batteryIgnored) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "权限设置（${missingCount(perm)}）",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!perm.fine) {
                            PermRow("定位权限（必需）") {
                                fineLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                        if (!perm.background) {
                            PermRow("后台定位（防止熄屏后漏记）") {
                                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                        if (!perm.notifications) {
                            PermRow("通知权限（显示常驻通知）") {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (!perm.batteryIgnored) {
                            PermRow("忽略电池优化（国产手机保活）") {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("工作地点", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onOpenMap(-1L) }) {
                            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加")
                        }
                    }
                    if (state.workplaces.isEmpty()) {
                        Text(
                            "还没有工作地点。点击\"添加\"，在地图上选择上班地点和打卡范围，进入范围自动记上班，离开自动记下班。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.workplaces.forEach { wp ->
                            var confirmDelete by remember { mutableStateOf(false) }
                            if (confirmDelete) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { confirmDelete = false },
                                    title = { Text("删除工作地点？") },
                                    text = { Text("「${wp.name}」将被删除，已产生的考勤记录仍会保留。") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmDelete = false
                                            vm.deleteWorkplace(wp.id)
                                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                                    }
                                )
                            }
                            ListItem(
                                headlineContent = { Text(wp.name) },
                                supportingContent = {
                                    Text(
                                        "范围 ${wp.radiusMeters.toInt()} 米 · " +
                                            String.format("%.5f, %.5f", wp.latitude, wp.longitude)
                                    )
                                },
                                leadingContent = {
                                    Icon(Icons.Filled.LocationOn, contentDescription = null)
                                },
                                trailingContent = {
                                    IconButton(onClick = { confirmDelete = true }) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = "删除"
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { onOpenMap(wp.id) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本月统计", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("在岗合计")
                        Text(OvertimeCalculator.formatDuration(state.monthWorkMs))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("加班合计")
                        Text(
                            OvertimeCalculator.formatDuration(state.monthOvertimeMs),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        "规则：工作日超出标准 " + OvertimeCalculator.formatDuration(
                            (state.settings.standardHours * 60).roundToInt() * 60_000L
                        ) + "计加班；" +
                            "周末/节假日全天计加班（可在设置中修改）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun missingCount(perm: PermState): Int =
    listOf(!perm.fine, !perm.background, !perm.notifications, !perm.batteryIgnored)
        .count { it }

@Composable
private fun PermRow(label: String, onRequest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRequest) { Text("去开启") }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, zone: ZoneId) {
    val inside = state.currentlyInside
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // 在岗中：固定绿色；不在范围：固定中性灰（均不随动态取色/壁纸变化，语义稳定）
    val container = if (inside) {
        if (dark) androidx.compose.ui.graphics.Color(0xFF1E3B2A)
        else androidx.compose.ui.graphics.Color(0xFFD3EFDD)
    } else {
        if (dark) androidx.compose.ui.graphics.Color(0xFF363B39)
        else androidx.compose.ui.graphics.Color(0xFFE4E7E5)
    }
    val accent = if (inside) {
        if (dark) androidx.compose.ui.graphics.Color(0xFF9CD6B0)
        else androidx.compose.ui.graphics.Color(0xFF1B5E2F)
    } else {
        if (dark) androidx.compose.ui.graphics.Color(0xFFB9BEBB)
        else androidx.compose.ui.graphics.Color(0xFF5A615E)
    }
    // 加班警示卡配色（Niu 视觉稿固定色值）
    val alertContainer = if (dark) androidx.compose.ui.graphics.Color(0xFF93000A)
    else androidx.compose.ui.graphics.Color(0xFFFFDAD6)
    val alertContent = if (dark) androidx.compose.ui.graphics.Color(0xFFFFDAD6)
    else androidx.compose.ui.graphics.Color(0xFF410002)
    val alertIcon = if (dark) androidx.compose.ui.graphics.Color(0xFFFFB4AB)
    else androidx.compose.ui.graphics.Color(0xFFBA1A1A)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = container
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (inside) "在岗中" else "不在工作范围",
                        style = MaterialTheme.typography.titleLarge,
                        color = accent
                    )
                }
                // 右上角：当前计时模式标签（Niu 视觉稿元素）
                Text(
                    if (state.settings.calcMode == com.worktime.trace.data.prefs.CalcMode.FIRST_IN_LAST_OUT)
                        "首进末出模式" else "多段累计模式",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.height(8.dp))
            val today = state.today
            if (state.currentlyInside) {
                state.currentSessionStartMs?.let { start ->
                    Text(
                        "本次 ${fmtTime(start, zone)} 进入围栏" +
                            "，当前仍在围栏内",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else if (today != null && today.workMs > 0 && today.lastOutMs != null) {
                Text(
                    "首进 ${fmtTime(today.firstInMs ?: 0, zone)} · 末出 ${fmtTime(today.lastOutMs, zone)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (today == null || today.workMs == 0L) {
                Text(
                    "今日尚无进入围栏记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (today != null && today.workMs > 0) {
                Spacer(Modifier.height(4.dp))
                // 核心数值：大字突出（Niu 视觉稿的 font-mono 大数字风格）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            "今日在岗",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            bigDurationText(today.workMs),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (today.overtimeMs > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "加班",
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color(0xFFBA1A1A)
                            )
                            Text(
                                bigDurationText(today.overtimeMs),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = androidx.compose.ui.graphics.Color(0xFFBA1A1A)
                            )
                        }
                    }
                }
                val thresholdMs = (state.settings.overtimeAlertThresholdHours * 3_600_000f).toLong()
                if (state.settings.overtimeAlertEnabled && today.overtimeMs >= thresholdMs) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Surface(
                        color = alertContainer,
                        contentColor = alertContent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Warning,
                                contentDescription = null,
                                tint = alertIcon,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "今日加班较多，注意休息",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = "今日已加班 ${OvertimeCalculator.formatDuration(today.overtimeMs)}（已超 ${state.settings.overtimeAlertThresholdHours} 小时阈值）",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                // 当天同时展示两种计时模式的对比差值（两种算法结果不同时才有意义）
                if (today.firstLastSpanMs != today.intervalUnionMs) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "对比：首进末出 ${OvertimeCalculator.formatDuration(today.firstLastSpanMs)}" +
                            " vs 多段并集 ${OvertimeCalculator.formatDuration(today.intervalUnionMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!state.trackingEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "记录服务未开启",
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
