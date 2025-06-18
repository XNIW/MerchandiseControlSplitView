package com.example.merchandisecontrolsplitview.ui.navigation

sealed class Screen(val route: String) {
    data object FilePicker   : Screen("filePicker")
    data object PreGenerate  : Screen("preGenerate")
    data object Generated    : Screen("generated")
    data object History      : Screen("history")
}