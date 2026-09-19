package com.worktime.trace.stats

import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.EventType
import com.worktime.trace.data.prefs.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** 一段在岗区间 [startMs, endMs)，epoch 毫秒。 */
data class PresenceInterval(val startMs: Long, val endMs: Long)

/** 一天的统计结果。 */
data class DayStat(
    val date: LocalDate,
    /** 在岗总时长（毫秒） */
    val workMs: Long,
    /** 加班时长（毫秒） */
    val overtimeMs: Long,
    /** 当天第一次进入时间；无记录为 null */
    val firstInMs: Long?,
    /** 当天最后离开时间；仍在岗为当前时间；无记录为 null */
    val lastOutMs: Long?,
    /** 合并后的在岗区间（用于明细展示） */
    val intervals: List<PresenceInterval>,
    /** 是否休息日（周末/节假日，扣除调休） */
    val restDay: Boolean,
    /** 节假日名（休息日且为法定节假日时有值） */
    val holidayName: String?
)

/**
 * 由原始进出事件计算每日在岗/加班时长。
 *
 * - ENTER 与其后同工作地点最近的 EXIT 配对为一段在岗区间；
 * - 未闭合的 ENTER 以 [nowMs] 收尾（表示当前仍在岗）；
 * - 跨午夜的区间按自然日切分，同一天多段区间做并集（去重叠）；
 * - 加班规则：休息日全天在岗计加班；工作日超出标准工时的部分计加班。
 */
object OvertimeCalculator {

    /**
     * @param events 全量事件（任意顺序）
     * @param settings 统计规则
     * @param nowMs “当前时间”，用于闭合未结束的区间
     */
    fun computeDayStats(
        events: List<AttendanceEventEntity>,
        settings: AppSettings,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<LocalDate, DayStat> {
        val perDay = HashMap<LocalDate, MutableList<PresenceInterval>>()

        for (interval in buildIntervals(events, nowMs)) {
            var dayStartMs = startOfLocalDate(interval.startMs, zone)
            while (dayStartMs < interval.endMs) {
                val nextDayMs = dayStartMs + 24L * 3600_000L
                val s = max(interval.startMs, dayStartMs)
                val e = minOf(interval.endMs, nextDayMs)
                if (e > s) {
                    val date = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
                    perDay.getOrPut(date) { mutableListOf() }.add(PresenceInterval(s, e))
                }
                dayStartMs = nextDayMs
            }
        }

        val standardMs = (settings.standardHours * 3600_000L).toLong()
        val result = HashMap<LocalDate, DayStat>(perDay.size)
        for ((date, list) in perDay) {
            val merged = mergeIntervals(list)
            val workMs = merged.sumOf { it.endMs - it.startMs }
            val holidayName = HolidayCalendar.holidayName(date)
            val restDay = HolidayCalendar.isRestDay(date)
            val overtimeMs = if (restDay) {
                val ruleOn = if (holidayName != null) settings.holidayOvertime
                else settings.weekendOvertime
                if (ruleOn) workMs else 0L
            } else {
                max(0L, workMs - standardMs)
            }
            result[date] = DayStat(
                date = date,
                workMs = workMs,
                overtimeMs = overtimeMs,
                firstInMs = merged.firstOrNull()?.startMs,
                lastOutMs = merged.lastOrNull()?.endMs,
                intervals = merged,
                restDay = restDay,
                holidayName = holidayName
            )
        }
        return result
    }

    /** 当前是否有任意工作地点处于“在岗”状态（存在未闭合的 ENTER）。 */
    fun currentlyInside(events: List<AttendanceEventEntity>): Boolean =
        buildIntervals(events, Long.MAX_VALUE).any { it.endMs == Long.MAX_VALUE }

    /** 当前在岗会话的开始时间（最近一次未闭合的 ENTER）；不在岗返回 null。 */
    fun currentSessionStartMs(events: List<AttendanceEventEntity>): Long? {
        val open = HashMap<Long, Long>()
        for (ev in events.sortedBy { it.timestamp }) {
            when (ev.type) {
                EventType.ENTER -> open[ev.workplaceId] = ev.timestamp
                EventType.EXIT -> open.remove(ev.workplaceId)
            }
        }
        return open.values.maxOrNull()
    }

    /** 将事件序列配对为在岗区间。 */
    private fun buildIntervals(
        events: List<AttendanceEventEntity>,
        closingMs: Long
    ): List<PresenceInterval> {
        val sorted = events.sortedBy { it.timestamp }
        val open = HashMap<Long, Long>() // workplaceId -> enterMs
        val out = ArrayList<PresenceInterval>(sorted.size / 2 + 1)
        for (ev in sorted) {
            when (ev.type) {
                EventType.ENTER -> {
                    open[ev.workplaceId]?.let { prevEnter ->
                        // 稳健性：连续两次 ENTER，用新的 ENTER 时间闭合上一段
                        if (prevEnter < ev.timestamp) {
                            out.add(PresenceInterval(prevEnter, ev.timestamp))
                        }
                    }
                    open[ev.workplaceId] = ev.timestamp
                }
                EventType.EXIT -> {
                    val enterMs = open.remove(ev.workplaceId)
                    if (enterMs != null && enterMs <= ev.timestamp) {
                        out.add(PresenceInterval(enterMs, ev.timestamp))
                    }
                    // 无配对 ENTER 的 EXIT（手动补录/数据缺失）忽略
                }
            }
        }
        // 未闭合区间以 closingMs 收尾
        for ((_, enterMs) in open) {
            out.add(PresenceInterval(enterMs, closingMs))
        }
        return out
    }

    /** 排序并合并重叠区间。 */
    private fun mergeIntervals(list: List<PresenceInterval>): List<PresenceInterval> {
        if (list.isEmpty()) return emptyList()
        val sorted = list.sortedBy { it.startMs }
        val merged = ArrayList<PresenceInterval>(sorted.size)
        for (iv in sorted) {
            val last = merged.lastOrNull()
            if (last != null && iv.startMs <= last.endMs) {
                merged[merged.size - 1] = PresenceInterval(last.startMs, max(last.endMs, iv.endMs))
            } else {
                merged.add(iv)
            }
        }
        return merged
    }

    private fun startOfLocalDate(epochMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()

    /** 时长格式化：x小时y分钟 */
    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h <= 0 -> "$m 分钟"
            else -> "$h 小时 $m 分钟"
        }
    }
}
