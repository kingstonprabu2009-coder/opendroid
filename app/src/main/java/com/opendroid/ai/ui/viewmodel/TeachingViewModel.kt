package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.TeachingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeachingViewModel @Inject constructor(
    private val teachingEngine: TeachingEngine
) : ViewModel() {

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun teach(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _status.value = "Video paarkkuren..."
        viewModelScope.launch {
            val result = teachingEngine.learnFromVideo(uri)
            _status.value = if (result.startsWith("ERROR:")) {
                result.removePrefix("ERROR:").trim()
            } else {
                "Kathukitten sir ✅ Ippo \"reference style la edit pannu\" nu sollunga.\n\n$result"
            }
            _busy.value = false
        }
    }
}
