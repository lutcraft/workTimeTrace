package com.worktime.trace.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worktime.trace.stats.DayStat
import com.worktime.trace.stats.HolidayCalendar
import com.worktime.trace.stats.OvertimeCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val zone: ZoneId = ZoneId.systemDefault()
private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")

/** 日历高亮筛选：全部日期 / 加班日 / 请假或缺勤日。 */
private enum class CalendarHighlightFilter(val label: String) {
    ALL("全部日期"),
    OVERTIME("加班日"),
    LEAVE_ABSENT("请假/缺勤")
}

/**
 * 过去的工作日中，无任何在岗记录（全天请假/缺勤）或工时明显不足（半天假/早退）判定。
 * 今天及未来日期、休息日不参与判定。
 */
private fun isLeaveOrAbsentDay(
    date: LocalDate,
    stat: DayStat?,
    standardMs: Long,
    today: LocalDate
): Boolean {
    if (!date.isBefore(today)) return false
    if (HolidayCalendar.isRestDay(date)) return false
    val workMs = stat?.workMs ?: 0L
    return workMs == 0L || workMs < (standardMs * 0.85).toLong()
}

/** 热力格子视觉参数：背景色、是否浅色文字、色阶档位（1-5，0 为无记录）。 */
private data class HeatCell(val color: Color, val lightText: Boolean, val level: Int)

/**
 * 五档工时热力图配色（Niu 视觉稿 6 档色阶：0h/<4h/4-8h/8-10h/10-12h/≥12h）。
 */
@Composable
private fun heatmapCellColors(workMs: Long): HeatCell {
    val dark = isSystemInDarkTheme()
    val hours = workMs / 3600_000.0
    if (hours <= 0.0) return HeatCell(Color.Transparent, false, 0)
    // level: 1..5 对应 Niu 的色阶（0 为无记录）
    val level = when {
        hours < 4.0 -> 1
        hours < 8.0 -> 2
        hours < 10.0 -> 3
        hours < 12.0 -> 4
        else -> 5
    }
    val color = when (level) {
        1 -> if (dark) Color(0xFF26A688).copy(alpha = 0.20f) else Color(0xFF1E6E5C).copy(alpha = 0.15f)
        2 -> if (dark) Color(0xFF26A688).copy(alpha = 0.38f) else Color(0xFF1E6E5C).copy(alpha = 0.32f)
        3 -> if (dark) Color(0xFF26A688).copy(alpha = 0.60f) else Color(0xFF1E6E5C).copy(alpha = 0.55f)
        4 -> if (dark) Color(0xFF26A688).copy(alpha = 0.80f) else Color(0xFF1E6E5C).copy(alpha = 0.78f)
        else -> if (dark) Color(0xFF1E8E73) else Color(0xFF114B3E)
    }
    return HeatCell(color, level >= 3, level)
}

/** 工时紧凑小数格式：3.2h / 0.5h（Niu 日历格内展示）。 */
private fun hoursCompact(ms: Long): String {
    val h = ms / 3600_000.0
    return String.format("%.1f", h) + "h"
}

/** 图例色阶（随深浅色模式），与 heatmapCellColors 的 6 档一一对应。 */
@Composable
private fun legendColors(): List<Color> {
    val dark = isSystemInDarkTheme()
    return listOf(
        MaterialTheme.colorScheme.surface,
        if (dark) Color(0xFF26A688).copy(alpha = 0.20f) else Color(0xFF1E6E5C).copy(alpha = 0.15f),
        if (dark) Color(0xFF26A688).copy(alpha = 0.38f) else Color(0xFF1E6E5C).copy(alpha = 0.32f),
        if (dark) Color(0xFF26A688).copy(alpha = 0.60f) else Color(0xFF1E6E5C).copy(alpha = 0.55f),
        if (dark) Color(0xFF26A688).copy(alpha = 0.80f) else Color(0xFF1E6E5C).copy(alpha = 0.78f),
        if (dark) Color(0xFF1E8E73) else Color(0xFF114B3E)
    )
}

@Composable
fun CalendarScreen(vm: CalendarViewModel) {
    val state by vm.uiState.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var filter by remember { mutableStateOf(CalendarHighlightFilter.ALL) }
    val today = remember(state.now) { Instant.ofEpochMilli(state.now).atZone(zone).toLocalDate() }
    val standardMs = remember(state.settings.standardHours) {
        (state.settings.standardHours * 60).roundToInt() * 60_000L
    }

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

        // 筛选器：全部日期 / 加班日 / 请假缺勤（带当月计数，再次点击已选项回到全部）
        val otCount = stats.count {
            YearMonth.from(it.key) == month && it.value.overtimeMs > 0L
        }
        val leaveCount = stats.count { (d, st) ->
            YearMonth.from(d) == month && isLeaveOrAbsentDay(d, st, standardMs, today)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CalendarHighlightFilter.entries.forEach { item ->
                val count = when (item) {
                    CalendarHighlightFilter.OVERTIME -> otCount
                    CalendarHighlightFilter.LEAVE_ABSENT -> leaveCount
                    CalendarHighlightFilter.ALL -> null
                }
                FilterChip(
                    selected = filter == item,
                    onClick = {
                        filter = if (filter == item && item != CalendarHighlightFilter.ALL) {
                            CalendarHighlightFilter.ALL
                        } else item
                    },
                    label = {
                        Text(
                            if (count != null) "${item.label} ($count)" else item.label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
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
                                val st = stats[date]
                                val leaveOrAbsent = isLeaveOrAbsentDay(date, st, standardMs, today)
                                DayCell(
                                    date = date,
                                    stat = st,
                                    isToday = date == today,
                                    isFuture = date.isAfter(today),
                                    leaveOrAbsent = leaveOrAbsent,
                                    filter = filter,
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

        // 热力图色阶图例（0h → <4h → 4-8h → 8-10h → 10-12h → ≥12h）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "工时热力：少",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                legendColors().forEach { c ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(c, RoundedCornerShape(3.dp))
                    )
                }
            }
            Text(
                "多 (≥12h)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        " · 颜色越深代表当日工时越长",
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
    leaveOrAbsent: Boolean,
    filter: CalendarHighlightFilter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val workMs = stat?.workMs ?: 0L
    val ot = stat?.overtimeMs ?: 0L
    val heat = heatmapCellColors(workMs)
    val matched = when (filter) {
        CalendarHighlightFilter.ALL -> true
        CalendarHighlightFilter.OVERTIME -> ot > 0L
        CalendarHighlightFilter.LEAVE_ABSENT -> leaveOrAbsent
    }
    val cellAlpha = if (matched) 1f else 0.22f
    val borderMod = when {
        filter == CalendarHighlightFilter.OVERTIME && ot > 0L ->
            Modifier.border(2.dp, Color(0xFFBA1A1A), RoundedCornerShape(10.dp))
        filter == CalendarHighlightFilter.LEAVE_ABSENT && leaveOrAbsent ->
            Modifier.border(2.dp, Color(0xFFB45309), RoundedCornerShape(10.dp))
        else -> Modifier
    }

    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .graphicsLayer(alpha = cellAlpha)
            .background(
                if (leaveOrAbsent && workMs == 0L) Color(0xFFFBBF24).copy(alpha = 0.20f) else heat.color,
                RoundedCornerShape(10.dp)
            )
            .then(borderMod)
            .clickable(enabled = stat != null || leaveOrAbsent, onClick = onClick),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 日号 + 休标记：今天带圆形高亮
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .background(
                        if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday || workMs > 0L) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        heat.lightText -> Color.White
                        stat?.restDay == true -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            if (workMs > 0L) {
                // 格内展示当日工时（小数），下有加班值则再显示一行
                Text(
                    hoursCompact(workMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (heat.lightText) Color.White.copy(alpha = 0.95f)
                    else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (ot > 0) {
                    Text(
                        "+" + hoursCompact(ot),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = if (heat.lightText) Color(0xFFFFDAD6) else MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else if (leaveOrAbsent) {
                    Text(
                        "请假/早退",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = Color(0xFFB45309)
                    )
                }
            } else if (leaveOrAbsent) {
                Text(
                    "缺勤",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFB45309)
                )
            } else if (stat != null && stat.restDay) {
                Text(
                    "休",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (heat.lightText) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (isFuture) {
                // 未来日期无标记
            }
        }
    }
}

private fun fmtHm(ms: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(hmFmt)
