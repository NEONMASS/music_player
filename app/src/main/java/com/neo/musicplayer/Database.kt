package com.neo.musicplayer

import android.content.Context
import androidx.room.*

// 1. THE TABLE: This is the shadow ledger that saves your edits and internet fetches
@Entity(tableName = "saved_songs")
data class SongEntity(
    @PrimaryKey val localMediaId: Long,   // The ID matching the MP3 on your phone
    val customTitle: String?,             // If you edit the title, it saves here
    val customArtist: String?,            // If you edit the artist, it saves here
    val fetchedArtUrl: String?,           // The 1000x1000 internet cover
    val fetchedLyrics: String?            // The lyrics from LRCLIB
)

// 2. THE DAO (Data Access Object): The commands to read/write to the database
@Dao
interface SongDao {
    // Look up a specific song by its MP3 ID
    @Query("SELECT * FROM saved_songs WHERE localMediaId = :id LIMIT 1")
    suspend fun getSongMemory(id: Long): SongEntity?

    // Save or update a song's memory (like when you edit it or it fetches new art)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSongMemory(song: SongEntity)
}

// 3. THE DATABASE ENGINE
@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hybrid_player_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}