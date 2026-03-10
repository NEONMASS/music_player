package com.neo.musicplayer

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ============================================================================
// 1. THE SONG VAULT (Upgraded with Favorites!)
// ============================================================================
@Entity(tableName = "saved_songs")
data class SongEntity(
    @PrimaryKey val localMediaId: Long,
    val customTitle: String?,
    val customArtist: String?,
    val fetchedTitle: String?,   
    val fetchedArtist: String?,  
    val fetchedArtUrl: String?,
    val fetchedLyrics: String?,
    val isFavorite: Boolean = false // NEW: The Liked Songs flag!
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

    // NEW: Instantly toggle the heart button status!
    @Query("UPDATE saved_songs SET isFavorite = :isFavorite WHERE localMediaId = :id")
    suspend fun updateFavoriteStatus(id: Long, isFavorite: Boolean)

    // NEW: Quickly grab all favorite songs for a dedicated Playlist screen
    @Query("SELECT * FROM saved_songs WHERE isFavorite = 1")
    fun getFavoriteSongs(): Flow<List<SongEntity>>

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
    version = 3, // THE CRASH FIX: Bumped to 3 to safely add the Favorite column
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
                .fallbackToDestructiveMigration() // Safely handles V2 -> V3
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}