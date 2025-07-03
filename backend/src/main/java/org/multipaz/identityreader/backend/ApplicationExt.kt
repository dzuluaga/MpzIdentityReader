package org.multipaz.identityreader.backend


import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.identityreader.libbackend.ReaderBackend
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.ServerConfiguration
import org.multipaz.server.ServerEnvironment
import org.multipaz.server.ServerIdentity
import org.multipaz.server.getServerIdentity
import org.multipaz.util.Logger

private const val TAG = "ApplicationExt"

private typealias RequestWrapper =
        suspend PipelineContext<*,ApplicationCall>.(
            suspend PipelineContext<*,ApplicationCall>.() -> Unit) -> Unit

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(configuration: ServerConfiguration) {
    // TODO: when https://youtrack.jetbrains.com/issue/KTOR-8088 is resolved, there
    //  may be a better way to inject our custom wrapper for all request handlers
    //  (instead of doing it for every request like we do today).
    val env = ServerEnvironment.create(configuration)
    val server = createServer(env)
    val runRequest: RequestWrapper = { body ->
        val self = this
        withContext(env.await()) {
            try {
                body.invoke(self)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    text = err::class.simpleName + ": " + err.message,
                    contentType = ContentType.Text.Plain
                )
            }
        }
    }
    routing {
        get("/") { runRequest { fetchResource(call, "index.html") } }
        get("/{path...}") {
            runRequest { fetchResource(call, call.parameters["path"]!!) }
        }
        post("/{command}") {
            runRequest {
                val command = call.parameters["command"]!!
                val requestData = call.receive<ByteArray>()
                val requestObj = Json.decodeFromString<JsonObject>(requestData.decodeToString())
                val (responseStatusCode, responseObj) = when (command) {
                    "getNonce" -> server.await().handleGetNonce(requestObj)
                    "register" -> server.await().handleRegister(requestObj)
                    "certifyKeys" -> server.await().handleCertifyKeys(requestObj)
                    else -> throw InvalidRequestException("Unknown command: $command")
                }
                call.respondText(
                    status = responseStatusCode,
                    contentType = ContentType.Application.Json,
                    text = Json.encodeToString(responseObj)
                )
            }
        }
    }
}

private fun createServer(
    backendEnvironmentDeferred: Deferred<BackendEnvironment>,
): Deferred<ReaderBackend> = CoroutineScope(Dispatchers.Default).async {
    val backendEnvironment = backendEnvironmentDeferred.await()
    withContext(coroutineContext + backendEnvironment) {
        val settings = Settings(BackendEnvironment.getInterface(Configuration::class)!!)

        val readerRoot = getReaderRootIdentity()

        ReaderBackend(
            readerRootKey = readerRoot.privateKey,
            readerRootCertChain = readerRoot.certificateChain,
            readerCertDuration = DateTimePeriod(days = settings.readerCertValidityDays),
            iosReleaseBuild = settings.iosReleaseBuild,
            iosAppIdentifier = settings.iosAppIdentifier,
            androidGmsAttestation = settings.androidRequireGmsAttestation,
            androidVerifiedBootGreen = settings.androidRequireVerifiedBootGreen,
            androidAppSignatureCertificateDigests = settings.androidRequireAppSignatureCertificateDigests,
            getStorageTable = { spec -> BackendEnvironment.getTable(spec) },
        )
    }
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
