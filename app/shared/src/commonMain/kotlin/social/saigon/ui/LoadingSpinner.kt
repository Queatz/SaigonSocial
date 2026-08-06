package social.saigon.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Wraps [CircularProgressIndicator] but only becomes visible after a 1 second delay,
 * to avoid flashing a spinner for very fast loads.
 */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier, useDelay: Boolean = true) {
    var visible by remember { mutableStateOf(!useDelay) }

    if (useDelay) {
        LaunchedEffect(Unit) {
            delay(1_000)
            visible = true
        }
    }

    if (visible) {
        CircularProgressIndicator(modifier = modifier)
    }
}
