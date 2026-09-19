package com.worktime.trace.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worktime.trace.stats.OvertimeCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil

private val zone: ZoneId = ZoneId.systemDefault()

private enum class StatsRange(val label: String) {
    MONTH("本月"), DAYS30("近 30 天"), YEAR("今年")
}

@Composable
fun StatsScreen(vm: StatsViewModel) {
    val state by vm.uiState.collectAsState()
    var range by remember { mutableStateOf(StatsRange.MONTH) }
    val today = remember(state.now) { Instant.ofEpochMilli(state.now).atZone(zone).toLocalDate() }

    val stats = remember(state.events, state.settings, state.now) {
        OvertimeCalculator.computeDayStats(state.events, state.settings, state.now)
    }

    // 所选范围内聚合
    val (start, end) = remember(range, today) {
        when (range) {
            StatsRange.MONTH -> today.withDayOfMonth(1) to today
            StatsRange.DAYS30 -> today.minusDays(29) to today
            StatsRange.YEAR -> today.withDayOfYear(1) to today
        }
    }
    val inRange = remember(stats, start, end) {
        stats.filterKeys { !it.isBefore(start) && !it.isAfter(end) }.values
    }
    val totalOt = inRange.sumOf { it.overtimeMs }
    val otDays = inRange.count { it.overtimeMs > 0 }
    val workdayOt = inRange.filter { !it.restDay }.sumOf { it.overtimeMs }
    val restOt = inRange.filter { it.restDay }.sumOf { it.overtimeMs }
    val avgPerOtDay = if (otDays > 0) totalOt / otDays else 0L
    val rangeDays = (end.toEpochDay() - start.toEpochDay() + 1)

    // 近 12 周加班（周一开头）
    val weekly = remember(stats, today) {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (11 downTo 0).map { i ->
            val ws = thisMonday.minusWeeks(i.toLong())
            val we = ws.plusDays(6)
            stats.filterKeys { !it.isBefore(ws) && !it.isAfter(we) }
                .values.sumOf { it.overtimeMs } / 3600_000.0
        }
    }
    val weekLabels = remember(today) {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (11 downTo 0).map { i ->
            val ws = thisMonday.minusWeeks(i.toLong())
            "${ws.monthValue}/${ws.dayOfMonth}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // 范围选择
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsRange.entries.forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { range = r },
                    label = { Text(r.label) }
                )
            }
        }

        // 总加班
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${range.label}加班合计（$rangeDays 天）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    OvertimeCalculator.formatDuration(totalOt),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (totalOt > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "加班 $otDays 天 · 有加班日日均 " + OvertimeCalculator.formatDuration(avgPerOtDay),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 拆分：工作日 vs 休息日
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "工作日加班",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        OvertimeCalculator.formatDuration(workdayOt),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "超标准工时部分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "周末/节假日加班",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        OvertimeCalculator.formatDuration(restOt),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "全天计入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 近12周柱状图
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("近 12 周加班趋势", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                val barColor = MaterialTheme.colorScheme.error
                val maxHours = weekly.max().coerceAtLeast(1.0)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    val n = weekly.size
                    val slot = size.width / n
                    val barW = slot * 0.55f
                    weekly.forEachIndexed { i, hours ->
                        if (hours > 0) {
                            val h = (size.height * (hours / maxHours)).toFloat()
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }
                    }
                    // 基线
                    drawRect(
                        color = Color.Gray.copy(alpha = 0.3f),
                        topLeft = Offset(0f, size.height - 1f),
                        size = Size(size.width, 1.5f)
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    weekLabels.forEach {
                        Text(
                            it,
                            Modifier.weight(1f),
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "每周加班小时数（最高 " + String.format("%.1f", maxHours) + "h）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 记录概况
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val workdays = inRange.count { it.workMs > 0 }
                val totalWork = inRange.sumOf { it.workMs }
                Text("记录概况", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "· 有在岗记录 $workdays 天，合计在岗 " +
                        OvertimeCalculator.formatDuration(totalWork),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "· 标准工时 ${state.settings.standardHours} 小时/天" +
                        (if (state.settings.weekendOvertime) "，周末全天计加班" else "") +
                        (if (state.settings.holidayOvertime) "，节假日全天计加班" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
