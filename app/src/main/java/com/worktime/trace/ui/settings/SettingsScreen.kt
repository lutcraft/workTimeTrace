package com.worktime.trace.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.worktime.trace.data.prefs.CalcMode
import com.worktime.trace.service.TrackingService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("加班规则", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // 四舍五入到整分钟，规避 Float 精度误差（5/60 这类值直接乘 3.6e6 会丢毫秒）
                val stdMs = (settings.standardHours * 60).roundToInt() * 60_000L
                // 滚轮刻度：小时 4–12，分钟 0/5/10/…/55（旧值吸附到最近刻度）
                val hourSteps = (4..12).toList()
                val minuteSteps = (0..55 step 5).toList()
                val curH = settings.standardHours.toInt().coerceIn(4, 12)
                val curMFrac = ((settings.standardHours - curH) * 12)
                    .roundToInt().coerceIn(0, minuteSteps.lastIndex)
                val curM = minuteSteps[curMFrac]
                Text(
                    "每日标准工时：" + com.worktime.trace.stats.OvertimeCalculator.formatDuration(stdMs),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        items = hourSteps.map { "$it 小时" },
                        selectedIndex = hourSteps.indexOf(curH).let { if (it < 0) 4 else it },
                        onSelected = { idx ->
                            vm.setStandardHours(hourSteps[idx] + curM / 60f)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    WheelPicker(
                        items = minuteSteps.map { if (it == 0) "整点" else "$it 分钟" },
                        selectedIndex = curMFrac,
                        onSelected = { idx ->
                            vm.setStandardHours(curH + minuteSteps[idx] / 60f)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                SettingSwitch(
                    title = "周末全天计加班",
                    subtitle = "周六、周日（调休上班除外）在岗时间全部计入加班",
                    checked = settings.weekendOvertime,
                    onChange = { vm.setWeekendOvertime(it) }
                )
                SettingSwitch(
                    title = "法定节假日全天计加班",
                    subtitle = "已内置 2025–2026 年国务院放假与调休安排",
                    checked = settings.holidayOvertime,
                    onChange = { vm.setHolidayOvertime(it) }
                )
                Spacer(Modifier.height(8.dp))
                Text("工时计时模式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CalcMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.calcMode == mode,
                            onClick = { vm.setCalcMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Text(
                    settings.calcMode.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitch(
                    title = "加班时长关怀提醒",
                    subtitle = "当日加班超过设定阈值时，在主页状态卡片高亮提示“今日加班较多，注意休息”",
                    checked = settings.overtimeAlertEnabled,
                    onChange = { vm.setOvertimeAlertEnabled(it) }
                )
                if (settings.overtimeAlertEnabled) {
                    Text(
                        "提醒阈值：${settings.overtimeAlertThresholdHours} 小时",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    // 滑杆：0.5–6.0 小时，步进 0.5（拖动结束吸附到 0.5 步进并保存）
                    Slider(
                        value = settings.overtimeAlertThresholdHours,
                        onValueChange = { vm.setOvertimeAlertThresholdHours((it * 2).toInt() / 2f) },
                        valueRange = 0.5f..6f
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1.0f, 1.5f, 2.0f, 3.0f, 4.0f).forEach { h ->
                            FilterChip(
                                selected = settings.overtimeAlertThresholdHours == h,
                                onClick = { vm.setOvertimeAlertThresholdHours(h) },
                                label = {
                                    Text(
                                        "${h}h",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("定位", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("定位检查间隔", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        60 to "1 分钟", 180 to "3 分钟", 300 to "5 分钟", 600 to "10 分钟"
                    ).forEach { (sec, label) ->
                        FilterChip(
                            selected = settings.locationIntervalSec == sec,
                            onClick = {
                                vm.setLocationInterval(sec)
                                // 间隔立即生效：仅在服务运行中时重启服务
                                if (settings.trackingEnabled) {
                                    TrackingService.start(context)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "默认 10 分钟：1 次 GPS 唤醒约 10 秒，全天耗电极低。注意间隔越长，" +
                        "上下班判定延迟越大（下班需连续 2 次范围外确认，10 分钟间隔下最多滞后约 20 分钟）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("保活与自启", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }) { Text("加入电池优化白名单") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }) { Text("应用详情（开启自启动/后台弹出等）") }
                Text(
                    "国产手机（小米/华为/OPPO/vivo 等）请在系统设置中为本应用开启" +
                        "「自启动」「无限制后台」「锁定后台」等，否则系统可能杀掉记录服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("诊断", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val file = com.worktime.trace.util.AppLogger.export()
                    if (file != null) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", file
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "分享排障日志"))
                        }
                    } else {
                        android.widget.Toast.makeText(context, "暂无日志可导出", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("导出日志（用于反馈问题）") }
                Text(
                    "导出最近 7 天的运行日志（定位、打卡、服务状态），可通过微信/QQ 等发给开发者排查问题。日志不包含位置历史以外的个人信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // 版本号动态读取（PackageManager），与 build.gradle.kts 保持一致
                val versionName = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                Text(
                    "牛马时录 v$versionName · 进入工作地点范围自动记上班，离开自动记下班，" +
                        "按规则统计加班。数据仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "开发者：lutcraft",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 上下滚动的滚轮选择器（机械时钟样式）。
 *
 * 实现：LazyColumn + SnapFlingBehavior，上下各留一行 contentPadding
 * 使首尾项也能滚到中心；中心项 = firstVisibleItemIndex。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = 44.dp
    val visibleCount = 3
    val listState = rememberLazyListState()

    // 用户一旦手动滚动即接管，之后设置值变化不再自动重定位（避免拉回抖动）；
    // 接管前（含 DataStore 真值晚于首帧到达的场景）跟随 selectedIndex 定位
    var userInteracted by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { it }
            .collect { userInteracted = true }
    }
    LaunchedEffect(selectedIndex, items.size) {
        if (!userInteracted) {
            listState.scrollToItem(selectedIndex.coerceIn(0, items.lastIndex))
        }
    }

    // LaunchedEffect 不会因 lambda 变化重启，必须用 rememberUpdatedState
    // 拿最新的回调值，否则滚动停选会用过期的小时/分钟组合出错误值
    val currentOnSelected by rememberUpdatedState(onSelected)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    // 滚动停止后回调中心项；跳过初始误发射（定位前 index=0 且值不同）
    LaunchedEffect(listState, items.size) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .filter { !it.first }
            .collect { pair ->
                val idx = pair.second
                if (idx in items.indices && idx != currentSelectedIndex) currentOnSelected(idx)
            }
    }

    Box(modifier) {
        // 中心选中框（画在列表下层，文字浮于其上）
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp)
                )
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(vertical = itemHeight),
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * visibleCount)
        ) {
            items(count = items.size) { i ->
                val selected by remember {
                    derivedStateOf { i == listState.firstVisibleItemIndex }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        items[i],
                        style = if (selected) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}
