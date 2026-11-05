package com.capyreader.app.ui.articles.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ArticleBarDefaults {
    val TopBarHeight = 64.dp
    val FloatingToolbarHeight = 64.dp
    val FloatingToolbarBottomGap = 12.dp
    val BottomBarHeight = FloatingToolbarHeight + FloatingToolbarBottomGap

    // Uses the stable (initial) status bar height so that layout geometry does
    // not shift when the system status bar is hidden while reading.
    val topBarOffset: Dp
        @Composable get() = LocalStableStatusBarHeight.current + TopBarHeight
}
