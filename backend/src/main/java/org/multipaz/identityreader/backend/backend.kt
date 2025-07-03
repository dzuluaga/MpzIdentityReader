package org.multipaz.identityreader.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
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
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.ServerIdentity
import org.multipaz.server.getServerIdentity
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

private val nonceTableSpec = StorageTableSpec(
    name = "Nonces",
    supportPartitions = false,
    supportExpiration = true
)

private val NONCE_EXPIRATION_TIME = 5.minutes

private val deviceAttestationsTableSpec = StorageTableSpec(
    name = "Clients",
    supportPartitions = false,
    supportExpiration = false   // TODO: maybe consider using expiration
)

private const val TAG = "backend"

suspend fun backendPost(call: ApplicationCall, command: String) {
    val requestData = call.receive<ByteArray>()
    when (command) {
        "getNonce" -> handleGetNonce(call, requestData)
        "register" -> handleRegister(call, requestData)
        "certifyKeys" -> handleCertifyKeys(call, requestData)
        else -> throw InvalidRequestException("Unknown command: $command")
    }
}

suspend fun backendGet(call: ApplicationCall, command: String) {
    when (command) {
        "bah" -> handleGetBah(call)
        else -> throw InvalidRequestException("Unknown command: $command")
    }
}

private suspend fun handleGetNonce(
    call: ApplicationCall,
    requestData: ByteArray
) {
    val noncesTable = BackendEnvironment.getTable(nonceTableSpec)
    val nonce = Random.Default.nextBytes(16)
    val nonceBase64url = nonce.toBase64Url()
    noncesTable.insert(
        key = nonceBase64url,
        data = ByteString(),
        expiration = Clock.System.now() + NONCE_EXPIRATION_TIME
    )
    call.respondText(
        status = HttpStatusCode.OK,
        contentType = ContentType.Application.Json,
        text = Json.encodeToString(buildJsonObject {
            put("nonce", nonceBase64url)
        })
    )
    println("returned nonce ${nonce.toHex()}")
}

private suspend fun handleRegister(
    call: ApplicationCall,
    requestData: ByteArray
) {
    val settings = Settings(BackendEnvironment.getInterface(Configuration::class)!!)

    val noncesTable = BackendEnvironment.getTable(nonceTableSpec)
    val clientsTable = BackendEnvironment.getTable(deviceAttestationsTableSpec)
    val request = Json.decodeFromString<JsonObject>(requestData.decodeToString())

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
            iosReleaseBuild = settings.iosReleaseBuild,
            iosAppIdentifier = settings.iosAppIdentifier,
            androidGmsAttestation = settings.androidRequireGmsAttestation,
            androidVerifiedBootGreen = settings.androidRequireVerifiedBootGreen,
            androidAppSignatureCertificateDigests = settings.androidRequireAppSignatureCertificateDigests
        )
    )

    val id = clientsTable.insert(
        key = null,
        data = ByteString(deviceAttestation.toCbor())
    )
    call.respondText(
        status = HttpStatusCode.OK,
        contentType = ContentType.Application.Json,
        text = Json.encodeToString(buildJsonObject {
            put("registrationId", id)
        })
    )
    println("generated id $id")
}

private suspend fun handleCertifyKeys(
    call: ApplicationCall,
    requestData: ByteArray
) {
    val noncesTable = BackendEnvironment.getTable(nonceTableSpec)
    val deviceAttestationsTable = BackendEnvironment.getTable(deviceAttestationsTableSpec)
    val request = Json.decodeFromString<JsonObject>(requestData.decodeToString())

    val id = request["registrationId"]!!.jsonPrimitive.content
    val deviceAttestationCbor = deviceAttestationsTable.get(key = id)
    if (deviceAttestationCbor == null) {
        throw IllegalArgumentException("Unknown client")
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

    val readerRoot = getReaderRootIdentity()

    val keysToCertify = request["keys"]!!.jsonArray
    val readerCertifications = mutableListOf<X509CertChain>()
    val now = Clock.System.now()
    for (keyJwk in keysToCertify) {
        val key = EcPublicKey.fromJwk(keyJwk.jsonObject)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootCert = readerRoot.certificateChain.certificates[0],
            readerRootKey = readerRoot.privateKey,
            readerKey = key,
            subject = X500Name.fromName("CN=Multipaz Identity Verifier Single-Use Key"),
            serial = ASN1Integer.fromRandom(numBits = 128),
            // TODO: introduce some jitter so RPs cannot correlate these certifications
            validFrom = now,
            validUntil = now.plus(DateTimePeriod(days = 30), TimeZone.currentSystemDefault())
        )
        val readerCertChain = X509CertChain(listOf(readerCert) + readerRoot.certificateChain.certificates)
        readerCertifications.add(readerCertChain)
    }

    call.respondText(
        status = HttpStatusCode.OK,
        contentType = ContentType.Application.Json,
        text = Json.encodeToString(buildJsonObject {
            putJsonArray("readerCertifications") {
                for (readerCertChain in readerCertifications) {
                    add(readerCertChain.toX5c())
                }
            }
        })
    )
}

private suspend fun handleGetBah(
    call: ApplicationCall,
) {
    call.respondText(
        status = HttpStatusCode.OK,
        contentType = ContentType.Application.Json,
        text = "Bah"
    )
}

private suspend fun getReaderRootIdentity(): ServerIdentity =
    BackendEnvironment.getServerIdentity("reader_root_identity") {
        val subjectAndIssuer = X500Name.fromName("CN=Multipaz Identity Verifier Reader CA")

        val validFrom = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validUntil = validFrom.plus(DateTimePeriod(years = 5), TimeZone.currentSystemDefault())
        val serial = ASN1Integer.fromRandom(128)

        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val readerRootCertificate =
            MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRootKey,
                subject = subjectAndIssuer,
                serial = serial,
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
            )
        ServerIdentity(readerRootKey, X509CertChain(listOf(readerRootCertificate)))
    }
