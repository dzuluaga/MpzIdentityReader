package org.multipaz.identityreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberFilePicker(
    types: List<String>,
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): FilePicker {
    return remember {
        FilePicker(
            types = types,
            allowMultiple = allowMultiple,
            onLaunch = {
                TODO()
            },
        )
    }
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
