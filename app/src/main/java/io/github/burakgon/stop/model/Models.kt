package io.github.burakgon.stop.model

import io.github.burakgon.stop.daemon.StopReasons

enum class RuleMode { BLOCK, ALLOW }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the user decided for one app. Apps without a rule are new or are system apps. */
data class AppRule(
    val packageName: String,
    val mode: RuleMode,
    /** False while a newly installed app is waiting for the user's choice. */
    val decided: Boolean,
    /** Overrides the global grace period when set. */
    val graceSeconds: Int?,
    val updatedAt: Long,
) {
    val blocked: Boolean get() = mode == RuleMode.BLOCK
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
    val category: Int,
    val firstInstallTime: Long,
) {
    /**
     * Has its own icon and isn't part of the system. Stop only manages these unless asked to show
     * everything: packages without an icon (voice data, language packs, plugins) run when another
     * app needs them, and stopping them breaks that app instead.
     */
    val isUserFacing: Boolean get() = !isSystem && isLaunchable
}

data class AppSettings(
    val onboarded: Boolean = false,
    val protectionEnabled: Boolean = true,
    val graceSeconds: Int = 60,
    val allowOngoing: Boolean = false,
    val askOnInstall: Boolean = true,
    val newAppMode: RuleMode = RuleMode.BLOCK,
    val extraRestrictions: Boolean = false,
    val showSystemApps: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
)

/** Choices offered for how long a blocked app may keep running after you leave it. */
object GracePeriods {
    val options = listOf(0, 30, 60, 5 * 60, 15 * 60, 60 * 60)
}

enum class StopReason(val code: String) {
    WOKE(StopReasons.WOKE),
    LEFT(StopReasons.LEFT),
    RUNNING(StopReasons.RUNNING),
    MANUAL(StopReasons.MANUAL);

    companion object {
        fun of(code: String): StopReason = entries.firstOrNull { it.code == code } ?: WOKE
    }
}

data class StopEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val reason: StopReason,
    val sinceLeftMs: Long,
)
