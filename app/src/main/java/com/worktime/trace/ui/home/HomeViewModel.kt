package com.worktime.trace.ui.home

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.WorkplaceEntity
import com.worktime.trace.data.prefs.AppSettings
import com.worktime.trace.stats.DayStat
import com.worktime.trace.stats.OvertimeCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HomeUiState(
    val trackingEnabled: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val workplaces: List<WorkplaceEntity> = emptyList(),
    val events: List<AttendanceEventEntity> = emptyList(),
    val today: DayStat? = null,
    val currentlyInside: Boolean = false,
    val currentSessionStartMs: Long? = null,
    val now: Long = 0L,
    val monthOvertimeMs: Long = 0L,
    val monthWorkMs: Long = 0L,
    val amapKeyConfigured: Boolean = true
)

class HomeViewModel(private val app: WorkTimeTraceApp) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        app.repository.observeWorkplaces(),
        app.repository.observeEvents(),
        app.settingsStore.settings,
        ticker
    ) { workplaces, events, settings, now ->
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val stats = OvertimeCalculator.computeDayStats(events, settings, now, zone)
        val monthDays = stats.filterKeys { it.year == today.year && it.month == today.month }
        HomeUiState(
            trackingEnabled = settings.trackingEnabled,
            settings = settings,
            workplaces = workplaces,
            events = events,
            today = stats[today],
            currentlyInside = OvertimeCalculator.currentlyInside(events),
            currentSessionStartMs = OvertimeCalculator.currentSessionStartMs(events),
            now = now,
            monthOvertimeMs = monthDays.values.sumOf { it.overtimeMs },
            monthWorkMs = monthDays.values.sumOf { it.workMs },
            amapKeyConfigured = isAmapKeyConfigured(app)
        )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, HomeUiState())

    fun deleteWorkplace(id: Long) {
        viewModelScope.launch { app.repository.deleteWorkplace(id) }
    }

    /** 主页快捷切换计时模式（实时生效，与设置页共享同一存储）。 */
    fun setCalcMode(mode: com.worktime.trace.data.prefs.CalcMode) {
        viewModelScope.launch { app.settingsStore.setCalcMode(mode) }
    }

    companion object {
        /** 高德 Key 是否已配置（Manifest 中非占位符）。 */
        fun isAmapKeyConfigured(app: Application): Boolean =
            (app as? com.worktime.trace.WorkTimeTraceApp)?.amapKey() != null
    }
}
