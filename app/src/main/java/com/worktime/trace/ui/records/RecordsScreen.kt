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
import androidx.compose.material.icons.filled.Edit
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
import kotlin.math.roundToInt

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm")
private val zone: ZoneId = ZoneId.systemDefault()

@Composable
fun RecordsScreen(vm: RecordsViewModel) {
    val state by vm.uiState.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var expandedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<AttendanceEventEntity?>(null) }

    val stats = remember(state.events, state.settings, state.now) {
        OvertimeCalculator.computeDayStats(state.events, state.settings, state.now)
    }
    // 展示日 = 统计有区间的日子 ∪ 有原始事件的日子（孤立上班/下班也显示）
    val monthDays = remember(state.events, stats, month) {
        val eventDays = state.events.map {
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
        }.filter { YearMonth.from(it) == month }
        val statDays = stats.keys.filter { YearMonth.from(it) == month }
        (eventDays + statDays).distinct().sortedDescending()
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
                        "标准工时 " + OvertimeCalculator.formatDuration(
                            (state.settings.standardHours * 60).roundToInt() * 60_000L
                        ) + "/天" +
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
                date = date,
                stat = stats[date],
                events = eventsOfNaturalDay(state.events, date),
                workplaceNames = workplaceNames,
                expanded = expandedDate == date,
                onToggle = { expandedDate = if (expandedDate == date) null else date },
                onDeleteEvent = { vm.deleteEvent(it) },
                onDeleteDay = { vm.deleteEventsOfDay(date) },
                onEditEvent = { editingEvent = it }
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

    if (showManualDialog || editingEvent != null) {
        ManualEventDialog(
            workplaces = state.workplaces,
            initialEvent = editingEvent,
            onDismiss = {
                showManualDialog = false
                editingEvent = null
            },
            onConfirm = { wpId, isEnter, epochMs ->
                val target = editingEvent
                if (target != null) {
                    vm.updateEvent(
                        target.copy(
                            workplaceId = wpId,
                            type = if (isEnter) EventType.ENTER else EventType.EXIT,
                            timestamp = epochMs
                        )
                    )
                } else {
                    vm.addManualEvent(wpId, isEnter, epochMs)
                }
                showManualDialog = false
                editingEvent = null
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
    date: LocalDate,
    stat: DayStat?,
    events: List<AttendanceEventEntity>,
    workplaceNames: Map<Long, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteEvent: (AttendanceEventEntity) -> Unit,
    onDeleteDay: () -> Unit,
    onEditEvent: (AttendanceEventEntity) -> Unit
) {
    var confirmDeleteDay by remember { mutableStateOf(false) }
    if (confirmDeleteDay) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDay = false },
            title = { Text("删除当天记录") },
            text = { Text("将删除 ${date.monthValue}月${date.dayOfMonth}日 的全部打卡记录（${events.size} 条），不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteDay = false
                    onDeleteDay()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDay = false }) { Text("取消") } }
        )
    }

    val restDay = stat?.restDay ?: com.worktime.trace.stats.HolidayCalendar.isRestDay(date)
    val holidayName = stat?.holidayName ?: com.worktime.trace.stats.HolidayCalendar.holidayName(date)

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
                            "${date.monthValue}月${date.dayOfMonth}日 " + date.dayOfWeek.displayZh(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (restDay) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                holidayName ?: "休",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    if (stat != null) {
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
                    } else {
                        Text(
                            "记录未配对（${events.size} 条）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (stat != null && stat.overtimeMs > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "+" + OvertimeCalculator.formatDuration(stat.overtimeMs),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text("加班", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { confirmDeleteDay = true }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "删除当天记录",
                        Modifier.height(18.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "展开"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (stat != null && stat.intervals.isNotEmpty()) {
                        Text("在岗时段", style = MaterialTheme.typography.labelMedium)
                        stat.intervals.forEach { iv ->
                            Text(
                                "· ${fmt(iv.startMs)} – " +
                                    if (iv.endMs == Long.MAX_VALUE) "（仍在岗）" else fmt(iv.endMs),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
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
                            IconButton(onClick = { onEditEvent(ev) }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "编辑",
                                    Modifier.height(18.dp)
                                )
                            }
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
    onConfirm: (workplaceId: Long, isEnter: Boolean, epochMs: Long) -> Unit,
    /** 编辑已有记录时传入（补录新增传 null） */
    initialEvent: AttendanceEventEntity? = null
) {
    val editing = initialEvent != null
    val initTime = initialEvent?.let {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(it.timestamp), zone)
    }
    var isEnter by remember { mutableStateOf(initialEvent?.type == EventType.ENTER) }
    var wpId by remember { mutableStateOf(initialEvent?.workplaceId ?: workplaces.firstOrNull()?.id ?: -1L) }
    var pickDate by remember { mutableStateOf<LocalDate?>(initTime?.toLocalDate()) }
    // 编辑时日期已知，跳过日期步骤直接选时间；补录仍先选日期
    var showDatePicker by remember { mutableStateOf(!editing) }
    var showTimePicker by remember { mutableStateOf(editing) }

    if (workplaces.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (editing) "无法编辑" else "无法补录") },
            text = { Text("请先在主页添加工作地点。") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        )
        return
    }

    if (showDatePicker) {
        // DatePicker 以 UTC 0 点定位日期，需把本地时间戳转成"本地日期的 UTC 0 点"
        val initialDayMs = initialEvent?.timestamp?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone).toLocalDate()
                .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        } ?: System.currentTimeMillis()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = initialDayMs
        )
        // 编辑模式：取消日期选择返回时间页（日期只是可选修改项）；补录模式取消关闭
        val cancelDate: () -> Unit = if (editing) {
            { showDatePicker = false }
        } else onDismiss
        DatePickerDialog(
            onDismissRequest = cancelDate,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { ms ->
                        pickDate = Instant.ofEpochMilli(ms)
                            .atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text(if (editing) "确定" else "下一步") }
            },
            dismissButton = { TextButton(onClick = cancelDate) { Text("取消") } }
        ) {
            DatePicker(state = dateState)
        }
        if (!editing) return
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = initTime?.hour ?: 9,
            initialMinute = initTime?.minute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (editing) "编辑记录" else "选择时间") },
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
                    if (editing) {
                        // 编辑时日期默认为原记录日期，点击可修改
                        val d = pickDate ?: LocalDate.now()
                        Spacer(Modifier.height(8.dp))
                        FilterChip(
                            selected = false,
                            onClick = { showDatePicker = true },
                            label = { Text("📅 ${d.monthValue}月${d.dayOfMonth}日") }
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
