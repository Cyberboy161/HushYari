package dev.hushyari.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hushyari.data.local.PreferencesManager
import dev.hushyari.data.repository.AppInfo
import dev.hushyari.data.repository.GameRepository
import dev.hushyari.data.repository.SkillRepository
import dev.hushyari.skills.SkillEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val installedGames: List<AppInfo> = emptyList(),
    val selectedGame: AppInfo? = null,
    val recentSkills: List<String> = emptyList(),
    val agentRunning: Boolean = false,
    val agentStatus: String = "Stopped",
    val stepCount: Int = 0,
    val elapsedSeconds: Long = 0,
    val accessibilityGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val gameRepository: GameRepository,
    private val skillRepository: SkillRepository,
    private val preferencesManager: PreferencesManager,
    private val skillEngine: SkillEngine,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadInstalledGames()
        checkPermissions()
        restoreLastSession()
    }

    fun loadInstalledGames() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val games = gameRepository.getInstalledGames()
                val lastPkg = preferencesManager.getLastUsedGamePackage()
                val selected = games.find { it.packageName == lastPkg }
                _uiState.update {
                    it.copy(
                        installedGames = games,
                        selectedGame = selected ?: games.firstOrNull { it.isGame },
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun selectGame(game: AppInfo) {
        _uiState.update { it.copy(selectedGame = game) }
        preferencesManager.setLastUsedGamePackage(game.packageName)
    }

    fun checkPermissions() {
        val context = getApplication<Application>()
        _uiState.update {
            it.copy(
                accessibilityGranted = isAccessibilityServiceEnabled(context),
                notificationGranted = isNotificationListenerEnabled(context),
                overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Settings.canDrawOverlays(context) else true,
            )
        }
    }

    fun startAgent() {
        val game = _uiState.value.selectedGame ?: return
        _uiState.update {
            it.copy(
                agentRunning = true,
                agentStatus = "Running",
                stepCount = 0,
                elapsedSeconds = 0,
            )
        }
    }

    fun stopAgent() {
        skillEngine.stop()
        _uiState.update {
            it.copy(
                agentRunning = false,
                agentStatus = "Stopped",
            )
        }
    }

    fun pauseAgent() {
        skillEngine.pause()
        _uiState.update { it.copy(agentStatus = "Paused") }
    }

    fun resumeAgent() {
        skillEngine.resume()
        _uiState.update { it.copy(agentStatus = "Running") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun restoreLastSession() {
        checkPermissions()
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "dev.hushyari/dev.hushyari.service.HushyariAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.contains(service) || enabledServices.contains("dev.hushyari")
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return flat.contains("dev.hushyari")
    }
}
