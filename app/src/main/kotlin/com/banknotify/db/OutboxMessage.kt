package com.banknotify.db

import androidx.room.*

@Entity(tableName = "telegram_outbox", indices = [Index(value = ["eventKey"], unique = true)])
data class OutboxMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventKey: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sent: Boolean = false,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: OutboxMessage): Long

    @Query("SELECT * FROM telegram_outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxMessage?

    @Query("SELECT id FROM telegram_outbox WHERE sent = 0 ORDER BY id")
    suspend fun pendingIds(): List<Long>

    @Query("SELECT COUNT(*) FROM telegram_outbox WHERE sent = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM telegram_outbox WHERE sent = 0 AND (attempts > 0 OR createdAt < :before))")
    suspend fun hasDeliveryProblems(before: Long): Boolean

    @Query("SELECT * FROM telegram_outbox ORDER BY id DESC LIMIT 100")
    suspend fun recent(): List<OutboxMessage>

    @Query("UPDATE telegram_outbox SET sent = 1, lastError = NULL, nextAttemptAt = 0 WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query("UPDATE telegram_outbox SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :retryAt WHERE id = :id")
    suspend fun failed(id: Long, error: String, retryAt: Long)
}
