package dev.hushyari.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.hushyari.ui.navigation.NavGraph
import dev.hushyari.ui.theme.HushyariTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HushyariTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        val extras = intent.extras ?: return

        when {
            action == "dev.hushyari.action.START_TASK" -> {
                val gamePackage = extras.getString("game_package")
                val taskDescription = extras.getString("task_description")
            }
            action == "dev.hushyari.action.STOP_TASK" -> {
            }
            action == "dev.hushyari.action.AUTOMATION" -> {
                val command = extras.getString("command")
            }
            intent.data != null -> {
                val uri = intent.data
                when (uri?.scheme) {
                    "hushyari" -> {
                        when (uri.host) {
                            "start" -> {
                                val gamePackage = uri.getQueryParameter("package")
                                val task = uri.getQueryParameter("task")
                            }
                            "stop" -> {
                            }
                        }
                    }
                }
            }
        }
    }
}
