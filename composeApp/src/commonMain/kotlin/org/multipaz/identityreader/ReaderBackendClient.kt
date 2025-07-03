package org.multipaz.identityreader

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.X509CertChain
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceCheck
import org.multipaz.device.toCbor
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyInfo
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url

private val backendRegistrationSpec = StorageTableSpec(
    name = "ReaderBackendClientRegistrationData",
    supportPartitions = false,
    supportExpiration = false
)

@CborSerializable
internal data class RegistrationData(
    val deviceAttestationId: String,
    val registrationId: String,
) {
    companion object
}

private val certifiedKeysSpec = StorageTableSpec(
    name = "ReaderBackendClientCertifiedKeys",
    supportPartitions = false,
    supportExpiration = false
)

@CborSerializable
internal data class CertifiedKey(
    val alias: String,
    val certification: X509CertChain,
    val validFrom: Instant,
    val validUntil: Instant,
    val refreshAt: Instant,
) {
    companion object
}

/**
 * A class for interacting with a reader backend.
 *
 * The reader backend can certify keys used for reader authentication. The client generate
 * keys in [secureArea] on the device and send them to the backend for certification.
 * This includes proving via [DeviceCheck] that the app, operating system, and device is in
 * good standing. After the backend successfully confirms this it will certify each key by
 * returning a [X509CertChain] chaining up to a trusted reader root.
 *
 * Because the server knows which app is requesting (via [org.multipaz.device.DeviceAttestation])
 * it may use different roots for different applications.
 *
 * @param readerBackendUrl the URL of the reader backend.
 * @param storage a [Storage] to use for storing registration data.
 * @param httpClientEngineFactory a [HttpClientEngineFactory] for communicating with the reader backend.
 * @param secureArea the [SecureArea] to use for creating the keys.
 * @param numKeys how many keys to manage.
 */
open class ReaderBackendClient(
    val readerBackendUrl: String,
    val storage: Storage,
    val httpClientEngineFactory: HttpClientEngineFactory<*>,
    val secureArea: SecureArea,
    val numKeys: Int,
) {
    private val httpClient = HttpClient(httpClientEngineFactory) {
        install(HttpTimeout)
    }

    // Maps from rowId to CertifiedKey
    private var certifiedKeys: MutableMap<String, CertifiedKey>? = null

    open suspend fun communicateWithServer(methodName: String, request: JsonObject): Pair<HttpStatusCode, JsonObject> {
        val response = httpClient.post(readerBackendUrl + "/" + methodName) {
            setBody(Json.encodeToString(request))
        }
        return Pair(
            response.status,
            Json.decodeFromString<JsonObject>(response.body<ByteArray>().decodeToString())
        )
    }

    private suspend fun ensureCertifiedKeys() {
        if (certifiedKeys != null) {
            return
        }
        certifiedKeys = mutableMapOf()
        val certifiedKeysTable = storage.getTable(certifiedKeysSpec)
        for ((key, encodedData) in certifiedKeysTable.enumerateWithData()) {
            certifiedKeys!!.put(key, CertifiedKey.fromCbor(encodedData.toByteArray()))
        }
    }

    // Ensures we're registered with the reader backend.
    //
    private suspend fun ensureRegistered(): RegistrationData {
        val backendRegistrationTable = storage.getTable(backendRegistrationSpec)
        val dataEncoded = backendRegistrationTable.get("default")
        if (dataEncoded != null) {
            return RegistrationData.fromCbor(dataEncoded.toByteArray())
        }
        val (nonceStatus, nonceRespObj) = communicateWithServer("getNonce", buildJsonObject {})
        check(nonceStatus == HttpStatusCode.OK)
        val nonceBase64Url = nonceRespObj.get("nonce")!!.jsonPrimitive.content
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAttestationResult = DeviceCheck.generateAttestation(
            secureArea = Platform.getSecureArea(),
            challenge = ByteString(nonce)
        )

        val (registerStatus, registerRespObj) = communicateWithServer("register", buildJsonObject {
            put("nonce", nonceBase64Url)
            put("deviceAttestation", deviceAttestationResult.deviceAttestation.toCbor().toBase64Url())
        })
        check(registerStatus == HttpStatusCode.OK)
        val registrationId = registerRespObj.get("registrationId")!!.jsonPrimitive.content

        val data = RegistrationData(
            deviceAttestationId = deviceAttestationResult.deviceAttestationId,
            registrationId = registrationId
        )
        backendRegistrationTable.insert(
            key = "default",
            data = ByteString(data.toCbor())
        )
        return data
    }

    // Ensures we have at least numKeys/2 fresh keys. Also removes expired keys.
    //
    private suspend fun ensureReplenished(atTime: Instant = Clock.System.now()) {
        if (ensureReplenishedWithRetry(atTime)) {
            // Our registration was 404, so retry
            ensureReplenishedWithRetry(atTime)
        }
    }

    // Like ensureReplenished() but returns true if we need to retry
    private suspend fun ensureReplenishedWithRetry(atTime: Instant = Clock.System.now()): Boolean {
        ensureCertifiedKeys()
        var numGoodKeys = 0
        val toDelete = mutableListOf<Pair<String, CertifiedKey>>()
        val certifiedKeysTable = storage.getTable(certifiedKeysSpec)
        for ((id, certifiedKey) in certifiedKeys!!.entries) {
            if (atTime > certifiedKey.refreshAt) {
                toDelete.add(Pair(id, certifiedKey))
            } else if (atTime > certifiedKey.validFrom && atTime < certifiedKey.validUntil) {
                numGoodKeys += 1
            }
        }
        // Note: We only delete these keys if either we have enough good keys OR if we successfully
        // replenish. This is to avoid ending up in a situation where we don't have keys left!

        // Only replenish if we are running below 50%...
        if (numGoodKeys > numKeys/2) {
            toDelete.forEach {
                secureArea.deleteKey(it.second.alias)
                certifiedKeysTable.delete(it.first)
                certifiedKeys!!.remove(it.first)
            }
            return false
        }
        val numKeysNeeded = numKeys - numGoodKeys

        val registrationData = ensureRegistered()
        val (nonceStatus, nonceRespObj) = communicateWithServer("getNonce", buildJsonObject {})
        check(nonceStatus == HttpStatusCode.OK)
        val nonceBase64Url = nonceRespObj.get("nonce")!!.jsonPrimitive.content
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertion = DeviceCheck.generateAssertion(
            secureArea = Platform.getSecureArea(),
            deviceAttestationId = registrationData.deviceAttestationId,
            assertion = AssertionNonce(ByteString(nonce))
        )

        val keysToCertify = mutableListOf<KeyInfo>()
        repeat(numKeysNeeded) {
            keysToCertify.add(secureArea.createKey(null, CreateKeySettings()))
        }

        val (certifyStatus, certifyRespObj) = communicateWithServer("certifyKeys", buildJsonObject {
            put("registrationId", registrationData.registrationId)
            put("nonce", nonceBase64Url)
            put("deviceAssertion", deviceAssertion.toCbor().toBase64Url())
            putJsonArray("keys") {
                for (keyInfo in keysToCertify) {
                    add(keyInfo.publicKey.toJwk())
                }
            }
        })
        if (certifyStatus == HttpStatusCode.NotFound) {
            // This is for handling the case here the server forgot or deleted our registrationId.
            Logger.w(TAG, "Server returned 404 on certifyKeys. Going to re-register.")
            val backendRegistrationTable = storage.getTable(backendRegistrationSpec)
            backendRegistrationTable.delete("default")
            return true
        }
        check(certifyStatus == HttpStatusCode.OK)
        val readerCertifications = certifyRespObj["readerCertifications"]!!.jsonArray
        check(readerCertifications.size == keysToCertify.size)
        var n = 0
        for (readerCertification in readerCertifications) {
            val readerCertification = X509CertChain.fromX5c(readerCertification)
            // Refresh a key once it's past two thirds of its life. We do b/c otherwise folks might have
            // certificates w/ very little life left (say, 1 day) and then they turn on Airplane Mode (or
            // otherwise lose Internet connectivity) and then verification won't work.
            //
            val validFrom = readerCertification.certificates[0].validityNotBefore
            val validUntil = readerCertification.certificates[0].validityNotAfter
            val validFor = validUntil - validFrom
            val refreshAt = validFrom + validFor*2/3
            val keyInfo = keysToCertify[n++]
            val certifiedKey = CertifiedKey(
                alias = keyInfo.alias,
                certification = readerCertification,
                validFrom = validFrom,
                validUntil = validUntil,
                refreshAt = refreshAt,
            )
            val id = certifiedKeysTable.insert(
                key = null,
                data = ByteString(certifiedKey.toCbor())
            )
            certifiedKeys!!.put(id, certifiedKey)
        }

        toDelete.forEach {
            secureArea.deleteKey(it.second.alias)
            certifiedKeysTable.delete(it.first)
            certifiedKeys!!.remove(it.first)
        }
        return false
    }

    /**
     * Gets a reader authentication key.
     *
     * This may involve network I/O to the reader backend for certification of freshly
     * created keys and other housekeeping.
     *
     * When the key has been used, call [markKeyAsUsed] to ensure it won't be used again.
     *
     * It's allowable to call this function to just prime the pool (ie. the result isn't used) so
     * network I/O is reduced for future calls. For example, it might be advantageous to do this
     * at application startup.
     *
     * @param atTime the current time, to take into consideration for purposing of evicting expired keys.
     * @return a [KeyInfo] for a key in [SecureArea] and the certification from the reader backend.
     */
    suspend fun getKey(
        atTime: Instant = Clock.System.now()
    ): Pair<KeyInfo, X509CertChain> {
        try {
            ensureReplenished(atTime)
        } catch (e: Throwable) {
            Logger.w(TAG, "Ignoring error replenishing keys: $e")
        }
        // Return the oldest certificate
        val sortedCertifiedKeys = certifiedKeys!!.values
            .filter { it.validFrom < atTime && atTime < it.validUntil }
            .sortedBy { it.validFrom }
        if (sortedCertifiedKeys.isEmpty()) {
            throw IllegalStateException("No currently valid keys available")
        }
        return Pair(
            secureArea.getKeyInfo(sortedCertifiedKeys[0].alias),
            sortedCertifiedKeys[0].certification
        )
    }

    /**
     * Marks a key retrieved with [getKey] as used.
     *
     * @param keyInfo the [KeyInfo] returned from the [getKey] call.
     * @param atTime the current time, to take into consideration for purposing of evicting expired keys.
     */
    suspend fun markKeyAsUsed(
        keyInfo: KeyInfo,
        atTime: Instant = Clock.System.now()
    ) {
        ensureCertifiedKeys()
        val entry = certifiedKeys!!.entries.find { (key, certifiedKey) ->
            certifiedKey.alias == keyInfo.alias
        } ?: throw IllegalArgumentException("No such certified key to mark as used")

        // If this was the last key, replenish immediately. If that fails (e.g. no Internet connectivity)
        // leave the key around but mark that it's already been used
        if (certifiedKeys!!.size == 1) {
            try {
                ensureReplenished(atTime)
            } catch (e: Throwable) {
                Logger.w(TAG, "Ignoring error replenishing keys so keeping around last key: $e")
                return
            }
        }

        val certifiedKeysTable = storage.getTable(certifiedKeysSpec)
        secureArea.deleteKey(entry.value.alias)
        certifiedKeysTable.delete(key = entry.key)
        certifiedKeys!!.remove(entry.key)
    }

    companion object {
        private const val TAG = "ReaderKeyManager"
    }
}