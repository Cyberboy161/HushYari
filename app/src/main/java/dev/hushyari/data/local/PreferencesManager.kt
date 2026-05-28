package dev.hushyari.data.local

import com.tencent.mmkv.MMKV
import dev.hushyari.llm.ApiKeyManager
import dev.hushyari.llm.LlmConfig
import dev.hushyari.llm.LlmProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    private val apiKeyManager: ApiKeyManager,
) {

    private val mmkv: MMKV = MMKV.mmkvWithID("hushyari_prefs")

    fun getApiKey(provider: String): String? = apiKeyManager.getApiKey(provider)

    fun storeApiKey(provider: String, key: String) = apiKeyManager.storeApiKey(provider, key)

    fun deleteApiKey(provider: String) = apiKeyManager.deleteApiKey(provider)

    fun getAllApiKeyProviders(): List<String> = apiKeyManager.getAllProviders()

    fun getSelectedModel(): LlmConfig {
        val providerName = mmkv.decodeString("llm_provider", LlmProvider.GOOGLE.name) ?: LlmProvider.GOOGLE.name
        val provider = try {
            LlmProvider.valueOf(providerName)
        } catch (_: Exception) {
            LlmProvider.GOOGLE
        }
        val modelName = mmkv.decodeString("model_name", "") ?: ""
        val baseUrl = mmkv.decodeString("base_url", "") ?: ""
        val temperature = mmkv.decodeFloat("temperature", 0.7f)
        val maxTokens = mmkv.decodeInt("max_tokens", 4096)
        val useLocal = mmkv.decodeBool("use_local", false)
        val localModelPath = mmkv.decodeString("local_model_path", "") ?: ""

        return LlmConfig(
            provider = provider,
            modelName = modelName.ifEmpty { when (provider) {
                LlmProvider.OPENAI -> LlmConfig.DEFAULT_OPENAI.modelName
                LlmProvider.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC.modelName
                LlmProvider.GOOGLE -> LlmConfig.DEFAULT_GOOGLE.modelName
                LlmProvider.LOCAL -> LlmConfig.DEFAULT_LOCAL.modelName
                else -> ""
            }},
            baseUrl = baseUrl,
            temperature = temperature,
            maxTokens = maxTokens,
            useLocal = useLocal,
            localModelPath = localModelPath,
        )
    }

    fun setSelectedModel(config: LlmConfig) {
        mmkv.encode("llm_provider", config.provider.name)
        mmkv.encode("model_name", config.modelName)
        mmkv.encode("base_url", config.baseUrl)
        mmkv.encode("temperature", config.temperature)
        mmkv.encode("max_tokens", config.maxTokens)
        mmkv.encode("use_local", config.useLocal)
        mmkv.encode("local_model_path", config.localModelPath)
    }

    fun getCaptureMode(): String = mmkv.decodeString("capture_mode", "FAST") ?: "FAST"

    fun setCaptureMode(mode: String) = mmkv.encode("capture_mode", mode)

    fun getLlmEscalationThreshold(): Int = mmkv.decodeInt("llm_escalation_threshold", 3)

    fun setLlmEscalationThreshold(value: Int) = mmkv.encode("llm_escalation_threshold", value)

    fun getPlayTimeLimitMinutes(): Int = mmkv.decodeInt("play_time_limit", 0)

    fun setPlayTimeLimitMinutes(minutes: Int) = mmkv.encode("play_time_limit", minutes)

    fun getDailySpendingLimitCents(): Int = mmkv.decodeInt("daily_spending_limit", 100)

    fun setDailySpendingLimitCents(cents: Int) = mmkv.encode("daily_spending_limit", cents)

    fun isActionBlacklisted(action: String): Boolean {
        val blacklist = mmkv.decodeStringSet("action_blacklist", emptySet()) ?: emptySet()
        return action in blacklist
    }

    fun getActionBlacklist(): Set<String> =
        mmkv.decodeStringSet("action_blacklist", emptySet()) ?: emptySet()

    fun addToActionBlacklist(action: String) {
        val blacklist = getActionBlacklist().toMutableSet()
        blacklist.add(action)
        mmkv.encode("action_blacklist", blacklist)
    }

    fun removeFromActionBlacklist(action: String) {
        val blacklist = getActionBlacklist().toMutableSet()
        blacklist.remove(action)
        mmkv.encode("action_blacklist", blacklist)
    }

    fun isExternalAutomationEnabled(): Boolean =
        mmkv.decodeBool("external_automation", false)

    fun setExternalAutomationEnabled(enabled: Boolean) =
        mmkv.encode("external_automation", enabled)

    fun hasCompletedOnboarding(): Boolean =
        mmkv.decodeBool("onboarding_completed", false)

    fun setOnboardingCompleted() = mmkv.encode("onboarding_completed", true)

    fun getLastUsedGamePackage(): String? =
        mmkv.decodeString("last_game_package")

    fun setLastUsedGamePackage(packageName: String) =
        mmkv.encode("last_game_package", packageName)

    fun clearAll() = mmkv.clearAll()
}
