package org.multipaz.identityreader

import androidx.compose.ui.window.ComposeUIViewController

private val app = App(urlLaunchData = null)

fun MainViewController() = ComposeUIViewController { app.Content() }