package com.filebridge.app.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToPhotos
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filebridge.app.ui.AppViewModel
import com.filebridge.app.ui.components.CenteredText
import com.filebridge.app.ui.components.SectionCard

@Composable
fun VaultScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsState()
    val meta by viewModel.meta.collectAsState()

    var showUnlock by remember { mutableStateOf(false) }
    var unlockError by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("保险箱", style = MaterialTheme.typography.titleLarge)

        if (!config.encryptionEnabled) {
            SectionCard("静态加密默认关闭") {
                CenteredText("保险箱（AES-256-GCM 加密存储）当前处于关闭状态。请到「设置」中开启「文件加密保险箱」。")
            }
            return@Column
        }
        if (!meta.passwordSet) {
            SectionCard("尚未设置密码") {
                CenteredText("请先在「设置」中设置访问密码，之后才能使用保险箱。")
            }
            return@Column
        }
        if (!meta.vaultUnlocked) {
            SectionCard("保险箱已锁定") {
                CenteredText("保险箱密钥由主密码保护。每次打开 App 后需输入主密码解锁，才能查看、上传、下载加密文件。")
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showUnlock = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("解锁保险箱")
                }
            }
            return@Column
        }
        VaultContent(viewModel)
    }

    if (showUnlock) {
        UnlockDialog(
            onDismiss = { showUnlock = false; unlockError = false },
            onSubmit = { pw ->
                viewModel.unlockVault(pw) { ok ->
                    unlockError = !ok
                    if (ok) showUnlock = false
                }
            },
            error = unlockError,
        )
    }
}

@Composable
private fun VaultContent(viewModel: AppViewModel) {
    val context = LocalContext.current
    // 直接读 StateFlow,不在主线程跑 listFiles();进入页面时拉一次后台刷新。
    val entries by viewModel.vaultEntries.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshVault() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            var name = it.lastPathSegment ?: "file"
            runCatching {
                context.contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
                }
            }
            val input = context.contentResolver.openInputStream(it)
            if (input != null) {
                // encrypt 完成后 ViewModel 已自动 refreshVault(),UI 自动更新,不再手动 version++。
                viewModel.addToVault(name, input) {}
            }
        }
    }

    SectionCard("操作") {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AddToPhotos, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("把文件加密收进保险箱")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "加入的文件会先在手机本地加密，再从电脑端查看/下载。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("保险箱内容（${entries.size}）") {
        if (entries.isEmpty()) {
            CenteredText("保险箱为空。所有文件都会用 AES-256-GCM 加密保存。")
        }
        entries.forEach { e ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        e.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatSize(e.cipherSize),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.deleteVault(e.name) }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun UnlockDialog(onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit, error: Boolean) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解锁保险箱") },
        text = {
            Column {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    label = { Text("主密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (error) {
                    Text("密码错误", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pw.isNotEmpty()) onSubmit(pw.toCharArray()) }) { Text("解锁") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}