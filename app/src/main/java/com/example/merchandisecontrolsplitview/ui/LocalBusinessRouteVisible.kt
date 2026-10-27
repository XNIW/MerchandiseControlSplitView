package com.example.merchandisecontrolsplitview.ui

import androidx.compose.runtime.compositionLocalOf

/** Presentation only: denied routes unmount separate windows while retaining their RAM holders. */
internal val LocalBusinessRouteVisible = compositionLocalOf { true }
