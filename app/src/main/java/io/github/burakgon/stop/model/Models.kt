package io.github.burakgon.stop.model

import io.github.burakgon.stop.daemon.StopReasons

enum class RuleMode { BLOCK, ALLOW }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How a blocked app gets frozen. [IDLE] freezes nothing (only the optional extra restrictions
 * apply), [STOP] kills the app and lets it start again, [FREEZE] makes it unusable until Stop
 * restores it.
 */
enum class WorkMode(val code: String) {
    /** 空闲：只做额外限制，不冻结应用。 */
    IDLE("idle"),

    /** 强行停止：am force-stop，应用可随时再次启动。 */
    STOP("stop"),

    /** 强停挂起：先 `pm suspend` 再 force-stop，应用在后台起不来，点开时由系统拦下。 */
    SUSPEND_STOP("suspend_stop"),

    /** 冻结停用：直接 `pm disable-user`（必要时退到隐藏），应用一直不可用直到手动恢复。 */
    FREEZE("freeze");

    companion object {
        fun fromCode(code: String?): WorkMode = when (code) {
            IDLE.code -> IDLE
            SUSPEND_STOP.code -> SUSPEND_STOP
            FREEZE.code, "disable", "hide", "suspend" -> FREEZE
            else -> STOP
        }

        /** Settings are stored by enum name; older builds stored DISABLE/HIDE/SUSPEND. */
        fun fromName(name: String?): WorkMode = when (name) {
            "IDLE" -> IDLE
            "SUSPEND_STOP" -> SUSPEND_STOP
            "FREEZE", "DISABLE", "HIDE", "SUSPEND" -> FREEZE
            else -> STOP
        }
    }
}

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
    /** False while the package is disabled — i.e. permanently frozen. */
    val enabled: Boolean = true,
    /** True while the system holds it suspended (`pm suspend`), whether frozen or not. */
    val suspended: Boolean = false,
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
    /** Which freeze tool the daemon should use for blocked apps. */
    val workMode: WorkMode = WorkMode.STOP,
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
    MANUAL(StopReasons.MANUAL),
    TAPPED(StopReasons.TAPPED),
    FREEZE(StopReasons.FREEZE),
    THAW(StopReasons.THAW);

    /** True for events that describe a freeze, not a stop. */
    val isFreeze: Boolean get() = this == FREEZE || this == THAW || this == TAPPED

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
