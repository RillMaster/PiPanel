package com.rillmaster.pipanel.model

import androidx.compose.ui.graphics.Color

data class DrawerItemData(
    val label: String,
    val icon: Any,
    val color: Color,
    val screen: Screen? = null
)

data class DashboardTileData(
    val icon: Any,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)
