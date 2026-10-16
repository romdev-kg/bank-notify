package com.banknotify.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "bank_notifications")
data class BankNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankName: String,
    val amount: String,
    val operationType: String,
    val accountNumber: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sentToTelegram: Boolean = false,
    val errorMessage: String? = null
)

@Dao
interface BankNotificationDao {
    @Insert
    suspend fun insert(notification: BankNotification): Long

    @Query("SELECT * FROM bank_notifications ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentNotifications(): List<BankNotification>

    @Query("UPDATE bank_notifications SET sentToTelegram = 1 WHERE id = :id")
    suspend fun markAsSent(id: Long)

    @Query("UPDATE bank_notifications SET errorMessage = :error WHERE id = :id")
    suspend fun recordError(id: Long, error: String)

    @Query("DELETE FROM bank_notifications WHERE timestamp < :timestamp")
    suspend fun deleteOldNotifications(timestamp: Long)
}

@Database(entities = [BankNotification::class, OutboxMessage::class], version = 2, exportSchema = false)
abstract class BankNotificationsDatabase : RoomDatabase() {
    abstract fun notificationDao(): BankNotificationDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS telegram_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventKey TEXT NOT NULL, message TEXT NOT NULL, createdAt INTEGER NOT NULL, sent INTEGER NOT NULL, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, lastError TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_telegram_outbox_eventKey ON telegram_outbox (eventKey)")
            }
        }
        @Volatile
        private var INSTANCE: BankNotificationsDatabase? = null

        fun getDatabase(context: Context): BankNotificationsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BankNotificationsDatabase::class.java,
                    "bank_notifications.db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
