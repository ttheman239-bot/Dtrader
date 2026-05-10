package com.dtrader.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.dtrader.app.ui.screens.FlowScreen
import com.dtrader.app.ui.screens.Phase1Screen
import com.dtrader.app.ui.screens.Phase2Screen
import com.dtrader.app.ui.screens.Phase3Screen
import com.dtrader.app.ui.screens.ToolsScreen

private enum class Tab(val title: String, val short: String, val icon: ImageVector) {
    Flow("Real-Time Flow", "Flow", Icons.Filled.TrendingUp),
    Phase1("Phase 1 — Pre-Market", "Pre-Mkt", Icons.Filled.Dashboard),
    Phase2("Phase 2 — Open", "Open", Icons.Filled.AutoGraph),
    Phase3("Phase 3 — Execution", "Execute", Icons.Filled.Insights),
    Tools("Tools", "Tools", Icons.Filled.Link),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtraderApp() {
    var selected by remember { mutableStateOf(Tab.Flow) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected.title, style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.short) },
                        label = { Text(tab.short) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (selected) {
            Tab.Flow -> FlowScreen(Modifier.padding(padding))
            Tab.Phase1 -> Phase1Screen(Modifier.padding(padding))
            Tab.Phase2 -> Phase2Screen(Modifier.padding(padding))
            Tab.Phase3 -> Phase3Screen(Modifier.padding(padding))
            Tab.Tools -> ToolsScreen(Modifier.padding(padding))
        }
    }
}
