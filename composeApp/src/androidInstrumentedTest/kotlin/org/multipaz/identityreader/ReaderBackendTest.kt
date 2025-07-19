package org.multipaz.identityreader

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import org.junit.Before
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.identityreader.libbackend.ReaderBackend
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ReaderBackendTest {
    private class LoopbackReaderBackendClient(
        clientStorage: Storage,
        serverStorage: Storage,
        secureArea: SecureArea,
        numKeys: Int,
        androidAppSignatureCertificateDigests: List<ByteString> = emptyList(),
        issueTrustListVersion: Long = Long.MIN_VALUE,
        issuerTrustList: List<TrustEntry> = emptyList()
    ) : ReaderBackendClient(
        readerBackendUrl = "not-used",
        storage = clientStorage,
        httpClientEngineFactory = CIO,
        secureArea = secureArea,
        numKeys = numKeys
    ) {
        private val readerRootKeyForUntrustedDevices = Crypto.createEcPrivateKey(EcCurve.P384)
        val readerRootCertChainForUntrustedDevices = X509CertChain(
            listOf(
                MdocUtil.generateReaderRootCertificate(
                    readerRootKey = readerRootKeyForUntrustedDevices,
                    subject = X500Name.fromName("CN=TEST Reader Root (Untrusted Devices)"),
                    serial = ASN1Integer.fromRandom(numBits = 128),
                    validFrom = Instant.parse("2024-07-01T06:00:00Z"),
                    validUntil = Instant.parse("2030-07-01T06:00:00Z"),
                    crlUrl = "https://www.example.com/crl"
                )
            )
        )

        private val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val readerRootCertChain = X509CertChain(
            listOf(
                MdocUtil.generateReaderRootCertificate(
                    readerRootKey = readerRootKey,
                    subject = X500Name.fromName("CN=TEST Reader Root"),
                    serial = ASN1Integer.fromRandom(numBits = 128),
                    validFrom = Instant.parse("2024-07-01T06:00:00Z"),
                    validUntil = Instant.parse("2030-07-01T06:00:00Z"),
                    crlUrl = "https://www.example.com/crl"
                )
            )
        )

        private var serverStorage_ = serverStorage

        fun setServerStorage(serverStorage: EphemeralStorage) {
            serverStorage_ = serverStorage
        }

        private val backend = ReaderBackend(
            readerRootKeyForUntrustedDevices = readerRootKeyForUntrustedDevices,
            readerRootCertChainForUntrustedDevices = readerRootCertChainForUntrustedDevices,
            readerRootKey = readerRootKey,
            readerRootCertChain = readerRootCertChain,
            readerCertDuration = DateTimePeriod(days = 30),
            iosReleaseBuild = false,
            iosAppIdentifier = null,
            androidGmsAttestation = false,
            androidVerifiedBootGreen = false,
            androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests,
            issuerTrustListVersion = issueTrustListVersion,
            issuerTrustList = issuerTrustList,
            getStorageTable = { spec -> serverStorage_.getTable(spec) },
            getCurrentTime = { currentTime }
        )

        var numRpc = 0
        var currentTime = Instant.parse("2024-08-01T00:00:00Z")
        var disableServer = false

        override suspend fun communicateWithServer(
            methodName: String,
            request: JsonObject
        ): Pair<HttpStatusCode, JsonObject> {
            if (disableServer) {
                throw IllegalStateException("Server has been disabled")
            }
            numRpc += 1
            return when (methodName) {
                "getNonce" -> backend.handleGetNonce(request)
                "register" -> backend.handleRegister(request)
                "certifyKeys" -> backend.handleCertifyKeys(request)
                "getIssuerList" -> backend.handleGetIssuerList(request)
                else -> throw IllegalArgumentException("Unexpected method $methodName")
            }
        }
    }

    @Before
    fun setup() {
        initializeApplication(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun testHappyPath() = runTest {
        val ownAppSignatureCertificateDigests = mutableListOf<ByteString>()
        val pkg = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        pkg.signingInfo!!.apkContentsSigners.forEach { signatureInfo ->
            ownAppSignatureCertificateDigests.add(
                ByteString(Crypto.digest(Algorithm.SHA256, signatureInfo.toByteArray()))
            )
        }
        testHappyPathWithBackend(
            expectedUntrustedDevice = false,
            androidAppSignatureCertificateDigests = ownAppSignatureCertificateDigests
        )
    }

    @Test
    fun testHappyPathUntrustedDevice() = runTest {
        testHappyPathWithBackend(
            expectedUntrustedDevice = true,
            androidAppSignatureCertificateDigests = listOf(ByteString(1, 2, 3))
        )
    }

    fun testHappyPathWithBackend(
        expectedUntrustedDevice: Boolean,
        androidAppSignatureCertificateDigests: List<ByteString>
    ) = runTest {
        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10,
            androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests
        )
        val (_, certChain) = client.getKey(client.currentTime)
        assertTrue(certChain.validate())
        assertEquals(2, certChain.certificates.size)

        if (expectedUntrustedDevice) {
            assertEquals(client.readerRootCertChainForUntrustedDevices.certificates[0], certChain.certificates[1])
        } else {
            assertEquals(client.readerRootCertChain.certificates[0], certChain.certificates[1])
        }

        // Check validity dates, taking into account the jitter the server injects.
        val readerCert = certChain.certificates[0]
        assertTrue(readerCert.validityNotBefore <= client.currentTime)
        assertTrue(readerCert.validityNotBefore >= client.currentTime - 12.hours)
        assertTrue(readerCert.validityNotAfter >= client.currentTime + 30.days)
        assertTrue(readerCert.validityNotAfter <= client.currentTime + 30.days + 12.hours)

        // First call should only be four RPCs (getNonce, register, getNonce, certifyKeys)
        assertEquals(4, client.numRpc)

        // Use up just enough keys to cause an RPC for replenishing on the call following the next call
        repeat(4) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
        }
        assertEquals(4, client.numRpc)

        // Create another client using the same storage - simulating an app restart.. next getKey()
        // call should not cause RPC
        val client2 = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10,
            androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests
        )
        val (keyInfo, _) = client2.getKey(client2.currentTime)
        client2.markKeyAsUsed(keyInfo)
        assertEquals(0, client2.numRpc)
        // Next getKey() call will cause RPCs.. check we only do two RPCs for replenishing
        val (_, _) = client2.getKey(client2.currentTime)
        assertEquals(2, client2.numRpc)
    }

    @Test
    fun testReplenish() = runTest {
        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10
        )

        // First call should cause the usual 4 RPCs
        val (_, _) = client.getKey(client.currentTime)
        assertEquals(4, client.numRpc)

        // Unless we use the key, it won't get replenished, so check getKey() can be called 100 times
        // without causing any additional RPCs.
        repeat(100) { client.getKey(client.currentTime) }
        assertEquals(4, client.numRpc)

        // Now use up 5 keys, and make sure we saw different keys everytime. Because we only
        // replenish when we fall below 50% this means no additional RPC is done. Check this.
        val seenAliases = mutableSetOf<String>()
        repeat(5) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(5, seenAliases.size)
        assertEquals(4, client.numRpc)

        // Next time we'll use a key it'll cause RPC to replenish, two calls. Check this.
        val (keyInfo, _) = client.getKey(client.currentTime)
        client.markKeyAsUsed(keyInfo)
        assertEquals(6, client.numRpc)

        // Check replenishing works ad infinitum (example: 100 uses) and we only do RPCs once half empty.
        seenAliases.clear()
        repeat(100) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(100, seenAliases.size)
        assertEquals(46, client.numRpc)
    }

    @Test
    fun testExpiration() = runTest {
        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10
        )

        // First call should cause the usual 4 RPCs
        val (_, _) = client.getKey(client.currentTime)
        assertEquals(4, client.numRpc)

        // Advance the time to 15 days past, should not cause RPC.
        client.currentTime += 15.days
        val (_, _) = client.getKey(client.currentTime)
        assertEquals(4, client.numRpc)

        // Another another 6 days to bring us to 21 days. This will cause 2 RPCs since all keys will be
        // replaced after two thirds of 30 days which is 20 days.
        client.currentTime += 6.days
        val (_, certChain) = client.getKey(client.currentTime)
        assertEquals(6, client.numRpc)
        assertTrue(certChain.validate())
        assertEquals(2, certChain.certificates.size)
        assertEquals(client.readerRootCertChain.certificates[0], certChain.certificates[1])

        // Check validity dates, taking into account the jitter the server injects.
        val readerCert = certChain.certificates[0]
        assertTrue(readerCert.validityNotBefore <= client.currentTime)
        assertTrue(readerCert.validityNotBefore >= client.currentTime - 12.hours)
        assertTrue(readerCert.validityNotAfter >= client.currentTime + 30.days)
        assertTrue(readerCert.validityNotAfter <= client.currentTime + 30.days + 12.hours)
    }

    @Test
    fun testNoInternetConnectivity() = runTest {
        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10
        )

        // First call should cause the usual 4 RPCs
        val (_, _) = client.getKey(client.currentTime)
        assertEquals(4, client.numRpc)

        // Now simulate not having Internet connectivity and use up all keys. This should work.
        client.disableServer = true
        val seenAliases = mutableSetOf<String>()
        repeat(10) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(10, seenAliases.size)
        assertEquals(4, client.numRpc)

        // Because we want verifications to keep working even if there is no connectivity
        // to the reader backend, we leave a single key around for reuse.
        //
        // This means that if we get another key it'll be the one we just used. Consequently
        // `seenAliases` set will not grow. Check this.
        repeat(10) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(10, seenAliases.size)

        // If we advance the clock so even this single key isn't valid anymore, getKey()
        // will stop working, though.
        client.currentTime += 31.days
        try {
            client.getKey(client.currentTime)
            fail("Expected getKey() to fail")
        } catch (_: IllegalStateException) {
            // expected path
        }

        // If we turn Internet connectivity back on, we'll get fresh never-used keys.
        client.disableServer = false
        repeat(10) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
            seenAliases.add(keyInfo.alias)
        }
        assertEquals(20, seenAliases.size)
    }

    @Test
    fun testServerLostRegistration() = runTest {
        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10
        )
        val (_, certChain) = client.getKey(client.currentTime)
        assertTrue(certChain.validate())
        assertEquals(2, certChain.certificates.size)
        assertEquals(client.readerRootCertChain.certificates[0], certChain.certificates[1])

        // First call should only be four RPCs (getNonce, register, getNonce, certifyKeys)
        assertEquals(4, client.numRpc)

        // Use up just enough keys to cause an RPC for replenishing on the next call
        repeat(5) {
            val (keyInfo, _) = client.getKey(client.currentTime)
            client.markKeyAsUsed(keyInfo)
        }
        assertEquals(4, client.numRpc)

        // Simulate the server losing the registration.. our client should recover gracefully
        // and just re-register. This causes another six RPCs:
        // - two for the failing replenishing (getNonce -> 200, certifyKeys -> 404)
        // - four for registering again
        client.setServerStorage(EphemeralStorage())
        val (_, _) = client.getKey(client.currentTime)
        assertEquals(10, client.numRpc)
    }

    @Test
    fun testIssuerTrustList() = runTest {
        val issuerTrustList1 = listOf(
            TrustEntryX509Cert(
                metadata = TrustMetadata(displayName = "foo", displayIcon = ByteString(1, 2, 3)),
                certificate = X509Cert(byteArrayOf(10, 11, 12))
            ),
            TrustEntryVical(
                metadata = TrustMetadata(displayName = "bar", displayIcon = ByteString(4, 5, 6)),
                encodedSignedVical = ByteString(20, 21, 22)
            ),
        )

        val clientStorage = EphemeralStorage()
        val serverStorage = EphemeralStorage()
        val client = LoopbackReaderBackendClient(
            clientStorage = clientStorage,
            serverStorage = serverStorage,
            secureArea = AndroidKeystoreSecureArea.create(clientStorage),
            numKeys = 10,
            issueTrustListVersion = 42L,
            issuerTrustList = issuerTrustList1
        )

        val issuerListsFromServer = client.getTrustedIssuers(currentVersion = null)
        assertNotNull(issuerListsFromServer)
        assertEquals(42L, issuerListsFromServer.first)
        assertEquals(issuerTrustList1, issuerListsFromServer.second)

        assertNull(client.getTrustedIssuers(currentVersion = 42L))
        assertEquals(issuerListsFromServer, client.getTrustedIssuers(currentVersion = 41L))
        assertEquals(issuerListsFromServer, client.getTrustedIssuers(currentVersion = 43L))
        assertEquals(issuerListsFromServer, client.getTrustedIssuers(currentVersion = null))

        val (_, certChain) = client.getKey(client.currentTime)
        assertTrue(certChain.validate())
        assertEquals(2, certChain.certificates.size)
        assertEquals(client.readerRootCertChain.certificates[0], certChain.certificates[1])

    }
}