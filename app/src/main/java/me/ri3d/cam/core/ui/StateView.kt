package me.ri3d.cam.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.ri3d.cam.R

/** Renders Loading / Empty / Error (with optional retry) and hands [UiState.Ready] data to [content]. */
@Composable
fun <T> StateView(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    emptyText: String = stringResource(R.string.state_view_empty),
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is UiState.Ready -> content(state.data)
        UiState.Loading -> Centered(modifier) {
            val loading = stringResource(R.string.state_view_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
        }
        UiState.Empty -> Centered(modifier) { Message(emptyText) }
        is UiState.Error -> Centered(modifier) {
            Message(state.message.asString())
            state.retry?.let { retry ->
                FilledTonalButton(onClick = retry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
