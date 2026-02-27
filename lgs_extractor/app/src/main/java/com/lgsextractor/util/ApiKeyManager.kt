package com.lgsextractor.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.apiKeyDataStore by preferencesDataStore(name = "api_keys")

@Singleton
class ApiKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        private val KEY_USE_CLAUDE = booleanPreferencesKey("use_claude_vision")
        private val KEY_USE_GEMINI = booleanPreferencesKey("use_gemini_vision")
    }

    val claudeApiKey: Flow<String?> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_CLAUDE_API_KEY]?.takeIf { it.isNotBlank() }
    }
    
    val geminiApiKey: Flow<String?> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY]?.takeIf { it.isNotBlank() }
    }
    
    val geminiModel: Flow<String?> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_GEMINI_MODEL]?.takeIf { it.isNotBlank() }
    }

    val useClaudeVision: Flow<Boolean> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_USE_CLAUDE] ?: false
    }

    val useGeminiVision: Flow<Boolean> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_USE_GEMINI] ?: false
    }

    suspend fun getClaudeApiKey(): String? =
        claudeApiKey.firstOrNull()

    suspend fun getGeminiApiKey(): String? =
        geminiApiKey.firstOrNull()
        
    suspend fun getGeminiModel(): String? =
        geminiModel.firstOrNull()

    suspend fun saveClaudeApiKey(apiKey: String) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[KEY_CLAUDE_API_KEY] = apiKey.trim()
        }
    }

    suspend fun saveGeminiApiKey(apiKey: String) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[KEY_GEMINI_API_KEY] = apiKey.trim()
        }
    }
    
    suspend fun saveGeminiModel(model: String) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[KEY_GEMINI_MODEL] = model.trim()
        }
    }

    suspend fun setUseClaudeVision(enabled: Boolean) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[KEY_USE_CLAUDE] = enabled
        }
    }

    suspend fun setUseGeminiVision(enabled: Boolean) {
        context.apiKeyDataStore.edit { prefs ->
            prefs[KEY_USE_GEMINI] = enabled
        }
    }
            prefs[KEY_GEMINI_MODEL] = model.trim()
        }
    }

    suspend fun clearClaudeApiKey() {
        context.apiKeyDataStore.edit { prefs ->
            prefs.remove(KEY_CLAUDE_API_KEY)
        }
    }

    suspend fun clearGeminiApiKey() {
        context.apiKeyDataStore.edit { prefs ->
            prefs.remove(KEY_GEMINI_API_KEY)
            prefs.remove(KEY_GEMINI_MODEL)
        }
    }

    suspend fun hasClaudeApiKey(): Boolean =
        !getClaudeApiKey().isNullOrBlank()
        
    suspend fun hasGeminiApiKey(): Boolean =
        !getGeminiApiKey().isNullOrBlank()
}
