package com.worktime.trace.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.prefs.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class CalendarUiState(
    val settings: AppSettings = AppSettings(),
    val events: List<AttendanceEventEntity> = emptyList(),
    val now: Long = 0L
)

class CalendarViewModel(app: WorkTimeTraceApp) : ViewModel() {

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        app.repository.observeEvents(),
        app.settingsStore.settings,
        ticker
    ) { events, settings, now ->
        CalendarUiState(settings, events, now)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, CalendarUiState())
}
