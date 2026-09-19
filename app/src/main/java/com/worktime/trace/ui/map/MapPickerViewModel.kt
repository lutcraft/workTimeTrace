package com.worktime.trace.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worktime.trace.WorkTimeTraceApp
import com.worktime.trace.data.db.WorkplaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapPickerViewModel(private val app: WorkTimeTraceApp) : ViewModel() {

    suspend fun loadWorkplace(id: Long): WorkplaceEntity? = withContext(Dispatchers.IO) {
        if (id > 0) app.repository.getWorkplace(id) else null
    }

    fun save(workplace: WorkplaceEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.repository.saveWorkplace(workplace) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
