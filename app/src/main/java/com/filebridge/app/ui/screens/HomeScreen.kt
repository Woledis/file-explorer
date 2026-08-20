package com.filebridge.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filebridge.app.ui.AppViewModel
import com.filebridge.app.ui.components.CenteredText
import com.filebridge.app.ui.components.InfoRow
import com.filebridge.app.ui.components.SectionCard
import com.filebridge.app.ui.components.StatusDot
import com.filebridge.app.util.QrUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val state by viewModel.serverState.collectAsState()
    val config by viewModel.config.collectAsState()
    val meta by viewModel.meta.collectAsState()

    Column(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("文件桥", style = MaterialTheme.typography.titleLarge)

        StatusCard(state.running, state.url, config.encryptionEnabled, meta.passwordSet) {
            if (state.running) viewModel.stopServer() else viewModel.startServer()
        }

        if (state.running) {
            SectionCard("访问信息") {
                InfoRow("访问地址", state.url, Modifier)
                InfoRow("传输", if (state.tls) "HTTPS（已加密）" else "HTTP", Modifier)
                QrBlock(state)
            }
            SectionCard("已共享") {
                InfoRow("共享文件夹", config.sharedUris.size.toString() + " 个", Modifier)
                InfoRow("加密保险箱", if (config.encryptionEnabled) "已开启" else "关闭", Modifier)
                InfoRow("当前连接", "${state.connections} 台设备", Modifier)
                InfoRow("会话超时", "${state.timeoutMin} 分钟", Modifier)
            }
        } else {
            SectionCard("如何开始") {
                InfoRow("1. 设置访问密码", if (meta.passwordSet) "已设置" else "未设置（在设置页）", Modifier)
                InfoRow("2. 选择共享文件夹", config.sharedUris.size.toString() + " 个已选择", Modifier)
                InfoRow("3. 点击上方启动服务", "电脑用浏览器访问", Modifier)
            }
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    url: String,
    encryption: Boolean,
    passwordSet: Boolean,
    onToggle: () -> Unit,
) {
    val color = if (running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Text(
                if (running) "服务运行中" else "服务已停止",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (running) {
            Text(
                url,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Text(
                "尚未生成访问地址",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(if (running) "停止服务" else "启动服务")
        }
        if (!passwordSet) {
            Text(
                "尚未设置访问密码，电脑端将无法登录。请先到「设置」设置密码。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun QrBlock(state: com.filebridge.app.server.ServerController.UiState) {
    // 二维码生成放到 Default 线程,避免阻塞 Compose 主线程;尺寸 320 对 210dp 显示已经足够清晰。
    val qr by produceState<ImageBitmap?>(initialValue = null, state.url) {
        val url = state.url
        value = if (url.isEmpty()) null
        else withContext(Dispatchers.Default) { QrUtils.toBitmap(url, 320).asImageBitmap() }
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            qr?.let {
                Image(it, contentDescription = "访问二维码", modifier = Modifier.size(210.dp))
            }
        }
        Text(
            "电脑扫码，或手动输入上方地址",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (!state.tls) {
            CenteredText("当前为 HTTP 明文传输，仅建议在可信局域网使用")
        }
    }
}