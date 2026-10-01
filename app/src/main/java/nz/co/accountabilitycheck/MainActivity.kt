package nz.co.accountabilitycheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.co.accountabilitycheck.domain.ThemeMode
import nz.co.accountabilitycheck.ui.AccountabilityScreen
import nz.co.accountabilitycheck.ui.AccountabilityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as AccountabilityApp).repository
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            AccountabilityTheme(darkTheme = dark) {
                AccountabilityScreen(repository)
            }
        }
    }
}
