package com.worktime.trace.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.prefs.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: WorkTimeTraceApp) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        app.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setStandardHours(v: Float) = viewModelScope.launch { app.settingsStore.setStandardHours(v) }
    fun setWeekendOvertime(v: Boolean) =
        viewModelScope.launch { app.settingsStore.setWeekendOvertime(v) }
    fun setHolidayOvertime(v: Boolean) =
        viewModelScope.launch { app.settingsStore.setHolidayOvertime(v) }
    fun setLocationInterval(v: Int) =
        viewModelScope.launch { app.settingsStore.setLocationInterval(v) }
}
