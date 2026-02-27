package com.lgsextractor.util

import android.content.Context
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
    }

    val claudeApiKey: Flow<String?> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_CLAUDE_API_KEY]?.takeIf { it.isNotBlank() }
    }
    
    val geminiApiKey: Flow<String?> = context.apiKeyDataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY]?.takeIf { it.isNotBlank() }
    }

    suspend fun getClaudeApiKey(): String? =
        claudeApiKey.firstOrNull()

    suspend fun getGeminiApiKey(): String? =
        geminiApiKey.firstOrNull()

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

    suspend fun clearClaudeApiKey() {
        context.apiKeyDataStore.edit { prefs ->
            prefs.remove(KEY_CLAUDE_API_KEY)
        }
    }

    suspend fun clearGeminiApiKey() {
        context.apiKeyDataStore.edit { prefs ->
            prefs.remove(KEY_GEMINI_API_KEY)
        }
    }

    suspend fun hasClaudeApiKey(): Boolean =
        !getClaudeApiKey().isNullOrBlank()
        
    suspend fun hasGeminiApiKey(): Boolean =
        !getGeminiApiKey().isNullOrBlank()
}
