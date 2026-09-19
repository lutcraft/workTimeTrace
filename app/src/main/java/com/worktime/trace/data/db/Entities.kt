package com.worktime.trace.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 工作地点（地理围栏中心点 + 半径）。
 */
@Entity(tableName = "workplaces")
data class WorkplaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** 围栏半径，米 */
    val radiusMeters: Float,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** 事件类型：进入范围 / 离开范围 */
enum class EventType { ENTER, EXIT }

/**
 * 一次进出围栏的事件。
 * ENTER 与后续最近的同 workplace 的 EXIT 配对成一段在岗区间。
 */
@Entity(tableName = "attendance_events")
data class AttendanceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workplaceId: Long,
    val type: EventType,
    /** 事件时间戳（毫秒） */
    val timestamp: Long,
    /** 事件发生时的定位坐标（用于核对），不可用时为 null */
    val latitude: Double?,
    val longitude: Double?,
    /** 是否为手动补录 */
    val manual: Boolean = false
)
