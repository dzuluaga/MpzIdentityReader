package org.multipaz.identityreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext

@Composable
actual fun rememberImagePicker(
    allowMultiple: Boolean,
    onResult: (fileData: List<ByteString>) -> Unit,
): ImagePicker {
    val imagePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
            onResult = { uri ->
                if (uri != null) {
                    val inputStream = applicationContext.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("File not found")
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    onResult(listOf(ByteString(bytes)))
                } else {
                    onResult(emptyList())
                }
            },
        )

    return remember {
        ImagePicker(
            allowMultiple = allowMultiple,
            onLaunch = {
                imagePicker.launch(
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build()
                )
            },
        )
    }
}

actual class ImagePicker actual constructor(
    val allowMultiple: Boolean,
    val onLaunch: () -> Unit
) {
    actual fun launch() {
        onLaunch()
    }
}
