package org.multipaz.identityreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemDateTimeString
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec

class SettingsModel private constructor(
    private val readOnly: Boolean
) {
    private lateinit var settingsTable: StorageTable

    companion object {
        private val tableSpec = StorageTableSpec(
            name = "Settings",
            supportPartitions = false,
            supportExpiration = false
        )

        /**
         * Asynchronous construction.
         *
         * @param storage the [Storage] backing the settings.
         * @param readOnly if `false`, won't monitor all the settings and write to storage when they change.
         */
        suspend fun create(
            storage: Storage,
            readOnly: Boolean = false
        ): SettingsModel {
            val instance = SettingsModel(readOnly)
            instance.settingsTable = storage.getTable(tableSpec)
            instance.init()
            return instance
        }
    }

    private data class BoundItem<T>(
        val variable: MutableStateFlow<T>,
        val defaultValue: T
    ) {
        fun resetValue() {
            variable.value = defaultValue
        }
    }

    private val boundItems = mutableListOf<BoundItem<*>>()

    private suspend inline fun<reified T> bind(
        variable: MutableStateFlow<T>,
        key: String,
        defaultValue: T
    ) {
        val value = settingsTable.get(key)?.let {
            val dataItem = Cbor.decode(it.toByteArray())
            when (T::class) {
                Instant::class -> { dataItem.asDateTimeString as T }
                Long::class -> { dataItem.asNumber as T }
                Boolean::class -> { dataItem.asBoolean as T }
                String::class -> { dataItem.asTstr as T }
                List::class -> { dataItem.asArray.map { item -> (item as Tstr).value } as T }
                EcCurve::class -> { EcCurve.entries.find { it.name == dataItem.asTstr } as T }
                ReaderAuthMethod::class -> { ReaderAuthMethod.entries.find { it.name == dataItem.asTstr } as T }
                EcPrivateKey::class -> {
                    if (dataItem == Simple.NULL) {
                        null
                    } else {
                        EcPrivateKey.fromDataItem(dataItem) as T
                    }
                }
                X509CertChain::class -> {
                    if (dataItem == Simple.NULL) {
                        null
                    } else {
                        X509CertChain.fromDataItem(dataItem) as T
                    }
                }
                else -> { throw IllegalStateException("Type not supported") }
            }
        } ?: defaultValue
        variable.value = value

        if (!readOnly) {
            CoroutineScope(Dispatchers.Default).launch {
                variable.asStateFlow().collect { newValue ->
                    val dataItem = when (T::class) {
                        Instant::class -> { (newValue as Instant).toDataItemDateTimeString() }
                        Long::class -> { (newValue as Long).toDataItem() }
                        Boolean::class -> { (newValue as Boolean).toDataItem() }
                        String::class -> { (newValue as String).toDataItem() }
                        List::class -> {
                            buildCborArray {
                                (newValue as List<*>).forEach { add(Tstr(it as String)) }
                            }
                        }
                        EcCurve::class -> { (newValue as EcCurve).name.toDataItem() }
                        ReaderAuthMethod::class -> { (newValue as ReaderAuthMethod).name.toDataItem() }
                        EcPrivateKey::class -> {
                            newValue?.let { (newValue as EcPrivateKey).toDataItem() } ?: Simple.NULL
                        }
                        X509CertChain::class -> {
                            newValue?.let { (newValue as X509CertChain).toDataItem() } ?: Simple.NULL
                        }
                        else -> { throw IllegalStateException("Type not supported") }
                    }
                    if (settingsTable.get(key) == null) {
                        settingsTable.insert(key, ByteString(Cbor.encode(dataItem)))
                    } else {
                        settingsTable.update(key, ByteString(Cbor.encode(dataItem)))
                    }
                }
            }
        }
        boundItems.add(BoundItem(variable, defaultValue))
    }

    fun resetSettings() {
        boundItems.forEach { it.resetValue() }
    }

    // TODO: use something like KSP to avoid having to repeat settings name three times..
    //

    private suspend fun init() {
        bind(logTransactions, "logTransactions", false)
        bind(selectedQueryName, "selectedQueryName", ReaderQuery.AGE_OVER_21.name)
        bind(builtInIssuersUpdatedAt, "builtInIssuersUpdatedAt", Instant.DISTANT_PAST)
        bind(builtInIssuersVersion, "builtInIssuersVersion", Long.MIN_VALUE)
        bind(readerAuthMethod, "readerAuthMethod", ReaderAuthMethod.STANDARD_READER_AUTH)
        bind(customReaderAuthKey, "customReaderAuthKey", null)
        bind(customReaderAuthCertChain, "customReaderAuthCertChain", null)
    }

    val logTransactions = MutableStateFlow<Boolean>(false)
    val selectedQueryName = MutableStateFlow<String>(ReaderQuery.AGE_OVER_21.name)
    val builtInIssuersUpdatedAt = MutableStateFlow<Instant>(Instant.DISTANT_PAST)
    val builtInIssuersVersion = MutableStateFlow<Long>(Long.MIN_VALUE)
    val readerAuthMethod = MutableStateFlow<ReaderAuthMethod>(ReaderAuthMethod.STANDARD_READER_AUTH)
    val customReaderAuthKey = MutableStateFlow<EcPrivateKey?>(null)
    val customReaderAuthCertChain = MutableStateFlow<X509CertChain?>(null)
}

enum class ReaderAuthMethod {
    NO_READER_AUTH,
    STANDARD_READER_AUTH,
    CUSTOM_KEY
}