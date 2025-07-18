package org.multipaz.identityreader.libbackend

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.DeviceAttestation
import org.multipaz.device.DeviceAttestationValidationData
import org.multipaz.device.fromCbor
import org.multipaz.device.toCbor
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A reference implementation of the reader backend.
 *
 * @param readerRootKey the private key for the reader root.
 * @param readerRootCertChain the certification for [readerRootKey].
 * @param readerCertDuration the amount of time the issued certificates will be valid for.
 * @param iosReleaseBuild Whether a release build is required on iOS. When `false`, both debug and release builds
 *   are accepted.
 * @param iosAppIdentifier iOS app identifier that consists of a team id followed by a dot and app bundle name. If
 *   `null`, any app identifier is accepted. It must not be `null` if [iosReleaseBuild] is `true`
 * @param androidGmsAttestation whether to require attestations made for local key on clients is using the Google root.
 * @param androidVerifiedBootGreen whether to require clients are in verified boot state green.
 * @param androidAppSignatureCertificateDigests the allowed list of applications that can use the
 *   service. Each element is the bytes of the SHA-256 of a signing certificate, see the
 *   [Signature](https://developer.android.com/reference/android/content/pm/Signature) class in
 *   the Android SDK for details. If empty, allow any app.
 * @param getStorageTable a method to create [StorageTable] from a [StorageTableSpec].
 * @param getCurrentTime a method to get the current time, used only for testing.
 * @param random the [Random] source to use.
 */
open class ReaderBackend(
    private val readerRootKey: EcPrivateKey,
    private val readerRootCertChain: X509CertChain,
    private val readerCertDuration: DateTimePeriod,
    private val iosReleaseBuild: Boolean,
    private val iosAppIdentifier: String?,
    private val androidGmsAttestation: Boolean,
    private val androidVerifiedBootGreen: Boolean,
    private val androidAppSignatureCertificateDigests: List<ByteString>,
    private val getStorageTable: suspend (spec: StorageTableSpec) -> StorageTable,
    private val getCurrentTime: () -> Instant = { Clock.System.now() },
    private val random: Random = Random.Default
) {

    suspend fun handleGetNonce(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val noncesTable = getStorageTable(nonceTableSpec)
        val nonce = random.nextBytes(16)
        val nonceBase64url = nonce.toBase64Url()
        noncesTable.insert(
            key = nonceBase64url,
            data = ByteString(),
            expiration = Clock.System.now() + NONCE_EXPIRATION_TIME
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                put("nonce", nonceBase64url)
            }
        )
    }

    suspend fun handleRegister(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val noncesTable = getStorageTable(nonceTableSpec)
        val clientsTable = getStorageTable(clientTableSpec)

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (noncesTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()
        val deviceAttestation = DeviceAttestation.fromCbor(
            request["deviceAttestation"]!!.jsonPrimitive.content.fromBase64Url()
        )

        // Check the attestation...
        deviceAttestation.validate(
            DeviceAttestationValidationData(
                attestationChallenge = ByteString(nonce),
                iosReleaseBuild = iosReleaseBuild,
                iosAppIdentifier = iosAppIdentifier,
                androidGmsAttestation = androidGmsAttestation,
                androidVerifiedBootGreen = androidVerifiedBootGreen,
                androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests
            )
        )

        val id = clientsTable.insert(
            key = null,
            data = ByteString(deviceAttestation.toCbor())
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                put("registrationId", id)
            }
        )
    }

    suspend fun handleCertifyKeys(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val noncesTable = getStorageTable(nonceTableSpec)
        val deviceAttestationsTable = getStorageTable(clientTableSpec)

        val id = request["registrationId"]!!.jsonPrimitive.content
        val deviceAttestationCbor = deviceAttestationsTable.get(key = id)
        if (deviceAttestationCbor == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val deviceAttestation = DeviceAttestation.fromCbor(deviceAttestationCbor.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (noncesTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        deviceAttestation.validateAssertion(deviceAssertion)

        val keysToCertify = request["keys"]!!.jsonArray
        val readerCertifications = mutableListOf<X509CertChain>()
        val now = Instant.fromEpochSeconds(getCurrentTime().epochSeconds)
        for (keyJwk in keysToCertify) {
            // Introduce a bit of jitter so it's not possible for someone to correlate two keys
            val jitterFrom = random.nextInt(12*3600).seconds
            val jitterUntil = random.nextInt(12*3600).seconds
            val validFrom = now - jitterFrom
            val validUntil = now.plus(readerCertDuration, TimeZone.currentSystemDefault()) + jitterUntil
            val key = EcPublicKey.fromJwk(keyJwk.jsonObject)
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootCert = readerRootCertChain.certificates[0],
                readerRootKey = readerRootKey,
                readerKey = key,
                subject = X500Name.fromName("CN=Multipaz Identity Verifier Single-Use Key"),
                serial = ASN1Integer.fromRandom(numBits = 128, random = random),
                validFrom = validFrom,
                validUntil = validUntil
            )
            val readerCertChain = X509CertChain(listOf(readerCert) + readerRootCertChain.certificates)
            readerCertifications.add(readerCertChain)
        }

        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                putJsonArray("readerCertifications") {
                    for (readerCertChain in readerCertifications) {
                        add(readerCertChain.toX5c())
                    }
                }
            }
        )
    }

    companion object {
        private val nonceTableSpec = StorageTableSpec(
            name = "ReaderBackendNonces",
            supportPartitions = false,
            supportExpiration = true
        )

        private val NONCE_EXPIRATION_TIME = 5.minutes

        private val clientTableSpec = StorageTableSpec(
            name = "ReaderBackendClients",
            supportPartitions = false,
            supportExpiration = false   // TODO: maybe consider using expiration
        )
    }

}