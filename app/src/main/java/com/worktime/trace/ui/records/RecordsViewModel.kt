package com.worktime.trace.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.AttendanceEventEntity
import com.worktime.trace.data.db.WorkplaceEntity
import com.worktime.trace.data.prefs.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordsUiState(
    val settings: AppSettings = AppSettings(),
    val workplaces: List<WorkplaceEntity> = emptyList(),
    val events: List<AttendanceEventEntity> = emptyList(),
    val now: Long = 0L
)

class RecordsViewModel(private val app: WorkTimeTraceApp) : ViewModel() {

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val uiState: StateFlow<RecordsUiState> = combine(
        app.repository.observeWorkplaces(),
        app.repository.observeEvents(),
        app.settingsStore.settings,
        ticker
    ) { workplaces, events, settings, now ->
        RecordsUiState(settings, workplaces, events, now)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, RecordsUiState())

    fun deleteEvent(event: AttendanceEventEntity) {
        viewModelScope.launch { app.repository.deleteEvent(event) }
    }

    fun addManualEvent(workplaceId: Long, isEnter: Boolean, epochMs: Long) {
        viewModelScope.launch {
            app.repository.addEvent(
                workplaceId = workplaceId,
                type = if (isEnter) com.worktime.trace.data.db.EventType.ENTER
                else com.worktime.trace.data.db.EventType.EXIT,
                timestamp = epochMs,
                latitude = null,
                longitude = null,
                manual = true
            )
        }
    }
}
