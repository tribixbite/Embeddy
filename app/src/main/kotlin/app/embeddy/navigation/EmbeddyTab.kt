package app.embeddy.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.graphics.vector.ImageVector
import app.embeddy.R

/** Top-level navigation destinations. */
enum class EmbeddyTab(
    val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CONVERT(
        label = R.string.tab_convert,
        selectedIcon = Icons.Filled.Movie,
        unselectedIcon = Icons.Outlined.Movie,
    ),
    INSPECT(
        label = R.string.tab_inspect,
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search,
    ),
    UPLOAD(
        label = R.string.tab_upload,
        selectedIcon = Icons.Filled.Upload,
        unselectedIcon = Icons.Outlined.Upload,
    ),
    SQUOOSH(
        label = R.string.tab_squoosh,
        selectedIcon = Icons.Filled.Compress,
        unselectedIcon = Icons.Outlined.Compress,
    ),
}
