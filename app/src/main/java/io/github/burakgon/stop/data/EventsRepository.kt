package io.github.burakgon.stop.data

import android.content.Context
import androidx.core.content.edit
import io.github.burakgon.stop.daemon.DaemonEvent
import io.github.burakgon.stop.data.db.EventDao
import io.github.burakgon.stop.data.db.EventEntity
import io.github.burakgon.stop.data.db.PackageCount
import io.github.burakgon.stop.model.StopEvent
import io.github.burakgon.stop.model.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class EventsRepository(context: Context, private val dao: EventDao) {
    private val prefs = context.getSharedPreferences("events", Context.MODE_PRIVATE)

    /** Ids up to this one were cleared by the user and must not be imported again. */
    private var clearedUpTo: Long
        get() = prefs.getLong(KEY_CLEARED_UP_TO, 0)
        set(value) = prefs.edit { putLong(KEY_CLEARED_UP_TO, value) }

    fun since(time: Long): Flow<List<StopEvent>> = dao.observeSince(time).map { list -> list.map { it.toModel() } }

    fun recent(limit: Int): Flow<List<StopEvent>> = dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    fun forPackage(packageName: String, limit: Int): Flow<List<StopEvent>> =
        dao.observeForPackage(packageName, limit).map { list -> list.map { it.toModel() } }

    /** Number of stops per package since [time]. */
    fun countsSince(time: Long): Flow<Map<String, PackageCount>> =
        dao.observeCounts(time).map { list -> list.associateBy { it.packageName } }

    suspend fun add(events: List<DaemonEvent>) {
        val cleared = clearedUpTo
        val fresh = events.filter { it.id > cleared }
        if (fresh.isEmpty()) return
        dao.insert(fresh.map { EventEntity(it.id, it.time, it.packageName, it.reason, it.sinceLeftMs) })
    }

    suspend fun latestId(): Long = maxOf(dao.maxId() ?: 0, clearedUpTo)

    suspend fun prune() = dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)

    suspend fun clear() {
        clearedUpTo = latestId()
        dao.deleteAll()
    }

    private fun EventEntity.toModel() = StopEvent(id, time, packageName, StopReason.of(reason), sinceLeftMs)

    companion object {
        val RETENTION_MS = TimeUnit.DAYS.toMillis(30)
        private const val KEY_CLEARED_UP_TO = "cleared_up_to"
    }
}
