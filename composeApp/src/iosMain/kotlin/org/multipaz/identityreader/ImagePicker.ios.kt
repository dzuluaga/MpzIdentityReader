package org.multipaz.identityreader

import androidx.compose.runtime.Composable
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberImagePicker(
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): ImagePicker {
    TODO()
}

actual class ImagePicker actual constructor(
    allowMultiple: Boolean,
    onLaunch: () -> Unit
) {
    actual fun launch() {
        TODO()
    }
}
