package com.moyou.isponsorblocktv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyou.isponsorblocktv.data.PairingRepository
import com.moyou.isponsorblocktv.data.SettingsRepository
import com.moyou.isponsorblocktv.domain.MonitorConfig
import com.moyou.isponsorblocktv.domain.MonitorStatus
import com.moyou.isponsorblocktv.service.MonitorController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class PairingUi(val loading: Boolean = false, val message: String = "")

data class ScreenUi(
    val config: MonitorConfig = MonitorConfig(),
    val monitor: MonitorStatus = MonitorStatus(),
    val pairing: Boolean = false,
    val message: String = "",
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val pairingRepo: PairingRepository,
    controller: MonitorController,
) : ViewModel() {
    private val pairingState = MutableStateFlow(PairingUi())
    val uiState = combine(settings.settings, controller.status, pairingState) { config, monitor, pairing ->
        ScreenUi(config, monitor, pairing.loading, pairing.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenUi(settings.settings.value, controller.status.value))

    fun pair(code: String) {
        if (pairingState.value.loading) return
        viewModelScope.launch {
            pairingState.value = PairingUi(loading = true)
            try {
                val result = pairingRepo.pairWithCode(code)
                settings.update { it.copy(screenId = result.screenId, screenName = result.name) }
                pairingState.value = PairingUi(message = "تم ربط ${result.name} بنجاح")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                pairingState.value = PairingUi(message = error.message ?: "فشل الربط")
            }
        }
    }

    fun forgetDevice() = settings.update { it.copy(screenId = "", screenName = "") }
    fun setCategory(category: String, selected: Boolean) = settings.update { current ->
        val value = current.categories.toMutableSet()
        if (selected) value.add(category) else value.remove(category)
        current.copy(categories = value.toSet())
    }
    fun setMuteAds(value: Boolean) = settings.update { it.copy(muteAds = value) }
    fun setSkipAds(value: Boolean) = settings.update { it.copy(skipAds = value) }
    fun setTracking(value: Boolean) = settings.update { it.copy(trackSkips = value) }
    fun setAutoPlay(value: Boolean) = settings.update { it.copy(autoPlay = value) }
    fun setMinimum(value: Int) = settings.update { it.copy(minSkipSeconds = value.coerceIn(0, 30)) }
    fun setOffset(value: Int) = settings.update { it.copy(offsetMillis = value.coerceIn(-1000, 1000)) }
}
