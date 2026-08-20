# FileBridge 文件桥

[![CI 构建状态](https://github.com/Woledis/file-explorer/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Woledis/file-explorer/actions/workflows/build-apk.yml)

把安卓手机变成一台局域网文件服务器：电脑用浏览器（或扫码）访问手机上的共享文件夹，支持在线浏览、下载与上传。所有访问都需要密码登录。

## 功能特性

| 能力 | 说明 |
| --- | --- |
| 浏览 / 下载 | 电脑浏览器实时浏览共享目录，支持各类常见格式内联打开与下载 |
| 上传 | 电脑可将文件上传到任意共享目录（支持多选） |
| 访问密码 | PBKDF2-SHA256 密码校验，失败 5 次锁定 5 分钟 |
| 会话管理 | 登录后发放会话令牌，空闲超时可调（10 / 30 / 60 / 120 分钟） |
| HTTPS 传输 | 可选启用，自签名证书加密局域网传输（默认 HTTP） |
| 加密保险箱 | 可选，AES-256-GCM 静态加密存储，**默认关闭** |
| 前台服务 | 应用退到后台文件服务仍持续运行 |
| 扫码访问 | 主屏展示局域网地址二维码，电脑扫码即达 |

## 技术栈

- 语言 / 界面：Kotlin 2.0.20、Jetpack Compose（Material 3）
- 服务端：NanoHTTPD（HTTP/HTTPS 内嵌服务器）
- 存储：Storage Access Framework（SAF，无全盘存储权限）
- 加密：PBKDF2-HMAC-SHA256（密码派生）、AES-256-GCM（文件加密）、TLS 1.2/1.3（传输）
- SDK：compileSdk 34 / minSdk 26 / target 34，AGP 8.5.2，Gradle 8.7

## 环境要求

- 安卓 8.0（API 26）及以上
- 手机与电脑处于同一局域网

## 构建打包

### 方式一：Android Studio（推荐）

1. `File → Open` 打开本目录（`filebridge-app`），等待 Gradle Sync（首次自动下载依赖，需联网）。
2. 若提示缺少 SDK，在 `SDK Manager` 安装 `Android SDK Platform 34` 与 `Build Tools 34`。
3. 调试：
   - 手机开启「开发者选项 → USB 调试」并连接，点击 ▶ **Run**，可直接装机运行。
   - 或在菜单 `Build → Build APK(s)`，产物位于 `app/build/outputs/apk/debug/app-debug.apk`。
4. 正式签名包：
   - `Build → Generate Signed Bundle / APK` → 选择 **APK**。
   - 新建或选择 keystore（记好密码），随后生成 `app-release.apk`。

### 方式二：命令行

需先在机器上安装 JDK 17+ 与 Android SDK，并配置 `local.properties`（`sdk.dir=...`）：

```bash
cd filebridge-app
gradle assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease      # 需先在 app/build.gradle.kts 配置签名信息
```

## 使用指南

1. 安装并打开 FileBridge。
2. 进入「设置」，设置**访问密码**（电脑登录与保险箱解锁共用）。
3. 进入「共享范围」，点击「添加共享文件夹」，选择要共享的目录（系统文件选择器授权一次即可）。
4. 回到「主屏」，点击**启动服务**，页面显示访问地址与二维码。
5. 电脑浏览器打开该地址（或扫码），输入密码后即可浏览 / 下载 / 上传。
6. 用完点击**停止服务**；所有已登录设备会立即登出。

### 进阶：HTTPS 与加密保险箱

- 传输加密：在「设置 → 传输」打开「HTTPS 加密传输」，用 `https://` 地址访问，首次会提示自签名证书，手动信任后即可。
- 静态加密（默认关闭）：在「设置 → 加密保险箱」开启后，进入「保险箱」解锁即可把文件加密收进保险箱；保险箱内文件以 AES-256-GCM 在手机本地加密保存，电脑端查看时实时解密。

## 目录结构

```
filebridge-app/
├─ app/src/main/java/com/filebridge/app/
│  ├─ crypto/     AuthCrypto(PBKDF2) · CryptoManager(AES-256-GCM) · TlsUtil(自签名证书)
│  ├─ data/       SettingsStore(DataStore) · SecureStore · SecurityManager(保险箱) · DocStore(SAF)
│  ├─ server/     FileServer(NanoHTTPD) · SessionStore · ServerController · ServerService(前台服务)
│  ├─ ui/         主屏/共享范围/保险箱/连接记录/设置 + 底部导航 + Material3 主题
│  └─ util/       局域网 IP、二维码
└─ gradle/        版本目录(libs.versions.toml)、wrapper 配置
```

## 安全说明

- 密码与保险箱密钥以哈希 / 密钥包装形式保存在应用私有 SharedPreferences 中，不存明文。
- 全部文件访问限定在用户授予的共享树目录内，网页客户端无法越权访问其他路径。
- 默认 HTTP + 密码登录即可满足可信局域网使用；跨网络或公网使用请启用 HTTPS 并设置高强度密码。
- 服务端仅监听本机局域网地址，不对公网开放端口。