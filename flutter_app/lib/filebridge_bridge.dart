/// FileBridge —— dart:ffi 到 Rust 核心的桥。
library;

import 'dart:ffi';
import 'package:ffi/ffi.dart';

final DynamicLibrary _lib = _openLib();

/// 访问口令 / HTTP端口 / FTP端口 的统一持久化文件(应用私有目录)。
const String kSettingsFile = '/data/data/com.filebridge.app/settings.txt';

DynamicLibrary _openLib() {
  try {
    return DynamicLibrary.open('libfilebridge_core.so');
  } catch (_) {
    return DynamicLibrary.process();
  }
}

// ---- strings ----
typedef _GreetNative = Pointer<Utf8> Function(Pointer<Utf8>);
typedef _GreetDart = Pointer<Utf8> Function(Pointer<Utf8>);
typedef _VersionNative = Pointer<Utf8> Function();
typedef _VersionDart = Pointer<Utf8> Function();
typedef _FreeNative = Void Function(Pointer<Void>);
typedef _FreeDart = void Function(Pointer<Void>);

// ---- engine ----
typedef _EngineStartNative = Int32 Function(
    Pointer<Utf8>, Pointer<Utf8>, Int32, Pointer<Int32>);
typedef _EngineStartDart = int Function(
    Pointer<Utf8>, Pointer<Utf8>, int, Pointer<Int32>);
typedef _EngineStopNative = Int32 Function();
typedef _EngineStopDart = int Function();
typedef _EngineRunningNative = Int32 Function();
typedef _EngineRunningDart = int Function();

// ---- vault ----
typedef _VaultEncryptNative = Int32 Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef _VaultEncryptDart = int Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);

final _greet = _lib.lookupFunction<_GreetNative, _GreetDart>('fb_greet');
final _version = _lib.lookupFunction<_VersionNative, _VersionDart>('fb_version_string');
final _free = _lib.lookupFunction<_FreeNative, _FreeDart>('fb_free_string');
final _engineStart =
    _lib.lookupFunction<_EngineStartNative, _EngineStartDart>('fb_engine_start');
final _engineStop =
    _lib.lookupFunction<_EngineStopNative, _EngineStopDart>('fb_engine_stop');
final _engineRunning =
    _lib.lookupFunction<_EngineRunningNative, _EngineRunningDart>('fb_engine_is_running');
final _vaultEncrypt =
    _lib.lookupFunction<_VaultEncryptNative, _VaultEncryptDart>('fb_vault_encrypt_file');
final _vaultDecrypt =
    _lib.lookupFunction<_VaultEncryptNative, _VaultEncryptDart>('fb_vault_decrypt_file');

String rustVersion() {
  final p = _version();
  try {
    return p.toDartString();
  } finally {
    _free(p.cast());
  }
}

String rustGreet(String name) {
  final n = name.toNativeUtf8();
  final p = _greet(n.cast());
  try {
    return p.toDartString();
  } finally {
    calloc.free(n);
    _free(p.cast());
  }
}

// ---- passsword ----
typedef _SetPwNative = Int32 Function(Pointer<Utf8>);
typedef _SetPwDart = int Function(Pointer<Utf8>);
typedef _GetPwNative = Pointer<Utf8> Function();
typedef _GetPwDart = Pointer<Utf8> Function();

final _getPassword = _lib.lookupFunction<_GetPwNative, _GetPwDart>('fb_get_password');
final _setPassword = _lib.lookupFunction<_SetPwNative, _SetPwDart>('fb_set_password');

/// 当前访问口令(可能为空=开放访问)。
String rustGetPassword() {
  final p = _getPassword();
  try {
    return p.toDartString();
  } finally {
    _free(p.cast());
  }
}

/// 设置访问口令(空串=取消口令, 开放访问)。
bool rustSetPassword(String pw) {
  final p = pw.toNativeUtf8();
  try {
    return _setPassword(p.cast()) != 0;
  } finally {
    calloc.free(p);
  }
}

// ---- settings init / ports ----
typedef _InitNative = Int32 Function(Pointer<Utf8>);
typedef _InitDart = int Function(Pointer<Utf8>);
typedef _PortSetNative = Int32 Function(Int32);
typedef _PortSetDart = int Function(int);
typedef _PortGetNative = Int32 Function();
typedef _PortGetDart = int Function();

final _settingsInit = _lib.lookupFunction<_InitNative, _InitDart>('fb_settings_init');
final _setHttpPort = _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_http_port');
final _getHttpPort = _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_http_port');
final _setFtpPort = _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_ftp_port');
final _getFtpPort = _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_ftp_port');

/// App 启动时初始化设置文件, 保证口令/端口读写针对同一持久化文件。
void initSettings() {
  final p = kSettingsFile.toNativeUtf8();
  try {
    _settingsInit(p.cast());
  } finally {
    calloc.free(p);
  }
}

/// HTTP 端口(0=未设置, 服务启动时自动分配).
int settingsGetHttpPort() => _getHttpPort();

/// FTP 端口(0=未设置, 服务启动时用默认 2121).
void settingsSetHttpPort(int port) => _setHttpPort(port);

int settingsGetFtpPort() => _getFtpPort();

void settingsSetFtpPort(int port) => _setFtpPort(port);

// ---- 服务启用 + 自定义选项 ----
final _setHttpEnabled =
    _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_http_enabled');
final _getHttpEnabled =
    _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_http_enabled');
final _setFtpEnabled =
    _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_ftp_enabled');
final _getFtpEnabled =
    _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_ftp_enabled');
final _setIdleTimeout =
    _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_idle_timeout');
final _getIdleTimeout =
    _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_idle_timeout');
final _setShowHidden =
    _lib.lookupFunction<_PortSetNative, _PortSetDart>('fb_settings_set_show_hidden');
final _getShowHidden =
    _lib.lookupFunction<_PortGetNative, _PortGetDart>('fb_settings_get_show_hidden');

/// 该服务是否已启用(设置页控制; 未启用则主页不显示、不能启动)。
bool settingsGetHttpEnabled() => _getHttpEnabled() != 0;
void settingsSetHttpEnabled(bool on) => _setHttpEnabled(on ? 1 : 0);
bool settingsGetFtpEnabled() => _getFtpEnabled() != 0;
void settingsSetFtpEnabled(bool on) => _setFtpEnabled(on ? 1 : 0);

/// HTTP 控制连接空闲超时(秒)。
int settingsGetIdleTimeout() => _getIdleTimeout();
void settingsSetIdleTimeout(int secs) => _setIdleTimeout(secs);

/// 网页目录列表是否显示隐藏(点号开头)文件。
bool settingsGetShowHidden() => _getShowHidden() != 0;
void settingsSetShowHidden(bool on) => _setShowHidden(on ? 1 : 0);

// ---- ftp ----
typedef _FtpStartNative = Int32 Function(Pointer<Utf8>, Int32, Pointer<Int32>);
typedef _FtpStartDart = int Function(Pointer<Utf8>, int, Pointer<Int32>);
typedef _NoArgNative = Int32 Function();
typedef _NoArgDart = int Function();

final _ftpStart = _lib.lookupFunction<_FtpStartNative, _FtpStartDart>('fb_ftp_start');
final _ftpStop = _lib.lookupFunction<_NoArgNative, _NoArgDart>('fb_ftp_stop');
final _ftpIsRunning = _lib.lookupFunction<_NoArgNative, _NoArgDart>('fb_ftp_is_running');

/// 启动 FTP 服务, 返回实际端口; <=0 = 失败。
int ftpStart(String root, int port) {
  final rootPtr = root.toNativeUtf8();
  final outPort = calloc<Int32>(1);
  try {
    final ok = _ftpStart(rootPtr.cast(), port, outPort);
    if (ok <= 0) return 0;
    return outPort.value;
  } finally {
    calloc.free(rootPtr);
    calloc.free(outPort);
  }
}

void ftpStop() {
  _ftpStop();
}

/// 停止并等待 FTP 真正退出(最多 ~1s), 避免重启时端口仍被占用。
Future<bool> ftpStopAndWait() async {
  ftpStop();
  for (var i = 0; i < 50; i++) {
    if (!ftpRunning()) return true;
    await Future.delayed(const Duration(milliseconds: 20));
  }
  return !ftpRunning();
}

bool ftpRunning() => _ftpIsRunning() != 0;

/// root: 共享根目录。settingsFile: 设置文件路径(存放口令, 可空)。port: 0=自动。
/// 返回实际端口；返回 <=0 表示启动失败。
int engineStart(String root, String settingsFile, int port) {
  final rootPtr = root.toNativeUtf8();
  final sfPtr = settingsFile.toNativeUtf8();
  final outPort = calloc<Int32>(1);
  try {
    final ok = _engineStart(rootPtr.cast(), sfPtr.cast(), port, outPort);
    if (ok <= 0) {
      return 0;
    }
    return outPort.value;
  } finally {
    calloc.free(rootPtr);
    calloc.free(sfPtr);
    calloc.free(outPort);
  }
}

void engineStop() {
  _engineStop();
}

/// 停止并等待引擎真正退出(Rust 侧轮询间隔 50ms, 最多等 ~1s)。
/// 不等就重启会有端口仍被旧监听占用、启动失败的竞态。
Future<bool> engineStopAndWait() async {
  engineStop();
  for (var i = 0; i < 50; i++) {
    if (!engineRunning()) return true;
    await Future.delayed(const Duration(milliseconds: 20));
  }
  return !engineRunning();
}

bool engineRunning() => _engineRunning() != 0;

bool vaultEncryptFile(String src, String dst, String password) => _vaultCall(
      _vaultEncrypt,
      src,
      dst,
      password,
    );

bool vaultDecryptFile(String src, String dst, String password) => _vaultCall(
      _vaultDecrypt,
      src,
      dst,
      password,
    );

bool _vaultCall(
  _VaultEncryptDart fn,
  String src,
  String dst,
  String password,
) {
  final s = src.toNativeUtf8();
  final d = dst.toNativeUtf8();
  final p = password.toNativeUtf8();
  try {
    return fn(s.cast(), d.cast(), p.cast()) != 0;
  } finally {
    calloc.free(s);
    calloc.free(d);
    calloc.free(p);
  }
}