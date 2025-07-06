package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.compottie.Compottie
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.nfc_icon
import multipazidentityreader.composeapp.generated.resources.qr_icon
import multipazidentityreader.composeapp.generated.resources.start_screen_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.multipaz.cbor.DataItem
import org.multipaz.compose.permissions.rememberBluetoothPermissionState
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.nfc.scanNfcMdocReader
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.nfcTagScanningSupported
import org.multipaz.nfc.nfcTagScanningSupportedWithoutDialog
import org.multipaz.prompt.PromptModel
import org.multipaz.util.UUID

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StartScreen(
    settingsModel: SettingsModel,
    promptModel: PromptModel,
    onMenuPressed: () -> Unit,
    onScanQrClicked: () -> Unit,
    onNfcHandover: suspend (
        transport: MdocTransport,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        updateMessage: ((message: String) -> Unit)?
    ) -> Unit
) {
    val blePermissionState = rememberBluetoothPermissionState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(stringResource(Res.string.start_screen_title))
                    }
                },
                onMenuPressed = onMenuPressed,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            if (!blePermissionState.isGranted) {
                RequestBluetoothPermission(blePermissionState)
            } else {
                StartScreenWithBluetoothPermission(
                    settingsModel = settingsModel,
                    promptModel = promptModel,
                    onScanQrClicked = onScanQrClicked,
                    onNfcHandover = onNfcHandover
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartScreenWithBluetoothPermission(
    settingsModel: SettingsModel,
    promptModel: PromptModel,
    onScanQrClicked: () -> Unit,
    onNfcHandover: suspend (
        transport: MdocTransport,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        updateMessage: ((message: String) -> Unit)?
    ) -> Unit
) {
    val isDarkTheme = isSystemInDarkTheme()
    val selectedQueryName = remember { mutableStateOf(
        ReaderQuery.valueOf(settingsModel.selectedQueryName.value).name
    )}
    val coroutineScope = rememberCoroutineScope { promptModel }
    val nfcComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            if (isDarkTheme) {
                Res.readBytes("files/nfc_animation_dark.json").decodeToString()
            } else {
                Res.readBytes("files/nfc_animation.json").decodeToString()
            }
        )
    }
    val nfcProgress by animateLottieCompositionAsState(
        composition = nfcComposition,
        iterations = Compottie.IterateForever
    )

    // On Platforms that support NFC scanning without a dialog, start scanning as soon
    // as we enter this screen. We'll get canceled when switched away because `coroutineScope`
    // will get canceled.
    //
    LaunchedEffect(Unit) {
        if (nfcTagScanningSupportedWithoutDialog) {
            coroutineScope.launch {
                scanNfcMdocReader(
                    message = null,
                    options = MdocTransportOptions(bleUseL2CAP = true),
                    transportFactory = MdocTransportFactory.Default,
                    // TODO: maybe do UI
                    selectConnectionMethod = { connectionMethods -> connectionMethods.first() },
                    negotiatedHandoverConnectionMethods = listOf(
                        MdocConnectionMethodBle(
                            supportsPeripheralServerMode = false,
                            supportsCentralClientMode = true,
                            peripheralServerModeUuid = null,
                            centralClientModeUuid = UUID.randomUUID(),
                        )
                    ),
                    onHandover = onNfcHandover
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.weight(0.1f))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                16.dp,
                alignment = Alignment.CenterHorizontally
            )
        ) {
            for (query in ReaderQuery.entries) {
                FilterChip(
                    selected = query.name == selectedQueryName.value,
                    onClick = {
                        selectedQueryName.value = query.name
                        settingsModel.selectedQueryName.value = query.name
                    },
                    label = { Text(text = query.displayName) },
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        if (!nfcTagScanningSupported) {
            // This is for phones that don't support NFC scanning
            Text(
                text = "Scan QR code from Wallet",
                style = MaterialTheme.typography.titleLargeEmphasized,
                fontWeight = FontWeight.Bold
            )
        } else {
            Image(
                painter = rememberLottiePainter(
                    composition = nfcComposition,
                    progress = { nfcProgress },
                ),
                contentDescription = null,
                modifier = Modifier.size(200.dp)
            )
            Text(
                text = "Hold to Wallet",
                style = MaterialTheme.typography.titleLargeEmphasized,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.weight(0.5f))

        // Only show the "Scan NFC" button on platforms which require the system NFC Scan dialog (iOS)
        // and if the device actually supports NFC scanning functionality.
        if (nfcTagScanningSupported && !nfcTagScanningSupportedWithoutDialog) {
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        scanNfcMdocReader(
                            message = "Hold to Wallet",
                            options = MdocTransportOptions(bleUseL2CAP = true),
                            transportFactory = MdocTransportFactory.Default,
                            // TODO: maybe do UI
                            selectConnectionMethod = { connectionMethods -> connectionMethods.first() },
                            negotiatedHandoverConnectionMethods = listOf(
                                MdocConnectionMethodBle(
                                    supportsPeripheralServerMode = false,
                                    supportsCentralClientMode = true,
                                    peripheralServerModeUuid = null,
                                    centralClientModeUuid = UUID.randomUUID(),
                                )
                            ),
                            onHandover = onNfcHandover
                        )
                    }
                }
            ) {
                Icon(
                    painter = painterResource(Res.drawable.nfc_icon),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    text = "Scan NFC"
                )
            }
        }

        OutlinedButton(
            onClick = { onScanQrClicked() }
        ) {
            Icon(
                painter = painterResource(Res.drawable.qr_icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = "Scan QR Code"
            )
        }
    }
}
