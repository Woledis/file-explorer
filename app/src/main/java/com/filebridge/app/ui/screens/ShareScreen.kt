package com.filebridge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filebridge.app.ui.AppViewModel
import com.filebridge.app.ui.components.CenteredText
import com.filebridge.app.ui.components.InfoRow
import com.filebridge.app.ui.components.SectionCard

@Composable
fun ShareScreen(viewModel: AppViewModel) {
    val config by viewModel.config.collectAsState()
    val labels by viewModel.shareLabels.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.addShare(it) }
    }

    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("共享范围", style = MaterialTheme.typography.titleLarge)

        SectionCard("准备共享") {
            Text(
                "选择要共享给电脑的文件夹。后启动服务后，电脑浏览器会看到一个「共享的文件夹」入口。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(1.dp))
            Button(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加共享文件夹")
            }
        }

        SectionCard("已选择（${config.sharedUris.size}）") {
            if (config.sharedUris.isEmpty()) {
                CenteredText("还没有共享任何文件夹")
            }
            config.sharedUris.forEach { uri ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            // 直接读缓存 Map,缓存未就绪时退到 URI 末段,不再每次重组都跑 SAF 查询。
                            labels[uri] ?: uri.substringAfterLast(":"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            uri,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { viewModel.removeShare(uri) }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "移除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        SectionCard("说明") {
            InfoRow("访问限制", "仅限登录用户", Modifier)
            InfoRow("传输", if (config.tlsEnabled) "HTTPS 加密" else "HTTP", Modifier)
            InfoRow("上传", "支持（电脑可上传到共享目录）", Modifier)
        }
    }
}