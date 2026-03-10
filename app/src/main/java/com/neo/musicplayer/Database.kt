package com.neo.musicplayer

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================================
// 1. THE SONG VAULT (The Offline Cache & Self-Healing Memory)
// ============================================================================
@Entity(tableName = "saved_songs")
data class SongEntity(
    @PrimaryKey val localMediaId: Long,   // The unique ID matching the MP3 on your phone
    val customTitle: String?,             // Saves your manual title edits
    val customArtist: String?,            // Saves your manual artist edits
    val fetchedArtUrl: String?,           // Caches the 1000x1000 internet cover
    val fetchedLyrics: String?            // Caches the lyrics from LRCLIB
)

// ============================================================================
// 2. THE PLAYLIST LEDGER
// ============================================================================
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

// ============================================================================
// 3. THE BRIDGE (Many-to-Many Relationship)
// ============================================================================
@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "localMediaId"]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val localMediaId: Long
)

// ============================================================================
// 4. THE RELATIONAL QUERY MODEL (Automatically joins Playlists and Songs)
// ============================================================================
data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "localMediaId",
        associateBy = Junction(PlaylistSongCrossRef::class)
    )
    val songs: List<SongEntity>
)

// ============================================================================
// 5. THE DAO (Data Access Object - The API for your App to talk to the DB)
// ============================================================================
@Dao
interface LibraryDao {
    // --- Self-Healing Memory Commands ---
    @Query("SELECT * FROM saved_songs WHERE localMediaId = :id LIMIT 1")
    suspend fun getSongMemory(id: Long): SongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSongMemory(song: SongEntity)

    // --- Playlist Engine Commands ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    // Returns a Flow so the UI instantly updates if a playlist is added/deleted
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
    version = 1,
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
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}