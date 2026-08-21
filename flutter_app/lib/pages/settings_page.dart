import 'dart:io';

import 'package:flutter/material.dart';

import '../filebridge_bridge.dart';
import '../keepalive.dart';

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
  final _httpPortCtrl = TextEditingController();
  final _ftpPortCtrl = TextEditingController();
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
    final hp = settingsGetHttpPort();
    final fp = settingsGetFtpPort();
    _httpPortCtrl.text = hp > 0 ? '$hp' : '';
    _ftpPortCtrl.text = fp > 0 ? '$fp' : '';
    _passwordEnabled = rustGetPassword().isNotEmpty;
    _httpOn = engineRunning();
    _ftpOn = ftpRunning();
    _ftpActual = _ftpPortCtrl.text.isEmpty ? 2121 : int.tryParse(_ftpPortCtrl.text) ?? 2121;
  }

  Future<void> _toggleHttp(bool on) async {
    if (on) {
      // 已在别处(主页)启动时先重启, 保证端口设置生效且开关状态一致
      if (engineRunning()) {
        await engineStopAndWait();
      }
      final port = engineStart(_root, _settingsFile, 0);
      if (port <= 0) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('HTTP 服务启动失败, 可能端口被占用')),
        );
        return;
      }
      setState(() {
        _httpOn = true;
        _httpPort = port;
      });
      syncKeepAlive();
    } else {
      await engineStopAndWait();
      setState(() {
        _httpOn = false;
        _httpPort = 0;
      });
      syncKeepAlive();
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

  Future<void> _toggleFtp(bool on) async {
    if (on) {
      // 已在别处启动时先重启, 让新端口生效
      if (ftpRunning()) {
        await ftpStopAndWait();
      }
      final def = settingsGetFtpPort();
      final port = (def > 0 && def <= 65535) ? def : 2121;
      final actual = ftpStart(_root, port);
      if (actual <= 0) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('FTP 启动失败, 可能端口被占用')),
        );
        return;
      }
      setState(() {
        _ftpOn = true;
        _ftpActual = actual;
      });
      syncKeepAlive();
    } else {
      await ftpStopAndWait();
      setState(() {
        _ftpOn = false;
        _ftpActual = 0;
      });
      syncKeepAlive();
    }
  }

  void _savePorts() {
    final h = _parsePort(_httpPortCtrl.text);
    final f = _parsePort(_ftpPortCtrl.text);
    settingsSetHttpPort(h);
    settingsSetFtpPort(f);
    setState(() {
      _httpPortCtrl.text = h > 0 ? '$h' : '';
      _ftpPortCtrl.text = f > 0 ? '$f' : '';
    });
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('端口已保存(重新开启对应服务生效)')),
    );
  }

  int _parsePort(String s) {
    final v = int.tryParse(s.trim());
    if (v == null || v <= 0) return 0;
    return v.clamp(0, 65535);
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
    _httpPortCtrl.dispose();
    _ftpPortCtrl.dispose();
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
          const SizedBox(height: 28),
          const Text('端口设置',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          const Text(
            'HTTP 端口留空=自动; FTP 端口留空=2121。保存后重新开启服务生效。',
            style: TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                children: [
                  TextField(
                    controller: _httpPortCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'HTTP 端口(留空自动)',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _ftpPortCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'FTP 端口(留空 2121)',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.tonalIcon(
                      onPressed: _savePorts,
                      icon: const Icon(Icons.save_outlined),
                      label: const Text('保存端口'),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 28),
          const Text('服务',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
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
                    : '给电脑提供 FTP 文件共享(端口 ${settingsGetFtpPort() > 0 ? settingsGetFtpPort() : _ftpPort})',
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