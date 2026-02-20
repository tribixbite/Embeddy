package app.embeddy.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.embeddy.R
import app.embeddy.ui.screens.ConvertScreen
import app.embeddy.ui.screens.InspectScreen
import app.embeddy.ui.screens.SquooshScreen
import app.embeddy.ui.screens.UploadScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold() {
    var currentTab by rememberSaveable { mutableStateOf(EmbeddyTab.CONVERT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            EmbeddyNavigationBar(
                currentTab = currentTab,
                onTabSelected = { currentTab = it },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        AnimatedContent(
            targetState = currentTab,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_content",
        ) { tab ->
            when (tab) {
                EmbeddyTab.CONVERT -> ConvertScreen()
                EmbeddyTab.INSPECT -> InspectScreen()
                EmbeddyTab.UPLOAD -> UploadScreen()
                EmbeddyTab.SQUOOSH -> SquooshScreen()
            }
        }
    }
}

@Composable
private fun EmbeddyNavigationBar(
    currentTab: EmbeddyTab,
    onTabSelected: (EmbeddyTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        EmbeddyTab.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = stringResource(tab.label),
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
