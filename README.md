# FileBridge 文件桥

[![CI 构建状态](https://github.com/Woledis/file-explorer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Woledis/file-explorer/actions/workflows/build-apk.yml)

把安卓手机变成一台局域网文件服务器：电脑既可用**浏览器**（扫码）访问手机共享文件夹，也可用**FTP 客户端**（资源管理器 / FileZilla）像本地文件夹一样使用，支持浏览、下载与上传。所有访问都需要密码登录。

## 功能特性

| 类别 | 能力 | 说明 |
| --- | --- | --- |
| 访问方式 | 浏览器 | 电脑浏览器实时浏览共享目录，支持内联预览与下载 |
| | FTP 服务器 | 可选启用，资源管理器 / FileZilla 直接访问，可上传、下载、建目录、重命名 |
| | FTP 访问全部文件 | 可选，FTP 根目录切到手机全部存储（需授予存储权限） |
| 文件操作 | 上传 | 电脑可将文件上传到任意共享目录（支持多选） |
| | 下载 / 预览 | 在线浏览、各类常见格式内联打开与下载 |
| 安全 | 访问密码 | PBKDF2-SHA256 密码校验，失败 5 次锁定 5 分钟 |
| | 会话管理 | 浏览器登录发放会话令牌，空闲超时可调（10 / 30 / 60 / 120 分钟） |
| | HTTPS 传输 | 可选启用，自签名证书加密局域网传输（默认 HTTP） |
| | 加密保险箱 | 可选，AES-256-GCM 静态加密存储，**默认关闭** |
| 体验 | 深/浅色主题 | 跟随系统 / 浅色 / 深色三档可切换 |
| | 滑动导航 | 五个页面左右滑动切换，底部导航同步高亮 |
| | 扫码访问 | 主屏展示局域网地址二维码，电脑扫码即达 |
| 运行 | 前台服务 | 应用退到后台文件服务仍持续运行 |

## 技术栈

- 界面：Kotlin、Jetpack Compose（Material 3）
- HTTP/HTTPS 服务端：NanoHTTPD（内嵌服务器）
- FTP 服务端：Apache MINA Ftpserver
- 存储：Storage Access Framework（SAF，避免申请全盘存储权限）
- 加密：PBKDF2-HMAC-SHA256（密码派生）、AES-256-GCM（文件加密）、TLS 1.2/1.3（传输）
- 可选内核：Rust（NDK 交叉编译，CI 构建自动启用；本机未装 Rust 时退化为 Kotlin 内核）
- SDK：minSdk 26 / target 34，AGP 8.5.2，Gradle 8.7

## 环境要求

- 安卓 8.0（API 26）及以上
- 手机与电脑处于同一局域网
- 使用 FTP 时，电脑需支持 FTP/SFTP 客户端（Windows 资源管理器原生支持）

## 构建打包

### 方式一：Android Studio（推荐）

1. `File → Open` 打开本目录（`filebridge-app`），等待 Gradle Sync（首次自动下载依赖，需联网）。
2. 若提示缺少 SDK，在 `SDK Manager` 安装 `Android SDK Platform 34` 与 `Build Tools 34`。
3. 调试：
   - 手机开启「开发者选项 → USB 调试」并连接，点击 ▶ **Run**，可直接装机运行。
   - 或在菜单 `Build → Build APK(s)`，产物位于 `app/build/outputs/apk/debug/app-debug.apk`。
4. 正式签名包：`Build → Generate Signed Bundle / APK` → 选择 **APK**，新建或选择 keystore 后生成 `app-release.apk`。

> **Rust 内核说明**：项目的 `buildRust` 任务会在检测到本机安装了 Rust 工具链与 NDK 时，把 Rust 库交叉编译进 APK；若未安装则自动跳过，APK 仍可正常构建运行（使用 Kotlin 内核）。GitHub Actions 上自动构建的是带 Rust 内核的版本。

### 方式二：命令行

需先在机器上安装 JDK 17+ 与 Android SDK，并配置 `local.properties`（`sdk.dir=...`）：

```bash
cd filebridge-app
gradle assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # 需先在 app/build.gradle.kts 配置签名信息
```

## 使用指南

### 浏览器方式

1. 安装并打开 FileBridge，进入「设置」设置**访问密码**。
2. 进入「共享」页，点「添加共享文件夹」，选择要共享的目录（系统文件选择器授权一次即可）。
3. 回到「主屏」，点击**启动服务**，页面显示访问地址与二维码。
4. 电脑浏览器打开该地址（或扫码），输入密码后即可浏览 / 下载 / 上传。
5. 用完点击**停止服务**；所有已登录设备会立即登出。

### FTP 方式

1. 在「设置 → FTP 服务器」打开「**启用 FTP**」；如需访问手机全部文件，再打开「**FTP 访问全部文件**」并授予存储权限。
2. 回到「主屏」**重新启动服务**（FTP 地址会显示在服务运行时的主屏上，端口默认 `2121`）。
3. 电脑资源管理器地址栏输入 `ftp://手机IP:2121`（或使用 FileZilla：主机填手机 IP、端口 `2121`）。
4. 弹窗登录：**用户名任意**（如 `filebridge`），**密码填访问密码**，即可像本地文件夹一样拖拽上传 / 下载。

### 进阶

- **HTTPS 加密传输**：「设置 → 传输」打开后，用 `https://` 地址访问，首次提示自签名证书，手动信任即可。强于 FTP 的明文传输。
- **加密保险箱（默认关闭）**：「设置 → 加密保险箱」开启后，进入「保险箱」解锁，即可把文件加密收进保险箱；保险箱内文件以 AES-256-GCM 在手机本地加密保存，电脑端查看时实时解密。
- **外观切换**：「设置 → 外观」可选跟随系统 / 浅色 / 深色。
- **导航**：主屏 / 共享 / 保险箱 / 连接 / 设置五个页面左右滑动切换，底部导航同步高亮。

## 目录结构

```
filebridge-app/
├─ app/src/main/
│  ├─ java/com/filebridge/app/
│  │  ├─ crypto/     AuthCrypto(PBKDF2) · CryptoManager(AES-256-GCM) · TlsUtil(自签名证书)
│  │  ├─ data/       SettingsStore(DataStore) · SecureStore · SecurityManager(保险箱) · DocStore(SAF)
│  │  ├─ server/     FileServer(NanoHTTPD) · FtpManager(MINA FTP) · SessionStore · ServerController · ServerService(前台服务)
│  │  ├─ ui/         主屏/共享/保险箱/连接/设置 + 左右滑动导航 + Material3 主题
│  │  └─ util/       局域网 IP、二维码
│  └─ rust/          (可选) Rust 内核源码,由 buildRust 交叉编译
└─ gradle/           版本目录(libs.versions.toml)、wrapper 配置
```

## 安全说明

- 密码与保险箱密钥以哈希 / 密钥包装形式保存在应用私有 SharedPreferences 中，不存明文。
- 浏览器方式下，全部文件访问限定在用户授予的共享树目录内，网页客户端无法越权访问其他路径。
- **FTP 是明文传输**，且「FTP 访问全部文件」开启后可操作手机全部文件，仅建议在可信局域网内使用；跨网络或公网请关闭 FTP，改用 HTTPS + 高强度密码。
- 服务端仅监听本机局域网地址，不对公网开放端口。