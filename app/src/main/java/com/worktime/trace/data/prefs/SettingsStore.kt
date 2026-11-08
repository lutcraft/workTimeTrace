package com.worktime.trace.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 工时统计模式：多段累计并集 vs 首进末出跨度 */
enum class CalcMode(val label: String, val desc: String) {
    INTERVAL_UNION("多段累计模式", "累加每次在围栏内的实际停留时段，中途离开围栏不计入"),
    FIRST_IN_LAST_OUT("首进末出模式", "只统计当天首次进入围栏与最后一次离开围栏的时间作为今日上班时间")
}

/** 应用统计规则与运行配置。 */
data class AppSettings(
    /** 工时计时模式 */
    val calcMode: CalcMode = CalcMode.FIRST_IN_LAST_OUT,
    /** 每日标准工时（小时），在岗时长超出部分计为加班 */
    val standardHours: Float = 8f,
    /** 周末（周六/周日）在岗是否全天计加班 */
    val weekendOvertime: Boolean = true,
    /** 法定节假日（含调休）在岗是否全天计加班 */
    val holidayOvertime: Boolean = true,
    /** 定位轮询间隔（秒），默认 10 分钟：打卡误差与该间隔正相关，但更省电 */
    val locationIntervalSec: Int = 600,
    /** 考勤记录服务是否启用（重启后据此自启） */
    val trackingEnabled: Boolean = false,
    /** 是否开启当日加班过长关怀提醒（默认关闭） */
    val overtimeAlertEnabled: Boolean = false,
    /** 当日加班过长提醒阈值（小时，默认 2 小时） */
    val overtimeAlertThresholdHours: Float = 2.0f
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val CALC_MODE = stringPreferencesKey("calc_mode")
        val STANDARD_HOURS = floatPreferencesKey("standard_hours")
        val WEEKEND_OVERTIME = booleanPreferencesKey("weekend_overtime")
        val HOLIDAY_OVERTIME = booleanPreferencesKey("holiday_overtime")
        val LOCATION_INTERVAL = intPreferencesKey("location_interval_sec")
        val TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        val OVERTIME_ALERT_ENABLED = booleanPreferencesKey("overtime_alert_enabled")
        val OVERTIME_ALERT_THRESHOLD_HOURS = floatPreferencesKey("overtime_alert_threshold_hours")
        fun lastInsideBeatKey(wpId: Long) = longPreferencesKey("last_inside_beat_$wpId")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val modeName = p[Keys.CALC_MODE]
        val calcMode = runCatching {
            if (modeName != null) CalcMode.valueOf(modeName) else CalcMode.FIRST_IN_LAST_OUT
        }.getOrDefault(CalcMode.FIRST_IN_LAST_OUT)

        AppSettings(
            calcMode = calcMode,
            standardHours = p[Keys.STANDARD_HOURS] ?: 8f,
            weekendOvertime = p[Keys.WEEKEND_OVERTIME] ?: true,
            holidayOvertime = p[Keys.HOLIDAY_OVERTIME] ?: true,
            locationIntervalSec = p[Keys.LOCATION_INTERVAL] ?: 600,
            trackingEnabled = p[Keys.TRACKING_ENABLED] ?: false,
            overtimeAlertEnabled = p[Keys.OVERTIME_ALERT_ENABLED] ?: false,
            overtimeAlertThresholdHours = p[Keys.OVERTIME_ALERT_THRESHOLD_HOURS] ?: 2.0f
        )
    }

    suspend fun setCalcMode(mode: CalcMode) = context.dataStore.edit { it[Keys.CALC_MODE] = mode.name }
    suspend fun setStandardHours(v: Float) = context.dataStore.edit { it[Keys.STANDARD_HOURS] = v }
    suspend fun setWeekendOvertime(v: Boolean) = context.dataStore.edit { it[Keys.WEEKEND_OVERTIME] = v }
    suspend fun setHolidayOvertime(v: Boolean) = context.dataStore.edit { it[Keys.HOLIDAY_OVERTIME] = v }
    suspend fun setLocationInterval(v: Int) = context.dataStore.edit { it[Keys.LOCATION_INTERVAL] = v }
    suspend fun setTrackingEnabled(v: Boolean) = context.dataStore.edit { it[Keys.TRACKING_ENABLED] = v }
    suspend fun setOvertimeAlertEnabled(v: Boolean) = context.dataStore.edit { it[Keys.OVERTIME_ALERT_ENABLED] = v }
    suspend fun setOvertimeAlertThresholdHours(v: Float) = context.dataStore.edit { it[Keys.OVERTIME_ALERT_THRESHOLD_HOURS] = v }

    /** 在岗心跳：围栏内每次定位成功时刷新，供关机后回溯结算下班时间。 */
    suspend fun setLastInsideBeat(workplaceId: Long, timestampMs: Long) {
        context.dataStore.edit { it[Keys.lastInsideBeatKey(workplaceId)] = timestampMs }
    }

    suspend fun getLastInsideBeat(workplaceId: Long): Long? {
        return context.dataStore.data.first()[Keys.lastInsideBeatKey(workplaceId)]
    }
}
