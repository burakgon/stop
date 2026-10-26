package io.github.burakgon.stop.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.burakgon.stop.model.AppSettings
import io.github.burakgon.stop.model.RuleMode
import io.github.burakgon.stop.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[ONBOARDED] = next.onboarded
            prefs[ENABLED] = next.protectionEnabled
            prefs[GRACE_SECONDS] = next.graceSeconds
            prefs[ALLOW_ONGOING] = next.allowOngoing
            prefs[ASK_ON_INSTALL] = next.askOnInstall
            prefs[NEW_APP_MODE] = next.newAppMode.name
            prefs[EXTRA_RESTRICTIONS] = next.extraRestrictions
            prefs[SHOW_SYSTEM_APPS] = next.showSystemApps
            prefs[THEME_MODE] = next.themeMode.name
            prefs[DYNAMIC_COLOR] = next.dynamicColor
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            onboarded = this[ONBOARDED] ?: defaults.onboarded,
            protectionEnabled = this[ENABLED] ?: defaults.protectionEnabled,
            graceSeconds = this[GRACE_SECONDS] ?: defaults.graceSeconds,
            allowOngoing = this[ALLOW_ONGOING] ?: defaults.allowOngoing,
            askOnInstall = this[ASK_ON_INSTALL] ?: defaults.askOnInstall,
            newAppMode = this[NEW_APP_MODE]?.let { runCatching { RuleMode.valueOf(it) }.getOrNull() } ?: defaults.newAppMode,
            extraRestrictions = this[EXTRA_RESTRICTIONS] ?: defaults.extraRestrictions,
            showSystemApps = this[SHOW_SYSTEM_APPS] ?: defaults.showSystemApps,
            themeMode = this[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = this[DYNAMIC_COLOR] ?: defaults.dynamicColor,
        )
    }

    private companion object {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val ENABLED = booleanPreferencesKey("protection_enabled")
        val GRACE_SECONDS = intPreferencesKey("grace_seconds")
        val ALLOW_ONGOING = booleanPreferencesKey("allow_ongoing")
        val ASK_ON_INSTALL = booleanPreferencesKey("ask_on_install")
        val NEW_APP_MODE = stringPreferencesKey("new_app_mode")
        val EXTRA_RESTRICTIONS = booleanPreferencesKey("extra_restrictions")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
