package app.embeddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.embeddy.ui.screens.HomeScreen
import app.embeddy.ui.theme.EmbeddyTheme
import app.embeddy.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Handle incoming share/view intents
        if (savedInstanceState == null) {
            intent?.let { viewModel.onSharedIntent(it) }
        }

        setContent {
            EmbeddyTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
    }
}
