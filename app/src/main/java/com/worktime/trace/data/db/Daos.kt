package com.worktime.trace.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkplaceDao {
    @Query("SELECT * FROM workplaces ORDER BY id")
    fun observeAll(): Flow<List<WorkplaceEntity>>

    @Query("SELECT * FROM workplaces WHERE enabled = 1 ORDER BY id")
    suspend fun getEnabled(): List<WorkplaceEntity>

    @Query("SELECT * FROM workplaces WHERE id = :id")
    suspend fun getById(id: Long): WorkplaceEntity?

    @Insert
    suspend fun insert(workplace: WorkplaceEntity): Long

    @Update
    suspend fun update(workplace: WorkplaceEntity)

    @Query("DELETE FROM workplaces WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM attendance_events ORDER BY timestamp ASC")
    fun observeAllAsc(): Flow<List<AttendanceEventEntity>>

    @Insert
    suspend fun insert(event: AttendanceEventEntity): Long

    @Update
    suspend fun update(event: AttendanceEventEntity)

    @Delete
    suspend fun delete(event: AttendanceEventEntity)

    /** 删除时间范围内（[from, to)）的事件，用于删除某天的全部记录。 */
    @Query("DELETE FROM attendance_events WHERE timestamp >= :from AND timestamp < :to")
    suspend fun deleteBetween(from: Long, to: Long)

    /**
     * 每个工作地点最新的一条事件（用于服务启动时恢复“是否在岗”状态）。
     */
    @Query(
        """
        SELECT e.* FROM attendance_events e
        INNER JOIN (
            SELECT workplaceId, MAX(timestamp) AS maxTs
            FROM attendance_events GROUP BY workplaceId
        ) last ON e.workplaceId = last.workplaceId AND e.timestamp = last.maxTs
        """
    )
    suspend fun getLatestEventPerWorkplace(): List<AttendanceEventEntity>
}
