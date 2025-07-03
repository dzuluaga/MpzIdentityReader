package org.multipaz.identityreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.compose_multiplatform
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.Platform


@Composable
@Preview
fun App() {
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                val readerBackendClient = ReaderBackendClient(
                    // Use the deployed backend by default..
                    readerBackendUrl = "https://verifier.multipaz.org/identityreaderbackend",
                    //readerBackendUrl = "http://127.0.0.1:8020",
                    storage = Platform.nonBackedUpStorage,
                    httpClientEngineFactory = platformHttpClientEngineFactory(),
                    secureArea = SoftwareSecureArea.create(Platform.nonBackedUpStorage),
                    numKeys = 10,
                )
                val (keyInfo, certification) = readerBackendClient.getKey()
                println("Woohoo replenished key")
                println("keyInfo $keyInfo")
                println("certification $certification")
            } catch (e: Throwable) {
                println("Error replenishing keys: $e")
                e.printStackTrace()
            }
        }
    }

    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("Click me!")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
        }
    }
}