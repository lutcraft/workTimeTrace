package com.worktime.trace.geo

/**
 * 围栏内外状态去抖跟踪器。
 *
 * GPS 在围栏边界附近会漂移，直接以单次定位判定会造成“下班/上班”误触发：
 * - 进入围栏：立即确认（上班路上进入一般无歧义）；
 * - 离开围栏：需连续 [exitConfirmations] 次定位在范围外才确认，避免信号漂移误记下班。
 */
class ZoneStateTracker(
    private val exitConfirmations: Int = 2
) {
    private data class State(val inside: Boolean, val outsideCount: Int)

    private val states = mutableMapOf<Long, State>()

    /** 服务启动时用数据库中的最新事件恢复初始状态。 */
    @Synchronized
    fun initInside(workplaceId: Long, inside: Boolean) {
        states[workplaceId] = State(inside, 0)
    }

    /**
     * 喂入一次定位判定结果。
     * @return 状态发生变化时返回新状态（true=已进入，false=已离开），未变化返回 null。
     */
    @Synchronized
    fun onReading(workplaceId: Long, insideNow: Boolean): Boolean? {
        val cur = states[workplaceId] ?: State(inside = false, outsideCount = 0)
        return when {
            insideNow && !cur.inside -> {
                states[workplaceId] = State(inside = true, outsideCount = 0)
                true
            }
            !insideNow && cur.inside -> {
                val count = cur.outsideCount + 1
                if (count >= exitConfirmations) {
                    states[workplaceId] = State(inside = false, outsideCount = 0)
                    false
                } else {
                    states[workplaceId] = cur.copy(outsideCount = count)
                    null
                }
            }
            else -> {
                states[workplaceId] = State(inside = cur.inside, outsideCount = 0)
                null
            }
        }
    }

    @Synchronized
    fun currentInside(workplaceId: Long): Boolean = states[workplaceId]?.inside ?: false
}
