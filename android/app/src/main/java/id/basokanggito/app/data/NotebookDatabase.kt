package id.basokanggito.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// A versioned root snapshot includes its items/receipts; children reconcile atomically with the root.
@Entity(tableName = "book_records")
data class StoredRecord(@PrimaryKey val id: String, val date: String, val payload: String, val serverPayload: String? = null)
@Entity(tableName = "customers")
data class StoredCustomer(@PrimaryKey val id: String, val payload: String, val serverPayload: String? = null)
@Entity(tableName = "pending_operations", indices = [Index("recordId"), Index(value = ["operationId"], unique = true)])
data class PendingOperation(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0, val operationId: String, val recordId: String,
    val payload: String, val beforePayload: String?, val state: String = "pending", val error: String = "",
)
@Entity(tableName = "notebook_meta")
data class NotebookMeta(@PrimaryKey val key: String, val value: String)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM customers ORDER BY id") fun observeCustomers(): Flow<List<StoredCustomer>>
    @Query("SELECT * FROM customers ORDER BY id") suspend fun customers(): List<StoredCustomer>
    @Query("SELECT * FROM customers WHERE id=:id") suspend fun customer(id: String): StoredCustomer?
    @Upsert suspend fun putCustomer(customer: StoredCustomer)
    @Query("DELETE FROM customers WHERE id=:id") suspend fun removeCustomer(id: String)
    @Query("SELECT * FROM book_records ORDER BY date DESC,id") fun observeRecords(): Flow<List<StoredRecord>>
    @Query("SELECT * FROM pending_operations WHERE state IN ('pending','failed') ORDER BY sequence") fun observeQueue(): Flow<List<PendingOperation>>
    @Query("SELECT * FROM notebook_meta") fun observeMeta(): Flow<List<NotebookMeta>>
    @Query("SELECT * FROM pending_operations WHERE state IN ('pending','failed') ORDER BY sequence") suspend fun activeOperations(): List<PendingOperation>
    @Query("SELECT * FROM notebook_meta") suspend fun metadata(): List<NotebookMeta>
    @Query("SELECT * FROM book_records ORDER BY date DESC,id") suspend fun records(): List<StoredRecord>
    @Query("SELECT * FROM book_records WHERE id=:id") suspend fun record(id: String): StoredRecord?
    @Upsert suspend fun putRecord(record: StoredRecord)
    @Query("DELETE FROM book_records WHERE id=:id") suspend fun removeRecord(id: String)
    @Insert suspend fun queue(operation: PendingOperation)
    @Update suspend fun updateOperation(operation: PendingOperation)
    @Query("SELECT * FROM pending_operations WHERE recordId=:id AND state IN ('pending','failed') ORDER BY sequence") suspend fun activeFor(id: String): List<PendingOperation>
    @Query("SELECT * FROM pending_operations WHERE state='pending' AND recordId NOT IN (SELECT recordId FROM pending_operations WHERE state='failed') ORDER BY sequence LIMIT 1") suspend fun nextOperation(): PendingOperation?
    @Query("SELECT * FROM pending_operations ORDER BY sequence") suspend fun allOperations(): List<PendingOperation>
    @Query("SELECT value FROM notebook_meta WHERE `key`=:key") suspend fun meta(key: String): String?
    @Upsert suspend fun putMeta(meta: NotebookMeta)
}

val CustomerMigration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `customers` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, `serverPayload` TEXT, PRIMARY KEY(`id`))")
    }
}

@Database(entities = [StoredRecord::class, StoredCustomer::class, PendingOperation::class, NotebookMeta::class], version = 2, exportSchema = true)
abstract class NotebookDatabase : RoomDatabase() { abstract fun notebook(): NotebookDao }
