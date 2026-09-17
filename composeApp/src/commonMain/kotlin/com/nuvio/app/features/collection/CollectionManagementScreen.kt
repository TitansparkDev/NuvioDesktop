package com.nuvio.app.features.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDialogSurface
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.NuvioTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionImportDialog(
    importText: String,
    importError: String?,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        NuvioDialogSurface(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(Res.string.collections_import_header),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.collections_import_paste_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NuvioTextField(
                    value = importText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.collections_import_json_placeholder),
                    isError = importError != null,
                    supportingText = importError,
                    singleLine = false,
                    minLines = 6,
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    onImeAction = { onConfirm() },
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    androidx.compose.material3.Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    androidx.compose.material3.Button(
                        onClick = onConfirm,
                        enabled = importText.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(stringResource(Res.string.action_import))
                    }
                }
            }
        }
    }
}
