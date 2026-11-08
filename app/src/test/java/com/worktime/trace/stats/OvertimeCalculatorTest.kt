package com.worktime.trace.stats

import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.EventType
import com.worktime.trace.data.prefs.AppSettings
import com.worktime.trace.data.prefs.CalcMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 核心工时计算引擎单元测试：双统计模式、加班规则、跨午夜切分、未闭合区间。
 */
class OvertimeCalculatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun ms(date: String, hour: Int, minute: Int = 0): Long {
        val d = LocalDate.parse(date)
        return LocalDateTime.of(d.year, d.monthValue, d.dayOfMonth, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()
    }

    private fun ev(
        wpId: Long,
        type: EventType,
        timestamp: Long,
        manual: Boolean = false
    ) = AttendanceEventEntity(
        workplaceId = wpId,
        type = type,
        timestamp = timestamp,
        latitude = null,
        longitude = null,
        manual = manual
    )

    // ---- 双工时统计模式 ----

    @Test
    fun `多段累计模式 - 中午外出就餐不计入在岗`() {
        // 9:00 进 12:00 出（3h），13:00 进 18:00 出（5h）→ 并集 8h
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 9)),
            ev(1, EventType.EXIT, ms("2026-11-02", 12)),
            ev(1, EventType.ENTER, ms("2026-11-02", 13)),
            ev(1, EventType.EXIT, ms("2026-11-02", 18))
        )
        val union = OvertimeCalculator.computeDayStats(
            events, AppSettings(calcMode = CalcMode.INTERVAL_UNION), ms("2026-11-02", 23)
        ).values.single()
        assertEquals(8 * 3600_000L, union.workMs)
        assertEquals(0L, union.overtimeMs)

        // 首进末出模式：同数据 → 9:00-18:00 = 9h，加班 1h
        val span = OvertimeCalculator.computeDayStats(
            events, AppSettings(calcMode = CalcMode.FIRST_IN_LAST_OUT), ms("2026-11-02", 23)
        ).values.single()
        assertEquals(9 * 3600_000L, span.workMs)
        assertEquals(3600_000L, span.overtimeMs)
    }

    @Test
    fun `首进末出模式 - 定位漂移裂隙被忽略`() {
        // 8:00 进，10:00 漂出 10:10 回（围栏边缘抖动），18:00 出
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 8)),
            ev(1, EventType.EXIT, ms("2026-11-02", 10)),
            ev(1, EventType.ENTER, ms("2026-11-02", 10, 10)),
            ev(1, EventType.EXIT, ms("2026-11-02", 18))
        )
        val span = OvertimeCalculator.computeDayStats(
            events, AppSettings(calcMode = CalcMode.FIRST_IN_LAST_OUT), ms("2026-11-02", 23)
        ).values.single()
        assertEquals(10 * 3600_000L, span.workMs)
    }

    @Test
    fun `多段累计模式 - 重叠区间去重`() {
        // 两个工作地点围栏同时覆盖，区间重叠 1h
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 9)),
            ev(1, EventType.EXIT, ms("2026-11-02", 17)),
            ev(2, EventType.ENTER, ms("2026-11-02", 16)),
            ev(2, EventType.EXIT, ms("2026-11-02", 18))
        )
        val stat = OvertimeCalculator.computeDayStats(
            events, AppSettings(calcMode = CalcMode.INTERVAL_UNION), ms("2026-11-02", 23)
        ).values.single()
        // 9-18 扣重叠部分：9h（并集），不是 10h
        assertEquals(9 * 3600_000L, stat.workMs)
    }

    // ---- 加班规则 ----

    @Test
    fun `工作日超出标准工时部分计加班`() {
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 9)),
            ev(1, EventType.EXIT, ms("2026-11-02", 21))
        )
        // 标准 8h → 12h 在岗，加班 4h
        val stat = OvertimeCalculator.computeDayStats(
            events, AppSettings(standardHours = 8f), ms("2026-11-02", 23)
        ).values.single()
        assertEquals(12 * 3600_000L, stat.workMs)
        assertEquals(4 * 3600_000L, stat.overtimeMs)
    }

    @Test
    fun `周末在岗全天计加班`() {
        // 2026-11-07 是周六
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-07", 10)),
            ev(1, EventType.EXIT, ms("2026-11-07", 14))
        )
        val stat = OvertimeCalculator.computeDayStats(
            events, AppSettings(), ms("2026-11-07", 23)
        ).values.single()
        assertEquals(true, stat.restDay)
        assertEquals(4 * 3600_000L, stat.overtimeMs)
    }

    @Test
    fun `调休补班的周末不算休息日`() {
        // 2026-02-14（周六）是 2026 春节调休补班日（HolidayCalendar 内置安排）
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-02-14", 9)),
            ev(1, EventType.EXIT, ms("2026-02-14", 18))
        )
        val stat = OvertimeCalculator.computeDayStats(
            events, AppSettings(), ms("2026-02-14", 23)
        ).values.single()
        // 补班日按工作日算：9h 在岗只加 1h，而非全天加班
        assertEquals(false, stat.restDay)
        assertEquals(3600_000L, stat.overtimeMs)
    }

    // ---- 跨午夜切分与未闭合区间 ----

    @Test
    fun `跨午夜区间按自然日切分`() {
        // 23:00 进，次日 02:00 出
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 23)),
            ev(1, EventType.EXIT, ms("2026-11-03", 2))
        )
        val stats = OvertimeCalculator.computeDayStats(
            events, AppSettings(), ms("2026-11-03", 12)
        )
        assertEquals(2, stats.size)
        assertEquals(3600_000L, stats[LocalDate.parse("2026-11-02")]!!.workMs)
        assertEquals(2 * 3600_000L, stats[LocalDate.parse("2026-11-03")]!!.workMs)
    }

    @Test
    fun `未闭合的 ENTER 以 nowMs 收尾`() {
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 9))
        )
        val now = ms("2026-11-02", 20)
        val stat = OvertimeCalculator.computeDayStats(events, AppSettings(), now).values.single()
        assertEquals(11 * 3600_000L, stat.workMs)
        assertEquals(true, OvertimeCalculator.currentlyInside(events))
        assertEquals(ms("2026-11-02", 9), OvertimeCalculator.currentSessionStartMs(events))
    }

    // ---- 稳健性：异常数据 ----

    @Test
    fun `连续两次 ENTER 用新时间闭合上一段`() {
        val events = listOf(
            ev(1, EventType.ENTER, ms("2026-11-02", 8)),
            ev(1, EventType.ENTER, ms("2026-11-02", 9))
        )
        val stat = OvertimeCalculator.computeDayStats(
            events, AppSettings(), ms("2026-11-02", 18)
        ).values.single()
        // 8-9 一段闭合 + 9-18 未闭合 = 10h
        assertEquals(10 * 3600_000L, stat.workMs)
    }

    @Test
    fun `无配对 ENTER 的 EXIT 被忽略`() {
        val events = listOf(
            ev(1, EventType.EXIT, ms("2026-11-02", 18))
        )
        val stats = OvertimeCalculator.computeDayStats(
            events, AppSettings(), ms("2026-11-02", 23)
        )
        assertEquals(0, stats.size)
    }

    // ---- 新增设置项默认值 ----

    @Test
    fun `CalcMode 默认为首进末出`() {
        assertEquals(CalcMode.FIRST_IN_LAST_OUT, AppSettings().calcMode)
    }
}
