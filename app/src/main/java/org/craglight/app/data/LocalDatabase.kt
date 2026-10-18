package org.craglight.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "crags")
data class CragRecord(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "ticks")
data class TickRecord(
    @PrimaryKey val id: String,
    val cragId: String, val cragName: String, val routeId: String, val routeName: String,
    val grade: String, val style: String, val date: String, val notes: String
)
@Entity(tableName = "contributions")
data class ContributionRecord(
    @PrimaryKey val id: String, val cragId: String, val cragName: String,
    val category: String, val proposal: String, val sourceUrl: String,
    val license: String, val createdAt: String, val status: String = "queued_on_device"
)
@Entity(tableName = "weather")
data class WeatherRecord(@PrimaryKey val cragId: String, val fetchedAt: String, val json: String)
@Dao
interface LocalDao {
    @Query("SELECT * FROM crags") suspend fun crags(): List<CragRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCrags(rows: List<CragRecord>)
    @Query("DELETE FROM crags") suspend fun clearCatalogue()
    @Transaction suspend fun replaceCatalogue(rows: List<CragRecord>) { clearCatalogue(); putCrags(rows) }
    @Query("SELECT * FROM ticks ORDER BY date DESC, id DESC") fun ticks(): Flow<List<TickRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveTick(row: TickRecord)
    @Query("DELETE FROM ticks WHERE id = :id") suspend fun deleteTick(id: String)
    @Query("SELECT * FROM contributions ORDER BY createdAt DESC") fun contributions(): Flow<List<ContributionRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveContribution(row: ContributionRecord)
    @Query("DELETE FROM contributions WHERE id = :id") suspend fun deleteContribution(id: String)
    @Query("SELECT * FROM weather WHERE cragId = :id") suspend fun weather(id: String): WeatherRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveWeather(row: WeatherRecord)
}
@Database(entities = [CragRecord::class, TickRecord::class, ContributionRecord::class, WeatherRecord::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() { abstract fun dao(): LocalDao }
