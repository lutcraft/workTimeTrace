package com.worktime.trace.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.worktime.trace.service.TrackingService

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
                Text("每日标准工时：${settings.standardHours} 小时")
                Slider(
                    value = settings.standardHours,
                    onValueChange = {
                        // 步进 0.5 小时
                        vm.setStandardHours((it * 2).toInt() / 2f)
                    },
                    valueRange = 4f..12f
                )
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
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "牛马时录 v1.0.0 · 进入工作地点范围自动记上班，离开自动记下班，" +
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
