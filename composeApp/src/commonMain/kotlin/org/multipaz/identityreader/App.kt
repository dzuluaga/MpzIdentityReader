package org.multipaz.identityreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.io.bytestring.ByteString
import multipazidentityreader.composeapp.generated.resources.Res
import multipazidentityreader.composeapp.generated.resources.about_screen_title
import multipazidentityreader.composeapp.generated.resources.settings_screen_title
import multipazidentityreader.composeapp.generated.resources.trusted_issuers_screen_title
import org.jetbrains.compose.resources.stringResource
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.crypto.X509Cert
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.CompositeTrustManager
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

data class UrlLaunchData(
    val url: String,
    val finish: () -> Unit,
)

/**
 * App instance.
 *
 * @param urlLaunchData if launched from an intent for mdoc://<base64-of-device-engagement>, this
 *   contains the URL and the app will start at [SelectRequestDestination]. This is useful for when
 *   being launched from the camera.
 */
class App(
    private val urlLaunchData: UrlLaunchData?
) {
    companion object {
        private const val TAG = "App"
    }

    private val promptModel = Platform.promptModel
    private var startDestination: String? = null
    private val readerModel = ReaderModel()
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var builtInTrustManager: TrustManagerLocal
    private lateinit var userTrustManager: TrustManagerLocal
    private lateinit var compositeTrustManager: TrustManager
    private lateinit var settingsModel: SettingsModel
    private lateinit var readerBackendClient: ReaderBackendClient

    private val initLock = Mutex()
    private var initialized = false

    suspend fun initialize() {
        initLock.withLock {
            if (initialized) {
                return
            }

            startDestination = if (urlLaunchData != null) {
                val encodedDeviceEngagement =
                    ByteString(urlLaunchData.url.substringAfter("mdoc:").fromBase64Url())
                readerModel.reset()
                readerModel.setConnectionEndpoint(
                    encodedDeviceEngagement = encodedDeviceEngagement,
                    handover = Simple.NULL,
                    existingTransport = null
                )
                SelectRequestDestination.route
            } else {
                null
            }

            documentTypeRepository = DocumentTypeRepository()
            documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
            // Note: builtInTrustManager will be populated at app startup, see updateBuiltInIssuers()
            //   and its call-sites
            builtInTrustManager = TrustManagerLocal(
                storage = Platform.storage,
                identifier = "builtInTrustManager",
            )
            userTrustManager = TrustManagerLocal(
                storage = Platform.storage,
                identifier = "userTrustManager",
            )
            compositeTrustManager = CompositeTrustManager(listOf(builtInTrustManager, userTrustManager))

            settingsModel = SettingsModel.create(Platform.storage)

            readerBackendClient = ReaderBackendClient(
                // Use the deployed backend by default..
                readerBackendUrl = "https://verifier.multipaz.org/identityreaderbackend",
                //readerBackendUrl = "http://127.0.0.1:8020",
                storage = Platform.nonBackedUpStorage,
                httpClientEngineFactory = platformHttpClientEngineFactory(),
                secureArea = Platform.getSecureArea(),
                numKeys = 10,
            )

            initialized = true
        }
    }

    private suspend fun ensureReaderKeys() {
        if (settingsModel.readerAuthMethod.value != ReaderAuthMethod.STANDARD_READER_AUTH) {
            Logger.i(TAG, "Not using standard reader auth so not ensuring keys")
            return
        }
        try {
            readerBackendClient.getKey()
            Logger.i(TAG, "Success ensuring reader keys")
        } catch (e: Throwable) {
            Logger.i(TAG, "Error when ensuring reader keys: $e")
        }
    }

    private suspend fun updateBuiltInIssuers() {
        try {
            val currentVersion = settingsModel.builtInIssuersVersion.value
            val getTrustedIssuerResult = readerBackendClient.getTrustedIssuers(
                currentVersion = currentVersion
            )
            if (getTrustedIssuerResult != null) {
                val version = getTrustedIssuerResult.first
                val entries = getTrustedIssuerResult.second
                builtInTrustManager.getEntries().forEach {
                    builtInTrustManager.deleteEntry(it)
                }
                entries.forEach {
                    when (it) {
                        is TrustEntryX509Cert -> {
                            builtInTrustManager.addX509Cert(
                                certificate = it.certificate,
                                metadata = it.metadata
                            )
                        }
                        is TrustEntryVical -> {
                            builtInTrustManager.addVical(
                                encodedSignedVical = it.encodedSignedVical,
                                metadata = it.metadata
                            )
                        }
                    }
                }
                settingsModel.builtInIssuersVersion.value = version
                settingsModel.builtInIssuersUpdatedAt.value = Clock.System.now()
                Logger.i(TAG, "Updated built-in issuer list from $currentVersion to version $version")
            } else {
                Logger.i(TAG, "No update to built-in issuer list at version $currentVersion")
            }
        } catch (e: Throwable) {
            Logger.i(TAG, "Error when checking for updated issuer trust list: $e")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content() {
        var isInitialized = remember { mutableStateOf<Boolean>(initialized) }
        if (!isInitialized.value) {
            CoroutineScope(Dispatchers.Main).launch {
                initialize()
                isInitialized.value = true
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Initializing...")
            }
            return
        }

        PromptDialogs(promptModel)

        val coroutineScope = rememberCoroutineScope()

        // At application-startup, update trusted issuers and ensure reader keys.
        //
        // Also do this every 4 hours to make sure we pick up updates even if the app keeps running.
        //
        LaunchedEffect(Unit) {
            coroutineScope.launch {
                while (true) {
                    ensureReaderKeys()
                    updateBuiltInIssuers()
                    delay(4.hours)
                }
            }
        }

        val navController = rememberNavController()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        AppTheme {
            ModalNavigationDrawer(
                drawerContent = {
                    ModalDrawerSheet {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Multipaz Identity Reader",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            HorizontalDivider()

                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Settings,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(text = stringResource(Res.string.settings_screen_title)) },
                                selected = false,
                                onClick = {
                                    navController.navigate(route = SettingsDestination.route)
                                    coroutineScope.launch { drawerState.close() }
                                }
                            )
                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Help,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(text = stringResource(Res.string.about_screen_title)) },
                                selected = false,
                                onClick = {
                                    navController.navigate(route = AboutDestination.route)
                                    coroutineScope.launch { drawerState.close() }
                                }
                            )
                        }
                    }
                },
                drawerState = drawerState
            ) {
                PromptDialogs(Platform.promptModel)

                NavHost(
                    navController = navController,
                    startDestination = startDestination ?: StartDestination.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(route = StartDestination.route) {
                        StartScreen(
                            settingsModel = settingsModel,
                            promptModel = promptModel,
                            onMenuPressed = {
                                coroutineScope.launch {
                                    drawerState.open()
                                }
                            },
                            onScanQrClicked = {
                                navController.navigate(route = ScanQrDestination.route)
                            },
                            onNfcHandover = { transport, encodedDeviceEngagement, handover, updateMessage ->
                                readerModel.reset()
                                readerModel.setConnectionEndpoint(
                                    encodedDeviceEngagement = encodedDeviceEngagement,
                                    handover = handover,
                                    existingTransport = transport
                                )
                                val readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value)
                                readerModel.setDeviceRequest(
                                    readerQuery.generateDeviceRequest(
                                        settingsModel = settingsModel,
                                        encodedSessionTranscript = readerModel.encodedSessionTranscript,
                                        readerBackendClient = readerBackendClient
                                    )
                                )
                                navController.navigate(route = TransferDestination.route)
                            }
                        )
                    }
                    composable(route = ScanQrDestination.route) {
                        ScanQrScreen(
                            onBackPressed = { navController.navigateUp() },
                            onMdocQrCodeScanned = { mdocUri ->
                                coroutineScope.launch {
                                    val encodedDeviceEngagement =
                                        ByteString(mdocUri.substringAfter("mdoc:").fromBase64Url())
                                    readerModel.reset()
                                    readerModel.setConnectionEndpoint(
                                        encodedDeviceEngagement = encodedDeviceEngagement,
                                        handover = Simple.NULL,
                                        existingTransport = null
                                    )
                                    val readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value)
                                    readerModel.setDeviceRequest(
                                        readerQuery.generateDeviceRequest(
                                            settingsModel = settingsModel,
                                            encodedSessionTranscript = readerModel.encodedSessionTranscript,
                                            readerBackendClient = readerBackendClient
                                        )
                                    )
                                    navController.popBackStack()
                                    navController.navigate(route = TransferDestination.route)
                                }
                            }
                        )
                    }
                    composable(route = SelectRequestDestination.route) {
                        SelectRequestScreen(
                            readerModel = readerModel,
                            settingsModel = settingsModel,
                            readerBackendClient = readerBackendClient,
                            onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                            onContinueClicked = {
                                navController.popBackStack()
                                navController.navigate(route = TransferDestination.route)
                            }
                        )
                    }
                    composable(route = TransferDestination.route) {
                        TransferScreen(
                            readerModel = readerModel,
                            onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                            onTransferComplete = {
                                navController.popBackStack()
                                navController.navigate(route = ShowResultsDestination.route)
                            }
                        )
                    }
                    composable(route = ShowResultsDestination.route) {
                        ShowResultsScreen(
                            readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value),
                            readerModel = readerModel,
                            documentTypeRepository = documentTypeRepository,
                            issuerTrustManager = compositeTrustManager,
                            onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                        )
                    }
                    composable(route = SettingsDestination.route) {
                        SettingsScreen(
                            onBackPressed = { navController.navigateUp() },
                            onReaderIdentityPressed = {
                                navController.navigate(ReaderIdentityDestination.route)
                            },
                            onTrustedIssuersPressed = {
                                navController.navigate(TrustedIssuersDestination.route)
                            }
                        )
                    }
                    composable(route = ReaderIdentityDestination.route) {
                        ReaderIdentityScreen(
                            promptModel = promptModel,
                            readerBackendClient = readerBackendClient,
                            settingsModel = settingsModel,
                            onBackPressed = { navController.navigateUp() },
                            onShowCertificateChain = { certificateChain ->
                                val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                                navController.navigate(
                                    route = CertificateViewerDestination.route + "/" + certificateDataBase64
                                )
                            },
                        )
                    }
                    composable(route = TrustedIssuersDestination.route) {
                        TrustedIssuersScreen(
                            builtInTrustManager = builtInTrustManager,
                            userTrustManager = userTrustManager,
                            settingsModel = settingsModel,
                            onBackPressed = { navController.navigateUp() },
                            onTrustEntryClicked = { trustManagerId, entryIndex, justImported ->
                                navController.navigate(
                                    route = TrustEntryViewerDestination.route +
                                            "/" + trustManagerId + "/" + entryIndex + "/" + justImported
                                )
                            }
                        )
                    }
                    composable(route = AboutDestination.route) {
                        AboutScreen(
                            onBackPressed = { navController.navigateUp() },
                        )
                    }
                    composable(
                        route = CertificateViewerDestination.routeWithArgs,
                        arguments = CertificateViewerDestination.arguments
                    ) { backStackEntry ->
                        CertificateViewerScreen(
                            certificateDataBase64 = backStackEntry.arguments?.getString(
                                CertificateViewerDestination.CERTIFICATE_DATA_BASE64
                            )!!,
                            onBackPressed = { navController.navigateUp() },
                        )
                    }
                    composable(
                        route = TrustEntryViewerDestination.routeWithArgs,
                        arguments = TrustEntryViewerDestination.arguments
                    ) { backStackEntry ->
                        TrustEntryViewerScreen(
                            builtInTrustManager = builtInTrustManager,
                            userTrustManager = userTrustManager,
                            trustManagerId = backStackEntry.arguments?.getString(
                                TrustEntryViewerDestination.TRUST_MANAGER_ID
                            )!!,
                            entryIndex = backStackEntry.arguments?.getInt(
                                TrustEntryViewerDestination.ENTRY_INDEX
                            )!!,
                            justImported = backStackEntry.arguments?.getBoolean(
                                TrustEntryViewerDestination.JUST_IMPORTED
                            )!!,
                            onBackPressed = { navController.navigateUp() },
                            onEditPressed = { entryIndex ->
                                navController.navigate(
                                    route = TrustEntryEditorDestination.route + "/" + entryIndex
                                )
                            },
                            onShowVicalEntry = { trustManagerId, entryIndex, vicalCertNum ->
                                navController.navigate(
                                    route = VicalEntryViewerDestination.route +
                                            "/" + trustManagerId + "/" + entryIndex + "/" + vicalCertNum
                                )
                            },
                            onShowCertificate = { certificate ->
                                val certificateDataBase64 = Cbor.encode(certificate.toDataItem()).toBase64Url()
                                navController.navigate(
                                    route = CertificateViewerDestination.route + "/" + certificateDataBase64
                                )
                            },
                            onShowCertificateChain = { certificateChain ->
                                val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                                navController.navigate(
                                    route = CertificateViewerDestination.route + "/" + certificateDataBase64
                                )
                            },
                        )
                    }
                    composable(
                        route = TrustEntryEditorDestination.routeWithArgs,
                        arguments = TrustEntryEditorDestination.arguments
                    ) { backStackEntry ->
                        TrustEntryEditorScreen(
                            userTrustManager = userTrustManager,
                            entryIndex = backStackEntry.arguments?.getInt(
                                TrustEntryViewerDestination.ENTRY_INDEX
                            )!!,
                            onBackPressed = { navController.navigateUp() },
                        )
                    }
                    composable(
                        route = VicalEntryViewerDestination.routeWithArgs,
                        arguments = VicalEntryViewerDestination.arguments
                    ) { backStackEntry ->
                        VicalEntryViewerScreen(
                            builtInTrustManager = builtInTrustManager,
                            userTrustManager = userTrustManager,
                            trustManagerId = backStackEntry.arguments?.getString(
                                VicalEntryViewerDestination.TRUST_MANAGER_ID
                            )!!,
                            entryIndex = backStackEntry.arguments?.getInt(
                                VicalEntryViewerDestination.ENTRY_INDEX
                            )!!,
                            certificateIndex = backStackEntry.arguments?.getInt(
                                VicalEntryViewerDestination.CERTIFICATE_INDEX
                            )!!,
                            onBackPressed = { navController.navigateUp() },
                        )
                    }
                }
            }
        }
    }
}

