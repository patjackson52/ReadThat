package dev.readthat.flows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.readthat.flows.model.LoadState
import dev.readthat.flows.vm.DashboardEvent
import dev.readthat.flows.vm.DashboardViewModel
import dev.readthat.flows.vm.SearchViewModel

/**
 * The UI half of the story: how a Compose screen should consume these flows.
 *
 * Two rules on display here:
 *
 * 1. **`collectAsStateWithLifecycle()`, not `collectAsState()`.**
 *    `collectAsState` keeps collecting while the app is in the background, so a
 *    `WhileSubscribed` upstream never gets the chance to stop. The lifecycle-aware
 *    version unsubscribes at STOPPED, which is what makes the 5-second grace period
 *    in the ViewModel actually mean something.
 *
 * 2. **One-shot events are consumed in `LaunchedEffect`, never in composition.**
 *    Reading an event during composition would re-fire it on every recomposition.
 *    `LaunchedEffect(Unit)` collects exactly once per entry into composition.
 */
@Composable
fun FlowsDemoScreen(
    dashboardViewModel: DashboardViewModel,
    searchViewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val state by dashboardViewModel.uiState.collectAsStateWithLifecycle()
    val query by searchViewModel.query.collectAsStateWithLifecycle()
    val results by searchViewModel.results.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot event consumption. Keyed on Unit so it survives recomposition and
    // runs exactly once per composition entry.
    LaunchedEffect(Unit) {
        dashboardViewModel.events.collect { event ->
            when (event) {
                is DashboardEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                DashboardEvent.NavigateToLogin -> snackbarHostState.showSnackbar("navigate → login")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dashboard", style = MaterialTheme.typography.titleLarge)

            when (val user = state.user) {
                is LoadState.Loading -> CircularProgressIndicator()
                is LoadState.Failure -> Text("Failed: ${user.message}")
                is LoadState.Success -> Text(
                    "${user.data.displayName} · ${user.data.karma} karma",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                if (state.isOffline) "OFFLINE — autoplay disabled" else "Online",
                style = MaterialTheme.typography.labelLarge,
            )

            Row2("Autoplay video", state.settings.autoplayVideo)
            Row2("Can autoplay (derived)", state.canAutoplay)

            Button(onClick = { dashboardViewModel.refresh() }) { Text("Refresh") }

            HorizontalDivider()

            Text("Search", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = searchViewModel::onQueryChanged,
                label = { Text("debounced 300ms, flatMapLatest") },
                modifier = Modifier.fillMaxWidth(),
            )

            when (val r = results) {
                is LoadState.Loading -> CircularProgressIndicator()
                is LoadState.Failure -> Text("Search failed: ${r.message}")
                is LoadState.Success -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(r.data, key = { it.id }) { post ->
                        Text("r/${post.subreddit} · ${post.title}")
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(label: String, value: Boolean) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = null)
    }
}
