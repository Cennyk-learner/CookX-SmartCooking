package com.smartcooking.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.LocalAppContainer

/** Creates a screen ViewModel scoped to the current back stack entry, keyed by the signed-in user. */
@Composable
inline fun <reified VM : ViewModel> cookxViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    val user = container.sessions.currentUserId.orEmpty()
    return viewModel(key = "${VM::class.java.name}:$user:${key.orEmpty()}") { create(container) }
}

/**
 * Creates a ViewModel shared by every screen of the activity (keyed by user), e.g. the kitchen and
 * the AI recipe tab, which drive the same cooking session.
 */
@Composable
inline fun <reified VM : ViewModel> cookxSharedViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    val user = container.sessions.currentUserId.orEmpty()
    val owner = androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity
    return viewModel(viewModelStoreOwner = owner, key = "${VM::class.java.name}:shared:$user") { create(container) }
}
