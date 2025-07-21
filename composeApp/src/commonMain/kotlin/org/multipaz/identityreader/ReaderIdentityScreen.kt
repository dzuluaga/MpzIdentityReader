package org.multipaz.identityreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.reader_identity_title
import org.jetbrains.compose.resources.stringResource
import org.multipaz.crypto.X509CertChain
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.requestPassphrase
import org.multipaz.securearea.PassphraseConstraints

@Composable
fun ReaderIdentityScreen(
    promptModel: PromptModel,
    readerBackendClient: ReaderBackendClient,
    settingsModel: SettingsModel,
    onBackPressed: () -> Unit,
    onShowCertificateChain: (certChain: X509CertChain) -> Unit
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    val showImportErrorDialog = remember { mutableStateOf<String?>(null) }

    val importReaderKeyFilePicker = rememberFilePicker(
        types = listOf(
            "application/x-pkcs12",
        ),
        allowMultiple = false,
        onResult = { files ->
            if (files.isNotEmpty()) {
                val pkcs12Contents = files[0]
                coroutineScope.launch {
                    val passphrase = requestPassphrase(
                        title = "Import reader certificate",
                        subtitle = "The PKCS#12 file is protected by a passphrase which have " +
                                "should have been shared with you. Enter the passphrase to continue",
                        passphraseConstraints = PassphraseConstraints.NONE,
                        passphraseEvaluator = { passphrase ->
                            try {
                                parsePkcs12(pkcs12Contents, passphrase)
                                null
                            } catch (_: WrongPassphraseException) {
                                "Wrong passphrase. Try again"
                            } catch (_: Throwable) {
                                // If parsing fails for reasons other than the wrong passphrase
                                // supplied, just pretend the passphrase worked and we'll catch
                                // the error below and show it to the user
                                null
                            }
                        }
                    )
                    if (passphrase != null) {
                        try {
                            val (privateKey, certChain) = parsePkcs12(pkcs12Contents, passphrase)
                            require(privateKey.publicKey == certChain.certificates[0].ecPublicKey) {
                                "First certificate is not for the given key"
                            }
                            require(certChain.validate()) {
                                "Certificate chain did not validate"
                            }
                            // TODO: add a couple of additional checks for example that the leaf certificate
                            //   has the correct keyUsage flags, etc.
                            //
                            settingsModel.customReaderAuthKey.value = privateKey
                            settingsModel.customReaderAuthCertChain.value = certChain
                            settingsModel.readerAuthMethod.value = ReaderAuthMethod.CUSTOM_KEY
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            showImportErrorDialog.value = "Importing reader key failed: $e"
                        }
                    }
                }
            }
        }
    )

    showImportErrorDialog.value?.let {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog.value = null },
            confirmButton = {
                TextButton(
                    onClick = { showImportErrorDialog.value = null }
                ) {
                    Text(text = "Close")
                }
            },
            title = {
                Text(text = "Error importing reader key")
            },
            text = {
                Text(text = it)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = AnnotatedString(stringResource(Res.string.reader_identity_title)),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            val readerAuthMethod = settingsModel.readerAuthMethod.collectAsState()
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = """
When making a request for identity attributes a reader identity may be used to cryptographically
sign the request. The wallet receiving the request may use this to inform the identity holder who
the request is from
                    """.trimIndent().replace("\n", " ").trim(),
                )

                val entries = mutableListOf<@Composable () -> Unit>()
                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = readerAuthMethod.value == ReaderAuthMethod.NO_READER_AUTH,
                            onClick = {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.NO_READER_AUTH
                                settingsModel.customReaderAuthKey.value = null
                                settingsModel.customReaderAuthCertChain.value = null
                            }
                        )
                        EntryItem(
                            key = "Don't use reader authentication",
                            valueText = "The request won't be signed and the receiving wallet " +
                                    "won't know who's asking"
                        )
                    }
                }
                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = readerAuthMethod.value == ReaderAuthMethod.STANDARD_READER_AUTH,
                            onClick = {
                                settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH
                                settingsModel.customReaderAuthKey.value = null
                                settingsModel.customReaderAuthCertChain.value = null
                            }
                        )
                        EntryItem(
                            key = "Standard reader authentication",
                            valueText = textViewCertificateLink(
                                text = "The Multipaz Identity Reader CA will be used to " +
                                    "certify single-use reader keys",
                                showViewCertificate = readerAuthMethod.value == ReaderAuthMethod.STANDARD_READER_AUTH,
                                onViewCertificateClicked = {
                                    coroutineScope.launch {
                                        onShowCertificateChain(readerBackendClient.getKey().second)
                                    }
                                },
                            )
                        )
                    }
                }
                entries.add {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = readerAuthMethod.value == ReaderAuthMethod.CUSTOM_KEY,
                            onClick = {
                                importReaderKeyFilePicker.launch()
                            }
                        )
                        EntryItem(
                            key = "Use reader certificate from PKCS#12 file",
                            valueText = textViewCertificateLink(
                                text = "Uses a custom key to sign requests. The same key will be " +
                                        "used to sign all requests",
                                showViewCertificate = readerAuthMethod.value == ReaderAuthMethod.CUSTOM_KEY,
                                onViewCertificateClicked = {
                                    onShowCertificateChain(settingsModel.customReaderAuthCertChain.value!!)
                                },
                            )
                        )
                    }
                }
                EntryList(
                    title = "Reader identity",
                    entries = entries
                )
            }
        }
    }
}

private fun textViewCertificateLink(
    text: String,
    showViewCertificate: Boolean,
    onViewCertificateClicked: () -> Unit
) = buildAnnotatedString {
    append(text)
    if (showViewCertificate) {
        append(". ")
        withLink(
            LinkAnnotation.Clickable(
                tag = "cert",
                linkInteractionListener = { link -> onViewCertificateClicked() }
            )
        ) {
            withStyle(
                style = SpanStyle(
                    color = Color.Blue,
                    textDecoration = TextDecoration.Underline
                )
            ) {
                append("View certificate")
            }
        }
    }
}