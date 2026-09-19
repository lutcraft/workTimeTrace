package com.worktime.trace.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worktime.trace.stats.DayStat
import com.worktime.trace.stats.OvertimeCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val zone: ZoneId = ZoneId.systemDefault()
private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 加班时长紧凑格式：1.5h / 45m / 2h（日历格子内空间有限）。 */
private fun otCompact(ms: Long): String {
    val h = ms / 3600_000.0
    return if (h >= 1f) "+" + String.format("%.1f", h) + "h"
    else "+" + (ms / 60_000) + "m"
}

@Composable
fun CalendarScreen(vm: CalendarViewModel) {
    val state by vm.uiState.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val today = remember(state.now) { Instant.ofEpochMilli(state.now).atZone(zone).toLocalDate() }

    val stats = remember(state.events, state.settings, state.now) {
        OvertimeCalculator.computeDayStats(state.events, state.settings, state.now)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // 月份切换
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀ 上月") }
            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("下月 ▶") }
        }

        // 星期表头（周一开头）
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    it,
                    Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 月历网格
        val offset = month.atDay(1).dayOfWeek.value - 1 // 周一=0
        val daysInMonth = month.lengthOfMonth()
        val rows = ((offset + daysInMonth) + 6) / 7
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val day = r * 7 + c - offset + 1
                            if (day in 1..daysInMonth) {
                                val date = month.atDay(day)
                                DayCell(
                                    date = date,
                                    stat = stats[date],
                                    isToday = date == today,
                                    isFuture = date.isAfter(today),
                                    onClick = { selected = date },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Spacer(Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }
            }
        }

        // 当月合计
        val monthStats = stats.filterKeys { YearMonth.from(it) == month }.values
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val ot = monthStats.sumOf { it.overtimeMs }
                val otDays = monthStats.count { it.overtimeMs > 0 }
                Text(
                    "本月加班：" + OvertimeCalculator.formatDuration(ot) +
                        if (otDays > 0) "（$otDays 天）" else "",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "在岗合计 " + OvertimeCalculator.formatDuration(monthStats.sumOf { it.workMs }) +
                        " · 红=有加班 · 灰=休息日 · 点击日期看明细",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // 单日明细
    selected?.let { date ->
        val stat = stats[date]
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(
                    "${date.monthValue}月${date.dayOfMonth}日 " +
                        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINESE) +
                        if (stat?.restDay == true) " · 休" else ""
                )
            },
            text = {
                if (stat == null) {
                    Text("当天无在岗记录")
                } else {
                    Column {
                        stat.intervals.forEach {
                            Text(
                                "· ${fmtHm(it.startMs)} – " +
                                    if (it.endMs == Long.MAX_VALUE) "（仍在岗）" else fmtHm(it.endMs),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在岗 " + OvertimeCalculator.formatDuration(stat.workMs) +
                                " · 加班 " + OvertimeCalculator.formatDuration(stat.overtimeMs),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (stat.overtimeMs > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    stat: DayStat?,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clickable(enabled = stat != null, onClick = onClick),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 日号：今天带圆形高亮
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .background(
                        if (isToday) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        stat?.restDay == true -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            // 加班时长（红色）
            val ot = stat?.overtimeMs ?: 0L
            if (ot > 0) {
                Text(
                    otCompact(ot),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (stat != null && stat.restDay) {
                Text(
                    "休",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isFuture) {
                // 未来日期无标记
            }
        }
    }
}

private fun fmtHm(ms: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(hmFmt)
