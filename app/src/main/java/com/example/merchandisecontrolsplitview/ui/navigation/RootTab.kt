package com.example.merchandisecontrolsplitview.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.example.merchandisecontrolsplitview.R

data class RootTab(
    val screen: Screen,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
)

val rootTabs = listOf(
    RootTab(
        screen = Screen.FilePicker,
        labelRes = R.string.inventory_title,
        icon = Icons.Filled.UploadFile
    ),
    RootTab(
        screen = Screen.Database,
        labelRes = R.string.database,
        icon = Icons.Filled.Storage
    ),
    RootTab(
        screen = Screen.History,
        labelRes = R.string.history_root_title,
        icon = Icons.Filled.History
    ),
    RootTab(
        screen = Screen.Options,
        labelRes = R.string.options,
        icon = Icons.Filled.Settings
    )
)

fun NavDestination?.currentRootTab(): RootTab? = rootTabs.firstOrNull { tab ->
    this?.hierarchy?.any { destination -> destination.route == tab.screen.route } == true
}
