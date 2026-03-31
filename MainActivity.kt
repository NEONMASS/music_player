import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.util.regex.Pattern
import android.util.Log

class MainActivity : AppCompatActivity() {
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fetchData()
    }

    private fun fetchData() {
        job = coroutineScope.launch {
            try {
                val data = apiCallWithRetry()
                // Process the data
                Log.d("MainActivity", "Data fetched successfully: $data")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error fetching data: ${e.message}")
            }
        }
    }

    private suspend fun apiCallWithRetry(retryCount: Int = 3): String {
        var lastException: Exception? = null
        for (i in 1..retryCount) {
            try {
                return makeNetworkCall()
            } catch (e: Exception) {
                lastException = e
                Log.w("MainActivity", "Retrying... attempt $i")
                delay(2000) // Delay before retry
            }
        }
        throw lastException ?: Exception("Unknown error")
    }

    private suspend fun makeNetworkCall(): String {
        // Simulate network call
        delay(1000) // Simulate delay
        return "sample data"
    }

    private fun optimizeRegex(input: String): Boolean {
        val pattern = Pattern.compile("^\s*\w+\s*$")
        val matcher = pattern.matcher(input)
        return matcher.matches()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel() // Cancel any ongoing coroutines
    }
}