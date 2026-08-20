package com.filebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filebridge.app.ui.AppViewModel
import com.filebridge.app.ui.components.InfoRow
import com.filebridge.app.ui.components.SectionCard

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsState()
    val meta by viewModel.meta.collectAsState()

    var showSet by remember { mutableStateOf(false) }
    var showChange by remember { mutableStateOf(false) }
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
    var ftpPortText by remember(config.ftpPort) { mutableStateOf(config.ftpPort.toString()) }
    val timeouts = listOf(10, 30, 60, 120)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        SectionCard("访问密码") {
            InfoRow("状态", if (meta.passwordSet) "已设置" else "未设置", Modifier)
            if (meta.passwordSet) {
                OutlinedButton(onClick = { showChange = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("修改密码")
                }
            } else {
                OutlinedButton(onClick = { showSet = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("设置访问密码")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "这是电脑访问的唯一凭证，也用于解锁保险箱。请使用较高强度的密码。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("传输") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HTTPS 加密传输", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "使用自签名证书加密局域网传输",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.tlsEnabled, onCheckedChange = { viewModel.setTls(it) })
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("端口") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = {
                    portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { viewModel.setPort(it) }
                }) { Text("保存端口") }
            }
            Text(
                "端口与 HTTPS 修改后，需重新启动服务生效。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("会话") {
            Text("空闲会话超时", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            timeouts.forEach { min ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = config.sessionTimeoutMin == min) {
                            viewModel.setTimeout(min)
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = config.sessionTimeoutMin == min, onClick = { viewModel.setTimeout(min) })
                    Text("${min} 分钟", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        SectionCard("FTP 服务器") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用 FTP", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "用电脑上的 FTP 客户端（资源管理器 / FileZilla）直接访问，可上传和下载",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.ftpEnabled, onCheckedChange = { viewModel.setFtpEnabled(it) })
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = ftpPortText,
                onValueChange = { ftpPortText = it },
                label = { Text("FTP 端口") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = config.ftpEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = {
                    ftpPortText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { viewModel.setFtpPort(it) }
                }) { Text("保存端口") }
            }
            Text(
                "FTP 使用同一访问密码登录，用户名任意。端口与开关修改后需重新启动服务生效。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("加密保险箱") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文件加密保险箱", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "保险箱内的文件以 AES-256-GCM 加密保存在本地，默认关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.encryptionEnabled, onCheckedChange = { viewModel.setEncryption(it) })
            }
        }
    }

    if (showSet) {
        SetPasswordDialog(
            onDismiss = { showSet = false },
            onSubmit = { new ->
                viewModel.setPassword(new) { showSet = false }
            },
        )
    }
    if (showChange) {
        ChangePasswordDialog(
            onDismiss = { showChange = false },
            onSubmit = { old, new -> viewModel.changePassword(old, new) { ok -> if (ok) showChange = false } },
        )
    }
}

@Composable
private fun SetPasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    PasswordPairDialog(title = "设置访问密码", confirm = "设置", onDismiss = onDismiss, onSubmit = onSubmit)
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(old, { old = it }, label = { Text("旧密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(new, { new = it }, label = { Text("新密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(again, { again = it }, label = { Text("再次输入新密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (err) {
                    Spacer(Modifier.height(6.dp))
                    Text("两次输入不一致或为空", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (old.isNotEmpty() && new.isNotEmpty() && new == again) onSubmit(old.toCharArray(), new.toCharArray())
                else err = true
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordPairDialog(
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(pw, { pw = it }, label = { Text("访问密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(again, { again = it }, label = { Text("再次输入") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (err) {
                    Spacer(Modifier.height(6.dp))
                    Text("两次输入不一致或为空", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pw.isNotEmpty() && pw == again) onSubmit(pw.toCharArray())
                else err = true
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}