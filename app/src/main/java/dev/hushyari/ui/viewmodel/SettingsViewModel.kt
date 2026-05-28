package dev.hushyari.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hushyari.data.local.PreferencesManager
import dev.hushyari.llm.ApiKeyManager
import dev.hushyari.llm.LlmConfig
import dev.hushyari.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ApiKeyUiState(
    val provider: LlmProvider,
    val key: String,
    val isMasked: Boolean,
    val isTesting: Boolean,
    val testResult: String?,
)

data class SettingsUiState(
    val selectedProvider: LlmProvider = LlmProvider.GOOGLE,
    val modelName: String = "",
    val apiKeys: List<ApiKeyUiState> = emptyList(),
    val useLocalModel: Boolean = false,
    val localModelPath: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val baseUrl: String = "",
    val captureMode: String = "FAST",
    val llmEscalationThreshold: Int = 3,
    val playTimeLimitMinutes: Int = 0,
    val dailySpendingLimitCents: Int = 100,
    val actionBlacklist: Set<String> = emptySet(),
    val externalAutomationEnabled: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val apiKeyManager: ApiKeyManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val config = preferencesManager.getSelectedModel()
        val providers = LlmProvider.entries.filter { it != LlmProvider.LOCAL }

        _uiState.update {
            it.copy(
                selectedProvider = config.provider,
                modelName = config.modelName,
                useLocalModel = config.useLocal,
                localModelPath = config.localModelPath,
                temperature = config.temperature,
                maxTokens = config.maxTokens,
                baseUrl = config.baseUrl,
                captureMode = preferencesManager.getCaptureMode(),
                llmEscalationThreshold = preferencesManager.getLlmEscalationThreshold(),
                playTimeLimitMinutes = preferencesManager.getPlayTimeLimitMinutes(),
                dailySpendingLimitCents = preferencesManager.getDailySpendingLimitCents(),
                actionBlacklist = preferencesManager.getActionBlacklist(),
                externalAutomationEnabled = preferencesManager.isExternalAutomationEnabled(),
                apiKeys = providers.map { p ->
                    val storedKey = apiKeyManager.getApiKey(p.name) ?: ""
                    ApiKeyUiState(
                        provider = p,
                        key = storedKey,
                        isMasked = storedKey.isNotEmpty(),
                        isTesting = false,
                        testResult = null,
                    )
                },
            )
        }
    }

    fun selectProvider(provider: LlmProvider) {
        _uiState.update {
            it.copy(
                selectedProvider = provider,
                modelName = when (provider) {
                    LlmProvider.OPENAI -> LlmConfig.DEFAULT_OPENAI.modelName
                    LlmProvider.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC.modelName
                    LlmProvider.GOOGLE -> LlmConfig.DEFAULT_GOOGLE.modelName
                    else -> it.modelName
                },
            )
        }
        saveModelConfig()
    }

    fun setModelName(name: String) {
        _uiState.update { it.copy(modelName = name) }
    }

    fun setApiKey(provider: LlmProvider, key: String) {
        apiKeyManager.storeApiKey(provider.name, key)
        _uiState.update { state ->
            state.copy(
                apiKeys = state.apiKeys.map {
                    if (it.provider == provider) it.copy(key = key, isMasked = true) else it
                }
            )
        }
    }

    fun testApiKey(provider: LlmProvider) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    apiKeys = state.apiKeys.map {
                        if (it.provider == provider) it.copy(isTesting = true, testResult = null) else it
                    }
                )
            }

            try {
                val key = apiKeyManager.getApiKey(provider.name)
                if (key.isNullOrBlank()) {
                    updateTestResult(provider, "No key set")
                    return@launch
                }
                updateTestResult(provider, "OK — key stored securely")
            } catch (e: Exception) {
                updateTestResult(provider, "Failed: ${e.message}")
            }
        }
    }

    fun deleteApiKey(provider: LlmProvider) {
        apiKeyManager.deleteApiKey(provider.name)
        _uiState.update { state ->
            state.copy(
                apiKeys = state.apiKeys.map {
                    if (it.provider == provider) it.copy(key = "", isMasked = false) else it
                }
            )
        }
    }

    fun setUseLocalModel(useLocal: Boolean) {
        _uiState.update { it.copy(useLocalModel = useLocal) }
        saveModelConfig()
    }

    fun setCaptureMode(mode: String) {
        _uiState.update { it.copy(captureMode = mode) }
        preferencesManager.setCaptureMode(mode)
    }

    fun setLlmEscalationThreshold(threshold: Int) {
        _uiState.update { it.copy(llmEscalationThreshold = threshold) }
        preferencesManager.setLlmEscalationThreshold(threshold)
    }

    fun setPlayTimeLimit(minutes: Int) {
        _uiState.update { it.copy(playTimeLimitMinutes = minutes) }
        preferencesManager.setPlayTimeLimitMinutes(minutes)
    }

    fun setDailySpendingLimit(cents: Int) {
        _uiState.update { it.copy(dailySpendingLimitCents = cents) }
        preferencesManager.setDailySpendingLimitCents(cents)
    }

    fun toggleExternalAutomation() {
        val newValue = !_uiState.value.externalAutomationEnabled
        _uiState.update { it.copy(externalAutomationEnabled = newValue) }
        preferencesManager.setExternalAutomationEnabled(newValue)
    }

    fun toggleActionBlacklist(action: String) {
        val current = _uiState.value.actionBlacklist.toMutableSet()
        if (action in current) {
            current.remove(action)
            preferencesManager.removeFromActionBlacklist(action)
        } else {
            current.add(action)
            preferencesManager.addToActionBlacklist(action)
        }
        _uiState.update { it.copy(actionBlacklist = current) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun saveModelConfig() {
        val state = _uiState.value
        val config = LlmConfig(
            provider = state.selectedProvider,
            modelName = state.modelName,
            baseUrl = state.baseUrl,
            temperature = state.temperature,
            maxTokens = state.maxTokens,
            useLocal = state.useLocalModel,
            localModelPath = state.localModelPath,
        )
        preferencesManager.setSelectedModel(config)
    }

    private fun updateTestResult(provider: LlmProvider, result: String) {
        _uiState.update { state ->
            state.copy(
                apiKeys = state.apiKeys.map {
                    if (it.provider == provider) it.copy(isTesting = false, testResult = result) else it
                }
            )
        }
    }
}
