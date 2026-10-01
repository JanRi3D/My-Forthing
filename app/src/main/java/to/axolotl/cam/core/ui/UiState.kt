package to.axolotl.cam.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Screen state rendered by [StateView]. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data class Error(val message: UiText, val retry: (() -> Unit)? = null) : UiState<Nothing>
    data class Ready<T>(val data: T) : UiState<T>
}

/** Text that is either a string resource (preferred) or dynamic text, e.g. a raw recorder value. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Dynamic(val text: String) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Dynamic -> text
}
