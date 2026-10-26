package io.github.burakgon.stop.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val packageName: String,
    val mode: String,
    val decided: Boolean,
    val graceSeconds: Int?,
    val updatedAt: Long,
)

@Entity(tableName = "events", indices = [Index("time"), Index("packageName")])
data class EventEntity(
    @PrimaryKey val id: Long,
    val time: Long,
    val packageName: String,
    val reason: String,
    val sinceLeftMs: Long,
)

data class PackageCount(
    val packageName: String,
    val count: Int,
    val lastTime: Long,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE packageName = :packageName")
    suspend fun get(packageName: String): RuleEntity?

    @Upsert
    suspend fun upsert(rules: List<RuleEntity>)

    @Query("DELETE FROM rules WHERE packageName IN (:packageNames)")
    suspend fun delete(packageNames: List<String>)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE time >= :since ORDER BY time DESC")
    fun observeSince(since: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query(
        "SELECT packageName, COUNT(*) AS count, MAX(time) AS lastTime FROM events " +
            "WHERE time >= :since GROUP BY packageName"
    )
    fun observeCounts(since: Long): Flow<List<PackageCount>>

    @Query("SELECT * FROM events WHERE packageName = :packageName ORDER BY time DESC LIMIT :limit")
    fun observeForPackage(packageName: String, limit: Int): Flow<List<EventEntity>>

    @Query("SELECT MAX(id) FROM events")
    suspend fun maxId(): Long?

    @Query("DELETE FROM events WHERE time < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}

@Database(entities = [RuleEntity::class, EventEntity::class], version = 1)
abstract class StopDatabase : RoomDatabase() {
    abstract fun rules(): RuleDao

    abstract fun events(): EventDao

    companion object {
        fun create(context: Context): StopDatabase =
            Room.databaseBuilder(context, StopDatabase::class.java, "stop.db").build()
    }
}
