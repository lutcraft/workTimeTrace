package com.worktime.trace.data

import android.content.Context
import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.EventType
import com.worktime.trace.data.db.WorkplaceEntity
import com.worktime.trace.data.db.WorkplaceDao
import com.worktime.trace.data.db.EventDao
import com.worktime.trace.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 考勤数据仓库：UI 与服务共同访问。
 */
class AttendanceRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val workplaceDao: WorkplaceDao = db.workplaceDao()
    private val eventDao: EventDao = db.eventDao()

    fun observeWorkplaces(): Flow<List<WorkplaceEntity>> = workplaceDao.observeAll()

    fun observeEvents(): Flow<List<AttendanceEventEntity>> = eventDao.observeAllAsc()

    suspend fun getEnabledWorkplaces(): List<WorkplaceEntity> = workplaceDao.getEnabled()

    suspend fun getWorkplace(id: Long): WorkplaceEntity? = workplaceDao.getById(id)

    suspend fun saveWorkplace(w: WorkplaceEntity) {
        if (w.id == 0L) workplaceDao.insert(w) else workplaceDao.update(w)
    }

    suspend fun deleteWorkplace(id: Long) = workplaceDao.deleteById(id)

    suspend fun addEvent(
        workplaceId: Long,
        type: EventType,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?,
        manual: Boolean = false
    ) {
        eventDao.insert(
            AttendanceEventEntity(
                workplaceId = workplaceId,
                type = type,
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude,
                manual = manual
            )
        )
    }

    suspend fun deleteEvent(event: AttendanceEventEntity) = eventDao.delete(event)

    suspend fun latestEventPerWorkplace(): List<AttendanceEventEntity> =
        eventDao.getLatestEventPerWorkplace()
}
