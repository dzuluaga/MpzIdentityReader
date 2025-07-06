package org.multipaz.identityreader

import androidx.compose.runtime.Composable
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberFilePicker(
    types: List<String>,
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): FilePicker {
    TODO()
}

actual class FilePicker actual constructor(
    types: List<String>,
    allowMultiple: Boolean,
    onLaunch: () -> Unit
) {
    actual fun launch() {
        TODO()
    }
}
