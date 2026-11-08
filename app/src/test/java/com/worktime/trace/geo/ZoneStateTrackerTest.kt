package com.worktime.trace.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 动态迟滞双半径围栏跟踪器测试：进严出宽、精度膨胀过滤、劣质精度跳变忽略。
 */
class ZoneStateTrackerTest {

    @Test
    fun `进入围栏 - 距离小于半径立即确认`() {
        val t = ZoneStateTracker(exitConfirmations = 2, hysteresisMeters = 25f)
        assertEquals(true, t.onReadingWithAccuracy(1, distanceMeters = 90.0, radiusMeters = 100f, accuracyMeters = 10f))
    }

    @Test
    fun `离开围栏 - 迟滞带内不判定为离开`() {
        val t = ZoneStateTracker(exitConfirmations = 2, hysteresisMeters = 25f)
        t.onReadingWithAccuracy(1, 90.0, 100f, 10f) // 进入
        // 距离 110m：超过半径 100 但在迟滞带 100+25+5=130 内 → 不算离开
        assertNull(t.onReadingWithAccuracy(1, 110.0, 100f, 10f))
        assertEquals(true, t.currentInside(1))
    }

    @Test
    fun `离开围栏 - 超出迟滞带需连续两次确认`() {
        val t = ZoneStateTracker(exitConfirmations = 2, hysteresisMeters = 25f)
        t.onReadingWithAccuracy(1, 90.0, 100f, 10f) // 进入
        // 150m 超出迟滞带 130：第一次确认不算离开
        assertNull(t.onReadingWithAccuracy(1, 150.0, 100f, 10f))
        assertEquals(true, t.currentInside(1))
        // 第二次确认 → 离开
        assertEquals(false, t.onReadingWithAccuracy(1, 150.0, 100f, 10f))
        assertEquals(false, t.currentInside(1))
    }

    @Test
    fun `劣质精度跳变 - 在岗时精度超阈值忽略判定`() {
        val t = ZoneStateTracker(exitConfirmations = 2, hysteresisMeters = 25f)
        t.onReadingWithAccuracy(1, 90.0, 100f, 10f) // 进入
        // 电梯里精度突然劣化到 300m（> max(100*1.5, 250)）→ 本轮跳过，即使距离很远
        assertNull(t.onReadingWithAccuracy(1, 500.0, 100f, 300f))
        assertEquals(true, t.currentInside(1))
    }

    @Test
    fun `精度膨胀 - 精度差时出宽判定半径变大`() {
        val t = ZoneStateTracker(exitConfirmations = 2, hysteresisMeters = 25f)
        t.onReadingWithAccuracy(1, 90.0, 100f, 10f) // 进入
        // 精度 60m → 出宽半径 = 100+25+30=155，距离 140m 仍在带内 → 不离开
        assertNull(t.onReadingWithAccuracy(1, 140.0, 100f, 60f))
        // 精度恢复 10m → 出宽半径 130，距离 140m 超出 → 第一次确认
        assertNull(t.onReadingWithAccuracy(1, 140.0, 100f, 10f))
        assertEquals(false, t.onReadingWithAccuracy(1, 140.0, 100f, 10f))
    }

    @Test
    fun `旧接口 - 纯布尔输入退化为计数去抖`() {
        val t = ZoneStateTracker(exitConfirmations = 2)
        assertEquals(true, t.onReading(1, true))
        assertNull(t.onReading(1, false)) // 第一次
        assertEquals(false, t.onReading(1, false)) // 第二次确认
    }
}
