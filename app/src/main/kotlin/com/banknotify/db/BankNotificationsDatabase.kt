package com.banknotify.db

import android.content.Context
import androidx.room.*

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

@Database(entities = [BankNotification::class], version = 1, exportSchema = false)
abstract class BankNotificationsDatabase : RoomDatabase() {
    abstract fun notificationDao(): BankNotificationDao

    companion object {
        @Volatile
        private var INSTANCE: BankNotificationsDatabase? = null

        fun getDatabase(context: Context): BankNotificationsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BankNotificationsDatabase::class.java,
                    "bank_notifications.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
