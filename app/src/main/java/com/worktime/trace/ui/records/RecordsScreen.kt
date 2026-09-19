package com.worktime.trace.ui.records

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.EventType
import com.worktime.trace.data.prefs.AppSettings
import com.worktime.trace.stats.DayStat
import com.worktime.trace.stats.OvertimeCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")
private val zone: ZoneId = ZoneId.systemDefault()

@Composable
fun RecordsScreen(vm: RecordsViewModel) {
    val state by vm.uiState.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var expandedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }

    val stats = remember(state.events, state.settings, state.now) {
        OvertimeCalculator.computeDayStats(state.events, state.settings, state.now)
    }
    val monthDays = remember(stats, month) {
        stats.filterKeys { YearMonth.from(it) == month }.keys.sortedDescending()
    }
    val workplaceNames = remember(state.workplaces) {
        state.workplaces.associate { it.id to it.name }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("◀ 上月") }
                Text(
                    "${month.year}年${month.monthValue}月",
                    style = MaterialTheme.typography.titleLarge
                )
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("下月 ▶") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val days = monthDays.mapNotNull { stats[it] }
                    Text(
                        "本月：在岗 " + OvertimeCalculator.formatDuration(days.sumOf { it.workMs }) +
                            " · 加班 " + OvertimeCalculator.formatDuration(days.sumOf { it.overtimeMs }),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "标准工时 ${state.settings.standardHours} 小时/天" +
                            if (state.settings.holidayOvertime) "，节假日全天计加班" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (monthDays.isEmpty()) {
            item {
                Text(
                    "本月暂无记录",
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        items(monthDays.size) { idx ->
            val date = monthDays[idx]
            DayCard(
                stat = stats[date] ?: return@items,
                events = eventsOfNaturalDay(state.events, date),
                workplaceNames = workplaceNames,
                expanded = expandedDate == date,
                onToggle = { expandedDate = if (expandedDate == date) null else date },
                onDeleteEvent = { vm.deleteEvent(it) }
            )
        }

        item {
            OutlinedButton(
                onClick = { showManualDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("补录上下班记录")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showManualDialog) {
        ManualEventDialog(
            workplaces = state.workplaces,
            onDismiss = { showManualDialog = false },
            onConfirm = { wpId, isEnter, epochMs ->
                vm.addManualEvent(wpId, isEnter, epochMs)
                showManualDialog = false
            }
        )
    }
}

private fun eventsOfNaturalDay(
    events: List<AttendanceEventEntity>,
    date: LocalDate
): List<AttendanceEventEntity> {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return events.filter { it.timestamp in start until end }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayCard(
    stat: DayStat,
    events: List<AttendanceEventEntity>,
    workplaceNames: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteEvent: (AttendanceEventEntity) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${stat.date.monthValue}月${stat.date.dayOfMonth}日 " +
                                stat.date.dayOfWeek.displayZh(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (stat.restDay) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stat.holidayName ?: "休",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    val inOut = listOfNotNull(stat.firstInMs, stat.lastOutMs)
                    if (inOut.size == 2) {
                        Text(
                            "${fmt(inOut[0])} – ${fmt(inOut[1])} · 在岗 " +
                                OvertimeCalculator.formatDuration(stat.workMs),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (inOut.isNotEmpty()) {
                        Text(
                            "${fmt(inOut[0])} – · 在岗 " +
                                OvertimeCalculator.formatDuration(stat.workMs),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (stat.overtimeMs > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "+" + OvertimeCalculator.formatDuration(stat.overtimeMs),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text("加班", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "展开"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("在岗时段", style = MaterialTheme.typography.labelMedium)
                    stat.intervals.forEach { iv ->
                        Text(
                            "· ${fmt(iv.startMs)} – " +
                                if (iv.endMs == Long.MAX_VALUE) "（仍在岗）" else fmt(iv.endMs),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("原始记录", style = MaterialTheme.typography.labelMedium)
                    events.forEach { ev ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (ev.type == EventType.ENTER) "上班 " else "下班 ") + fmt(ev.timestamp) +
                                    if (ev.manual) "（补录）" else "",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                workplaceNames[ev.workplaceId] ?: "#${ev.workplaceId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { onDeleteEvent(ev) }) {
                                Icon(
                                    Icons.Filled.DeleteOutline,
                                    contentDescription = "删除",
                                    Modifier.height(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEventDialog(
    workplaces: List<com.worktime.trace.data.db.WorkplaceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (workplaceId: Long, isEnter: Boolean, epochMs: Long) -> Unit
) {
    var isEnter by remember { mutableStateOf(true) }
    var wpId by remember { mutableStateOf(workplaces.firstOrNull()?.id ?: -1L) }
    var pickDate by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(true) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (workplaces.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("无法补录") },
            text = { Text("请先在主页添加工作地点。") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        )
        return
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        pickDate = Instant.ofEpochMilli(ms)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text("下一步") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        ) {
            DatePicker(state = dateState)
        }
        return
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("选择时间") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isEnter,
                            onClick = { isEnter = true },
                            label = { Text("上班") }
                        )
                        FilterChip(
                            selected = !isEnter,
                            onClick = { isEnter = false },
                            label = { Text("下班") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    workplaces.forEach { wp ->
                        FilterChip(
                            selected = wpId == wp.id,
                            onClick = { wpId = wp.id },
                            label = { Text(wp.name) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickDate ?: LocalDate.now()
                    val time = LocalTime.of(timeState.hour, timeState.minute)
                    val epochMs = LocalDateTime.of(date, time)
                        .atZone(zone).toInstant().toEpochMilli()
                    onConfirm(wpId, isEnter, epochMs)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}

private fun fmt(ms: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(hmFmt)

private fun java.time.DayOfWeek.displayZh(): String =
    getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.CHINESE)
