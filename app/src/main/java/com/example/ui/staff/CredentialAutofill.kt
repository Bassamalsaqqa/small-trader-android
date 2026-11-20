package com.example.ui.staff

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/** Registers the editable field with the Android Autofill Framework on Compose 1.7. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.credentialAutofill(types: List<AutofillType>, onFill: (String) -> Unit): Modifier = composed {
    val service = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val callback = rememberUpdatedState(onFill)
    val node = remember(types) { AutofillNode(types, onFill = { callback.value(it) }) }
    DisposableEffect(tree, node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged {
            if (it.isFocused && node.boundingBox != null) service?.requestAutofillForNode(node)
            else service?.cancelAutofillForNode(node)
        }
}
