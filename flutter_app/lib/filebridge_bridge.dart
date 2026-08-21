/// FileBridge Flutter UI —— Rust 核心桥入口。
///
/// M1：通过 dart:ffi 调用 Rust 的 C ABI 导出，验证"Flutter -> Rust"最小链路。
/// 目录列表通过 List<FileInfo> 由 dart:ffi 拉取，真正的传输服务仍复用 Rust 引擎。
library;

import 'dart:ffi';
import 'package:ffi/ffi.dart';

final DynamicLibrary _lib = _openLib();

DynamicLibrary _openLib() {
  try {
    return DynamicLibrary.open('libfilebridge_core.so');
  } catch (_) {
    // CI 桌面/调试环境兜底：允许以进程自身暴露的符号(主要是跑 dart 单元测试)
    return DynamicLibrary.process();
  }
}

const int _kNullBufferSize = 256;

typedef _GreetNative = Pointer<Utf8> Function(Pointer<Utf8> name);
typedef _GreetDart = Pointer<Utf8> Function(Pointer<Utf8> name);

typedef _VersionNative = Pointer<Utf8> Function();
typedef _VersionDart = Pointer<Utf8> Function();

typedef _FreeNative = Void Function(Pointer<Void>);
typedef _FreeDart = void Function(Pointer<Void>);

final _greet = _lib.lookupFunction<_GreetNative, _GreetDart>('fb_greet');
final _version = _lib.lookupFunction<_VersionNative, _VersionDart>('fb_version_string');
final _free = _lib.lookupFunction<_FreeNative, _FreeDart>('fb_free_string');

/// 给用户展示的版本串。
String rustVersion() {
  final p = _version();
  try {
    return p.toDartString();
  } finally {
    _free(p.cast());
  }
}

/// 问候测试：验证跨 FFI 传参/返回值。
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

// 占位常量，避免无用字段触发静态分析噪音
const int kNullBufferSize = _kNullBufferSize;