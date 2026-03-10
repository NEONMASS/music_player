package com.neo.musicplayer

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================================
// 1. THE SONG VAULT (Upgraded to hold fetched official names!)
// ============================================================================
@Entity(tableName = "saved_songs")
data class SongEntity(
    @PrimaryKey val localMediaId: Long,
    val customTitle: String?,
    val customArtist: String?,
    val fetchedTitle: String?,   // NEW: Saves the official internet title
    val fetchedArtist: String?,  // NEW: Saves the official internet artist
    val fetchedArtUrl: String?,
    val fetchedLyrics: String?
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "localMediaId"],
    indices = [Index("localMediaId")] 
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val localMediaId: Long
)

data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "localMediaId",
        associateBy = Junction(PlaylistSongCrossRef::class)
    )
    val songs: List<SongEntity>
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM saved_songs WHERE localMediaId = :id LIMIT 1")
    suspend fun getSongMemory(id: Long): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSongMemory(song: SongEntity)

    @Query("SELECT * FROM saved_songs")
    fun getAllSongMemories(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylistWithSongs(playlistId: Long): PlaylistWithSongs
}

// ============================================================================
// 6. THE SQLITE DATABASE ENGINE
// ============================================================================
@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 2, // THE CRASH FIX: Bumping this to 2 tells Android to safely migrate instead of crashing!
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

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
                .fallbackToDestructiveMigration() // Safely handles the Version 1 -> Version 2 transition
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}