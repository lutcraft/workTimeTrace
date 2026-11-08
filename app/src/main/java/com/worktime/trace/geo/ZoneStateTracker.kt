package com.worktime.trace.geo

/**
 * 围栏内外状态去抖跟踪器（动态迟滞双半径防漂移）。
 *
 * GPS 在围栏边界附近会漂移，直接以单次定位判定会造成“下班/上班”误触发：
 * - 进入围栏：进严，dist <= radius 立即确认（上班路上进入一般无歧义）；
 * - 离开围栏：出宽，需 dist > radius + [hysteresisMeters] + 定位精度*0.5，
 *   且连续 [exitConfirmations] 次确认才判定离开，过滤室内窗边/电梯/基站切换的偶发跳变；
 * - 定位精度差于 max(radius*1.5, 250m) 时跳过本轮离开判定（劣质精度不做出宽决定）。
 */
class ZoneStateTracker(
    private val exitConfirmations: Int = 2,
    private val hysteresisMeters: Float = 25f
) {
    private data class State(val inside: Boolean, val outsideCount: Int)

    private val states = mutableMapOf<Long, State>()

    /** 服务启动时用数据库中的最新事件恢复初始状态。 */
    @Synchronized
    fun initInside(workplaceId: Long, inside: Boolean) {
        states[workplaceId] = State(inside, 0)
    }

    /**
     * 喂入一次带精度的定位判定结果（进严出宽迟滞）。
     * @return 状态发生变化时返回新状态（true=已进入，false=已离开），未变化返回 null。
     */
    @Synchronized
    fun onReadingWithAccuracy(
        workplaceId: Long,
        distanceMeters: Double,
        radiusMeters: Float,
        accuracyMeters: Float
    ): Boolean? {
        val cur = states[workplaceId] ?: State(inside = false, outsideCount = 0)
        if (cur.inside && accuracyMeters > maxOf(radiusMeters * 1.5f, 250f)) {
            // 忽略电梯/地下室切基站造成的劣质精度跳变
            return null
        }
        val effectiveExitRadius = radiusMeters + hysteresisMeters +
            accuracyMeters.coerceAtLeast(0f) * 0.5f
        val insideNow = if (cur.inside) {
            distanceMeters <= effectiveExitRadius
        } else {
            distanceMeters <= radiusMeters
        }
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

    /**
     * 兼容旧签名：仅有“是否在半径内”的布尔结果时使用。
     * 迟滞与精度过滤无法生效，行为退化为纯计数去抖。
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
