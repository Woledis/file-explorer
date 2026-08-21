import 'dart:io';
import 'dart:isolate';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import '../filebridge_bridge.dart';

/// 保险箱页: 用口令把文件加密为 FBE 格式存入私有保险箱目录; 可反向解密取回。
/// 静态加密默认关闭——不选择"加密"操作即不产生任何加密文件。
/// 加解密在后台 isolate 执行, 避免 PBKDF2/AES-GCM 阻塞 UI。
class VaultPage extends StatefulWidget {
  const VaultPage({super.key});

  @override
  State<VaultPage> createState() => _VaultPageState();
}

class _VaultPageState extends State<VaultPage> {
  final _password = TextEditingController();
  String _dir = '';
  String _log = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _resolveDir();
  }

  Future<void> _resolveDir() async {
    try {
      final doc = await getApplicationDocumentsDirectory();
      final dir = Directory('${doc.path}/bridge_vault');
      await dir.create(recursive: true);
      if (mounted) setState(() => _dir = dir.path);
    } catch (_) {}
  }

  void _append(String line) {
    setState(() => _log = '$line\n$_log');
  }

  Future<PlatformFile?> _pickSingleFile(String ext) async {
    final result = await FilePicker.platform.pickFiles(
      allowMultiple: false,
      type: ext.isNotEmpty ? FileType.custom : FileType.any,
      allowedExtensions: ext.isNotEmpty ? [ext] : null,
    );
    if (result == null) return null;
    return result.files.single;
  }

  Future<void> _encrypt() async {
    final pw = _password.text;
    if (pw.isEmpty) {
      _append('请先输入保险箱口令');
      return;
    }
    final file = await _pickSingleFile('');
    if (file == null) return; // 用户取消
    final src = file.path;
    if (src == null) return;
    if (_dir.isEmpty) {
      _append('保险箱目录未就绪');
      return;
    }
    final fname = file.name.contains('.fbv') ? file.name : '${file.name}.fbv';
    final dst = '$_dir/$fname';
    setState(() => _busy = true);
    try {
      final ok = await Isolate.run(() => vaultEncryptFile(src, dst, pw));
      _append(ok ? '已加密: $fname' : '加密失败(口令或文件问题)');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _decrypt() async {
    final pw = _password.text;
    if (pw.isEmpty) {
      _append('请先输入保险箱口令');
      return;
    }
    final file = await _pickSingleFile('fbv');
    if (file == null) return;
    final src = file.path;
    if (src == null) return;
    if (_dir.isEmpty) {
      _append('保险箱目录未就绪');
      return;
    }
    // 去掉 .fbv 后缀作为解密输出名
    final outName = file.name.replaceFirst(RegExp(r'\.fbv$'), '');
    final dst = '$_dir/${outName.isEmpty ? 'decrypted' : outName}';
    setState(() => _busy = true);
    try {
      final ok = await Isolate.run(() => vaultDecryptFile(src, dst, pw));
      _append(ok ? '已解密: $outName' : '解密失败(口令错误或文件非法)');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: _password,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: '保险箱口令',
              hintText: '加密/解密使用的口令',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '加密文件保存在应用私有保险箱目录:\n$_dir',
            style: const TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _busy ? null : _encrypt,
                  icon: _busy
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.lock_outline),
                  label: const Text('选文件并加密'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _decrypt,
                  icon: const Icon(Icons.lock_open),
                  label: const Text('选 .fbv 解密'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          if (_log.isNotEmpty)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(_log, style: const TextStyle(fontFamily: 'monospace')),
            ),
        ],
      ),
    );
  }
}