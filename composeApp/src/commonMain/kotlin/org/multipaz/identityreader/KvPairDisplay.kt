package org.multipaz.identityreader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun KvPairDisplay(
    key: String,
    valueText: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(8.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        SelectionContainer {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


@Composable
fun KvPairDisplay(
    key: String,
    valueText: String,
    modifier: Modifier = Modifier,
) = KvPairDisplay(key, AnnotatedString(valueText), modifier)
