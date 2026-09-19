package com.worktime.trace.stats

import java.time.LocalDate
import java.time.MonthDay

/**
 * 中国法定节假日与调休日历（国务院办公厅通知）。
 *
 * 内置 2025、2026 年安排；未收录的年份退化为“仅按周末判断”。
 */
object HolidayCalendar {

    private data class YearPlan(
        /** 放假日期 → 节日名 */
        val holidays: Map<MonthDay, String>,
        /** 调休上班的周末日期 */
        val adjustedWorkdays: Set<MonthDay>
    )

    private val plans = mapOf(
        2025 to YearPlan(
            holidays = buildMap {
                // 元旦
                put(MonthDay.of(1, 1), "元旦")
                // 春节：1/28(除夕)-2/4
                for (d in 28..31) put(MonthDay.of(1, d), "春节")
                for (d in 1..4) put(MonthDay.of(2, d), "春节")
                // 清明：4/4-4/6
                for (d in 4..6) put(MonthDay.of(4, d), "清明节")
                // 劳动节：5/1-5/5
                for (d in 1..5) put(MonthDay.of(5, d), "劳动节")
                // 端午：5/31-6/2
                put(MonthDay.of(5, 31), "端午节")
                for (d in 1..2) put(MonthDay.of(6, d), "端午节")
                // 国庆中秋：10/1-10/8
                for (d in 1..8) put(MonthDay.of(10, d), if (d == 1) "国庆节" else "国庆·中秋")
            },
            adjustedWorkdays = setOf(
                MonthDay.of(1, 26), // 周日
                MonthDay.of(2, 8),  // 周六
                MonthDay.of(4, 27), // 周日
                MonthDay.of(9, 28), // 周日
                MonthDay.of(10, 11) // 周六
            )
        ),
        2026 to YearPlan(
            holidays = buildMap {
                // 元旦：1/1-1/3
                for (d in 1..3) put(MonthDay.of(1, d), "元旦")
                // 春节：2/15-2/23
                for (d in 15..23) put(MonthDay.of(2, d), "春节")
                // 清明：4/4-4/6
                for (d in 4..6) put(MonthDay.of(4, d), "清明节")
                // 劳动节：5/1-5/5
                for (d in 1..5) put(MonthDay.of(5, d), "劳动节")
                // 端午：6/19-6/21
                for (d in 19..21) put(MonthDay.of(6, d), "端午节")
                // 中秋：9/25-9/27
                for (d in 25..27) put(MonthDay.of(9, d), "中秋节")
                // 国庆：10/1-10/7
                for (d in 1..7) put(MonthDay.of(10, d), "国庆节")
            },
            adjustedWorkdays = setOf(
                MonthDay.of(1, 4),  // 周日
                MonthDay.of(2, 14), // 周六
                MonthDay.of(2, 28), // 周六
                MonthDay.of(5, 9),  // 周六
                MonthDay.of(9, 20), // 周日
                MonthDay.of(10, 10) // 周六
            )
        )
    )

    /** 是否法定节假日（放假） */
    fun isHoliday(date: LocalDate): Boolean = holidayName(date) != null

    /** 节假日名称，非节假日返回 null */
    fun holidayName(date: LocalDate): String? =
        plans[date.year]?.holidays?.get(MonthDay.of(date.monthValue, date.dayOfMonth))

    /** 是否调休上班日 */
    private fun isAdjustedWorkday(date: LocalDate): Boolean =
        plans[date.year]?.adjustedWorkdays?.contains(MonthDay.of(date.monthValue, date.dayOfMonth))
            ?: false

    /**
     * 是否休息日：节假日或普通周末（调休上班的周末除外）。
     * 休息日在岗按加班规则全天计入。
     */
    fun isRestDay(date: LocalDate): Boolean = when {
        isHoliday(date) -> true
        isAdjustedWorkday(date) -> false
        else -> date.dayOfWeek.value >= 6 // 周六=6 周日=7
    }
}
