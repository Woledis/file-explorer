/// FileBridge —— dart:ffi 到 Rust 核心的桥。
library;

import 'dart:ffi';
import 'package:ffi/ffi.dart';

final DynamicLibrary _lib = _openLib();

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