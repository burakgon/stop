package io.github.burakgon.stop.core

import io.github.burakgon.stop.data.InstalledApps
import io.github.burakgon.stop.data.RulesRepository
import io.github.burakgon.stop.data.SettingsRepository
import io.github.burakgon.stop.data.SystemRoles
import io.github.burakgon.stop.model.InstalledApp
import io.github.burakgon.stop.notifications.Notifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles installs and removals: a new app gets the default rule and, if the user wants to be
 * asked, a notification; a removed app loses its rule.
 */
class PackageEvents(
    private val settings: SettingsRepository,
    private val rules: RulesRepository,
    private val apps: InstalledApps,
    private val roles: SystemRoles,
    private val notifications: Notifications,
) {
    private val mutex = Mutex()

    suspend fun onPackagesChanged(packages: List<String>) = mutex.withLock {
        val installed = apps.refresh()
        val s = settings.current()
        if (!s.onboarded) return@withLock
        val known = rules.all()
        val removed = packages.filter { it !in installed && it in known }
        rules.delete(removed)
        removed.forEach(notifications::cancelNewApp)
        val protected = roles.protectedPackages()
        val added = packages.mapNotNull { installed[it] }.filter { it.isCandidate(known.keys, protected) }
        announce(added)
    }

    /** Catches up on apps installed while Stop wasn't watching (e.g. Shizuku wasn't running). */
    suspend fun reconcile() = mutex.withLock {
        val s = settings.current()
        if (!s.onboarded) return@withLock
        val installed = apps.refresh()
        val known = rules.all()
        rules.delete(known.keys.filter { it !in installed })
        val protected = roles.protectedPackages()
        announce(installed.values.filter { it.isCandidate(known.keys, protected) })
    }

    private suspend fun announce(added: List<InstalledApp>) {
        if (added.isEmpty()) return
        val s = settings.current()
        rules.addUndecided(added.map { it.packageName }, s.newAppMode)
        if (!s.askOnInstall) return
        if (added.size <= MAX_SINGLE_NOTIFICATIONS) {
            added.forEach(notifications::showNewApp)
        } else {
            notifications.showNewAppsSummary(added.size)
        }
    }

    private fun InstalledApp.isCandidate(known: Set<String>, protected: Set<String>) =
        isUserFacing && packageName !in known && packageName !in protected

    private companion object {
        const val MAX_SINGLE_NOTIFICATIONS = 3
    }
}
