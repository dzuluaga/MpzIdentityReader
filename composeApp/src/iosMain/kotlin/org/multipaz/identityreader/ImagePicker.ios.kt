package org.multipaz.identityreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.io.bytestring.ByteString

@Composable
actual fun rememberImagePicker(
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): ImagePicker {
    return remember {
        ImagePicker(
            allowMultiple = allowMultiple,
            onLaunch = {
                TODO()
            },
        )
    }
}

actual class ImagePicker actual constructor(
    allowMultiple: Boolean,
    onLaunch: () -> Unit
) {
    actual fun launch() {
        TODO()
    }
}
