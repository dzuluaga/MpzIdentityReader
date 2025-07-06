package org.multipaz.identityreader

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.back_button_content_description
import org.jetbrains.compose.resources.stringResource

/**
 * The top app bar.
 *
 * @param onBackPressed the function to call when the back arrow is pressed or `null` to not show a back arrow.
 * @param onMenuPressed the function to call when the menu is pressed or `null` to not show a menu.
 * @param title the title to show or `null` to not show a title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: AnnotatedString? = null,
    onBackPressed: (() -> Unit)? = null,
    onMenuPressed: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {}
) {
    CenterAlignedTopAppBar(
        title = {
            title?.let { Text(text = it) }
        },
        modifier = Modifier,
        navigationIcon = {
            if (onBackPressed != null) {
                IconButton(onClick = onBackPressed) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.back_button_content_description)
                    )
                }
            } else {
                // No back arrow
                if (onMenuPressed != null) {
                    IconButton(
                        onClick = onMenuPressed
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = null
                        )
                    }
                }
            }
        },
        actions = {
            if (onBackPressed != null && onMenuPressed != null) {
                IconButton(
                    onClick = onMenuPressed
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = null
                    )
                }
            }
            actions()
        },
    )
}