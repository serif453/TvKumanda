package com.serif.tvkumanda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "URL'den oynat" penceresi: panodaki adres kendiliğinden gelir, altında son kullanılanlar. */
@Composable
fun CastUrlDialog(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(clipboard.getText()?.text?.let { CastUrl.parse(it) }.orEmpty()) }
    val url = CastUrl.parse(text)
    val isPage = url != null && CastUrl.isKnownPage(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pal.Btn,
        title = { Text("URL'den oynat", color = Pal.Text, fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("https://…/video.mp4", color = Pal.Sub) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Pal.Text, unfocusedTextColor = Pal.Text,
                        focusedBorderColor = Pal.Blue, unfocusedBorderColor = Pal.Sub.copy(alpha = 0.5f), cursorColor = Pal.Blue
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (isPage) Text(
                    "Bu bir sayfa adresi, video dosyası değil; TV doğrudan oynatamaz. Linki bu siteden \"Paylaş\" ile gönderirsen TV'de açılır.",
                    color = Pal.Amber, fontSize = 12.sp
                ) else Text(
                    "Videonun doğrudan adresi gerekir (.mp4, .webm, .m3u8 gibi).",
                    color = Pal.Sub, fontSize = 12.sp
                )
                if (AppSettings.castUrls.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("Son kullanılanlar", color = Pal.Sub, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    for (u in AppSettings.castUrls) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { text = u }.padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(u, color = Pal.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable { AppSettings.removeCastUrl(context, u) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Outlined.Close, "Listeden kaldır", tint = Pal.Sub, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = url != null && !isPage, onClick = { onDismiss(); url?.let(onPlay) }) {
                Text("TV'de oynat", color = if (url != null && !isPage) Pal.Blue else Pal.Sub)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç", color = Pal.Sub) } }
    )
}
