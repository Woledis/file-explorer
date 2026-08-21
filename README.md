# FileBridge 文件桥

[![CI 构建状态](https://github.com/Woledis/file-explorer/actions/workflows/build-flutter.yml/badge.svg)](https://github.com/Woledis/file-explorer/actions/workflows/build-flutter.yml)

把安卓手机变成一台局域网文件服务器：电脑既可用**浏览器**访问手机共享目录（浏览 / 下载 / 上传），也可用 **FTP 客户端**（资源管理器 / FileZilla）像本地文件夹一样使用。文件服务器核心用 **Rust** 编写，界面用 **Flutter** 构建。

## 功能特性

| 类别 | 能力 | 说明 |
| --- | --- | --- |
| 访问方式 | 浏览器 | 电脑浏览器实时浏览共享目录，支持下载与断点续传（Range） |
| | 浏览器上传 | 网页端可直接把文件上传到当前目录（Rust 流式写入） |
| | FTP 服务器 | 可选启用，资源管理器 / FileZilla 视为本地文件夹：上传 / 下载 / 建目录 / 重命名 / 断点续传 |
| 安全 | 访问口令 | 浏览器登录用口令（cookie 会话），FTP 同样校验访问口令 |
| | 加密保险箱 | 可选，AES-256-GCM 分块加密，**默认关闭**；不点加密即不产生密文 |
| 体验 | 深/浅色主题 | 跟随系统 / 浅色 / 深色三档切换，顶栏可一键切换 |
| | 滑动导航 | 主页 / 保险箱 / 设置三页左右滑动，底部导航同步高亮 |
| | 精简体积 | R8 精简 + 代码混淆 + 仅真机 ABI，快速冷启动、低后台能耗 |

## 技术栈

- 界面：Flutter（Material 3）
- 服务端核心：Rust（`dart:ffi` ↔ C ABI 桥接），纯 std 手写 HTTP（GET/HEAD/Range/PUT）+ FTP（PASV/EPSV/REST）
- 加密：PBKDF2-HMAC-SHA256（口令派生）、AES-256-GCM（文件 / .fbv 保险箱格式）
- 保险箱文件格式：`FBE2 魔数 + 随机盐 + 块大小`，每块独立随机 nonce，可流式处理大文件
- 构建：GitHub Actions 编译 Rust → 交叉编译 `.so` → 打包 Flutter release APK
- 兼容：Android 8.0（API 26）及以上

## 使用指南

### 浏览器方式

1. 安装打开 FileBridge，进入「设置」设置**访问口令**（留空并保存 = 开放访问）。
2. 回到「主页」，点击**启动服务**，页面显示访问地址 `http://手机IP:端口`。
3. 电脑浏览器打开该地址（或扫页面地址），输入口令后即可浏览 / 下载 / 上传。
4. 用完点**停止服务**。

### FTP 方式

1. 「设置」打开 **FTP 服务** 开关，页面显示 `ftp://手机IP:2121`。
2. 电脑资源管理器地址栏输入 `ftp://手机IP:2121`（或 FileZilla：主机填手机 IP、端口 `2121`），
   弹窗登录：**用户名任意**（如 `filebridge`，留空亦可），**密码填访问口令**。
3. 即可像本地文件夹一样上传 / 下载 / 断点续传。

### 加密保险箱

1. 进入「保险箱」页，输入保险箱口令（该口令即加密密钥的派生口令）。
2. 点「**选文件并加密**」→ 选取要保护的任意文件，加密为 `.fbv` 存入应用私有保险箱目录。
3. 需要取回时点「**选 .fbv 解密**」，选择加密文件，输入正确口令即可还原。
4. 加解密默认关闭——不使用即不会产生任何密文。

### 外观

「设置 → 外观」选跟随系统 / 浅色 / 深色；顶栏按钮可快捷循环切换。三页可左右滑动切换。

## 构建打包

无需本地 Rust / Flutter 工具链，直接在 GitHub Actions 上构建并下载成品 APK：

1. 打开 [Actions](https://github.com/Woledis/file-explorer/actions) 页面 → 选中最近的 **build-flutter** 运行记录。
2. 展开已构建运行的 **Artifacts** 区。
3. 下载 `filebridge-flutter-apk`，得到两个可安装包：
   - `…arm64-v8a….apk` — 现代 64 位手机（推荐）
   - `…armeabi-v7a….apk` — 较老的 32 位手机
4. 把 APK 传到手机（或直接点开下载的 APK），运行时允许「安装未知来源」即可安装。

> 若想在本机构建：安装 [Flutter SDK](https://flutter.dev)，在 `flutter_app/` 下运行
> `flutter build apk --release --split-per-abi`（需先交叉编译 `flutter_app/rust` 为 `.so`，
> 或直接依赖 CI 成品）。

## 目录结构

```
filebridge-app/
├─ .github/workflows/build-flutter.yml    CI: 编译 Rust → .so → 打包 Flutter APK(精简/混淆)
└─ flutter_app/
   ├─ lib/
   │  ├─ main.dart                        三页滑动导航 + 深浅色主题
   │  ├─ filebridge_bridge.dart           dart:ffi ↔ Rust C ABI 桥(HTTP/FTP/口令/保险箱)
   │  └─ pages/                           主页 · 保险箱 · 设置
   ├─ rust/src/
   │  ├─ lib.rs                           C ABI 入口(引擎/口令/保险箱/FTP/版本)
   │  ├─ http.rs                          HTTP 服务(目录/下载/Range/上传/登录)
   │  ├─ ftp.rs                           FTP 服务(PASV/EPSV/上下传/断点/目录管理)
   │  ├─ auth.rs                          口令校验 + 会话令牌(cookie)
   │  ├─ settings.rs                      口令持久化
   │  └─ vault.rs                         AES-256-GCM 保险箱(分块流式)
   └─ pubspec.yaml
```

## 安全说明

- 浏览器与 FTP 均校验访问口令；浏览器登录发放一次性会话 cookie，重启后全部失效。
- 服务端文件访问限定在 `/storage/emulated/0` 内，路径做规范化处理，防止目录穿越。
- **HTTP 与 FTP 均为局域网明文传输**，仅建议在可信局域网内使用；请勿暴露到公网。
- 保险箱密文只有掌握保险箱口令者能解密；私钥由口令派生，不在手机明文存储。
- 访问口令以明文存在设置文件（应用私有目录）中——首次使用请先设置口令，避免局域网内被访问。