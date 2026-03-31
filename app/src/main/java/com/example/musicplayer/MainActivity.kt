import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.musicplayer.RoomDatabase
import com.example.musicplayer.network.RetrofitClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private val viewModel: MusicViewModel by viewModels()
    private var job: Job? = null
    private val pattern: Pattern = Pattern.compile("your-regex-here")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fetchData()
    }

    private fun fetchData() {
        job?.cancel() // Cancel any running job
        job = lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getMusicData()
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    // Insert data into Room database
                    viewModel.insertData(data)
                } else {
                    // Log error and handle it
                    Log.e(TAG, "Error: ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network call failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        job?.cancel() // Cancel the job when the activity is destroyed
        super.onDestroy()
    }
}

class MusicViewModel : ViewModel() {
    private val db = RoomDatabase.getInstance(application)

    fun insertData(data: MusicData) {
        viewModelScope.launch {
            db.musicDao().insert(data)
        }
    }
}