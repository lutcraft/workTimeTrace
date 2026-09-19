package com.worktime.trace.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 应用统计规则与运行配置。 */
data class AppSettings(
    /** 每日标准工时（小时），在岗时长超出部分计为加班 */
    val standardHours: Float = 8f,
    /** 周末（周六/周日）在岗是否全天计加班 */
    val weekendOvertime: Boolean = true,
    /** 法定节假日（含调休）在岗是否全天计加班 */
    val holidayOvertime: Boolean = true,
    /** 定位轮询间隔（秒），默认 10 分钟：打卡误差与该间隔正相关，但更省电 */
    val locationIntervalSec: Int = 600,
    /** 考勤记录服务是否启用（重启后据此自启） */
    val trackingEnabled: Boolean = false
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val STANDARD_HOURS = floatPreferencesKey("standard_hours")
        val WEEKEND_OVERTIME = booleanPreferencesKey("weekend_overtime")
        val HOLIDAY_OVERTIME = booleanPreferencesKey("holiday_overtime")
        val LOCATION_INTERVAL = intPreferencesKey("location_interval_sec")
        val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            standardHours = p[Keys.STANDARD_HOURS] ?: 8f,
            weekendOvertime = p[Keys.WEEKEND_OVERTIME] ?: true,
            holidayOvertime = p[Keys.HOLIDAY_OVERTIME] ?: true,
            locationIntervalSec = p[Keys.LOCATION_INTERVAL] ?: 600,
            trackingEnabled = p[Keys.TRACKING_ENABLED] ?: false
        )
    }

    suspend fun setStandardHours(v: Float) = context.dataStore.edit { it[Keys.STANDARD_HOURS] = v }
    suspend fun setWeekendOvertime(v: Boolean) = context.dataStore.edit { it[Keys.WEEKEND_OVERTIME] = v }
    suspend fun setHolidayOvertime(v: Boolean) = context.dataStore.edit { it[Keys.HOLIDAY_OVERTIME] = v }
    suspend fun setLocationInterval(v: Int) = context.dataStore.edit { it[Keys.LOCATION_INTERVAL] = v }
    suspend fun setTrackingEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TRACKING_ENABLED] = v }
}
