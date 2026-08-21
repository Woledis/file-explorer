import 'dart:io';

import 'package:flutter/material.dart';

import '../filebridge_bridge.dart';

/// 设置页: 访问口令(简化设置, 无需旧口令/重复输入) + FTP 服务 + 深浅色切换。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.onThemeChanged});

  final ValueChanged<ThemeMode> onThemeChanged;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  static const String _root = '/storage/emulated/0';
  static const String _settingsFile = '/data/data/com.filebridge.app/settings.txt';
  static const int _ftpPort = 2121;

  final _password = TextEditingController();
  bool _passwordEnabled = false;
  ThemeMode _theme = ThemeMode.system;
  bool _httpOn = false;
  int _httpPort = 0;
  bool _ftpOn = false;
  int _ftpActual = 0;
  String _lanIp = '';

  @override
  void initState() {
    super.initState();
    _load();
    _findIp();
  }

  void _load() {
    _passwordEnabled = rustGetPassword().isNotEmpty;
    _httpOn = engineRunning();
    _ftpOn = ftpRunning();
    _ftpActual = _ftpPort;
  }

  void _toggleHttp(bool on) {
    if (on) {
      final port = engineStart(_root, _settingsFile, 0);
      if (port <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('HTTP 服务启动失败, 可能已在主页启动')),
        );
        return;
      }
      setState(() {
        _httpOn = true;
        _httpPort = port;
      });
    } else {
      engineStop();
      setState(() {
        _httpOn = false;
        _httpPort = 0;
      });
    }
  }

  Future<void> _findIp() async {
    String ip = '';
    try {
      final interfaces = await NetworkInterface.list(
        includeLoopback: false,
        type: InternetAddressType.IPv4,
      );
      for (final ifc in interfaces) {
        for (final addr in ifc.addresses) {
          if (addr.address.startsWith('192.168.') ||
              addr.address.startsWith('10.') ||
              addr.address.startsWith('172.')) {
            ip = addr.address;
            break;
          }
        }
        if (ip.isNotEmpty) break;
      }
    } catch (_) {}
    if (mounted) setState(() => _lanIp = ip);
  }

  void _toggleFtp(bool on) {
    if (on) {
      final port = ftpStart(_root, _ftpPort);
      if (port <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('FTP 启动失败, 可能端口被占用')),
        );
        return;
      }
      setState(() {
        _ftpOn = true;
        _ftpActual = port;
      });
    } else {
      ftpStop();
      setState(() {
        _ftpOn = false;
        _ftpActual = 0;
      });
    }
  }

  void _savePassword() {
    final pw = _password.text.trim();
    rustSetPassword(pw);
    setState(() {
      _passwordEnabled = pw.isNotEmpty;
      _password.clear();
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(pw.isEmpty ? '已清除口令(开放访问)' : '访问口令已保存')),
    );
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
          const Text('访问口令',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          const Text(
            '电脑访问时需要的口令; 留空保存则取消口令(开放访问)。',
            style: TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _password,
            obscureText: true,
            decoration: InputDecoration(
              labelText: _passwordEnabled ? '输入新口令以修改' : '设置访问口令',
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _savePassword,
              icon: const Icon(Icons.save_outlined),
              label: const Text('保存口令'),
            ),
          ),
          const SizedBox(height: 4),
          Card(
            margin: EdgeInsets.zero,
            child: SwitchListTile(
              secondary: const Icon(Icons.http_outlined),
              title: const Text('HTTP 服务'),
              subtitle: Text(
                _httpOn && _httpPort > 0
                    ? '电脑浏览器访问: http://$_lanIp:$_httpPort'
                    : '给电脑浏览器提供文件共享',
              ),
              value: _httpOn,
              onChanged: _toggleHttp,
            ),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: SwitchListTile(
              secondary: const Icon(Icons.dns_outlined),
              title: const Text('FTP 服务'),
              subtitle: Text(
                _ftpActual > 0 && _ftpOn
                    ? '电脑文件管理器访问: ftp://$_lanIp:$_ftpActual'
                    : '给电脑提供 FTP 文件共享(端口 $_ftpPort)',
              ),
              value: _ftpOn,
              onChanged: _toggleFtp,
            ),
          ),
          const SizedBox(height: 28),
          const Text('外观',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          RadioListTile<ThemeMode>(
            value: ThemeMode.system,
            groupValue: _theme,
            onChanged: (v) => _setTheme(v!),
            title: const Text('跟随系统'),
          ),
          RadioListTile<ThemeMode>(
            value: ThemeMode.light,
            groupValue: _theme,
            onChanged: (v) => _setTheme(v!),
            title: const Text('浅色'),
          ),
          RadioListTile<ThemeMode>(
            value: ThemeMode.dark,
            groupValue: _theme,
            onChanged: (v) => _setTheme(v!),
            title: const Text('深色'),
          ),
        ],
      ),
    );
  }

  void _setTheme(ThemeMode mode) {
    setState(() => _theme = mode);
    widget.onThemeChanged(mode);
  }
}