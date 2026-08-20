package com.filebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filebridge.app.ui.AppViewModel
import com.filebridge.app.ui.components.CenteredText
import com.filebridge.app.ui.components.InfoRow
import com.filebridge.app.ui.components.SectionCard

@Composable
fun ConnectionsScreen(viewModel: AppViewModel) {
    val state by viewModel.serverState.collectAsState()
    val config by viewModel.config.collectAsState()

    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("连接记录", style = MaterialTheme.typography.titleLarge)

        SectionCard("运行状态") {
            InfoRow("服务", if (state.running) "运行中" else "已停止", Modifier)
            InfoRow("地址", state.url.ifEmpty { "—" }, Modifier)
            InfoRow("当前连接", "${state.connections} 台设备", Modifier)
            InfoRow("会话超时", "${state.timeoutMin} 分钟", Modifier)
            InfoRow("加密保险箱", if (config.encryptionEnabled) "开启" else "关闭", Modifier)
        }

        SectionCard("说明") {
            CenteredText(
                if (state.running)
                    "正在浏览/下载的电脑即为活动连接。停止服务后，所有会话令牌会立即失效，已登录的设备需重新输入密码。"
                else
                    "当前没有运行中的服务。启动服务后，这里会显示实时连接情况。"
            )
        }
    }
}