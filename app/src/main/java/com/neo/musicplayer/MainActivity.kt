import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fetchData()
    }

    private fun fetchData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val data = repository.getData() // Async operation using repository
                withContext(Dispatchers.Main) {
                    updateUI(data) // Update UI on the main thread
                }
            } catch (e: Exception) {
                handleError(e) // Proper error handling
            }
        }
    }

    private fun updateUI(data: DataType) {
        // Update the UI with data
    }

    private fun handleError(e: Exception) {
        // Handle errors here, log or show a message
    }
}