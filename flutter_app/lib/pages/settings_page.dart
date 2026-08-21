import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../filebridge_bridge.dart';
import '../permission_helper.dart';

/// 设置页: 访问口令 + 端口 + 服务启用/禁用 + 安全与目录自定义 + 深浅色切换。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.onThemeChanged});

  final ValueChanged<ThemeMode> onThemeChanged;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _password = TextEditingController();
  final _httpPortCtrl = TextEditingController();
  final _ftpPortCtrl = TextEditingController();
  final _idleCtrl = TextEditingController();
  bool _passwordEnabled = false;
  ThemeMode _theme = ThemeMode.system;
  bool _httpEnabled = true;
  bool _ftpEnabled = true;
  bool _showHidden = false;
  int _idleSecs = 90;
  bool _fullStorageGranted = true;

  @override
  void initState() {
    super.initState();
    _load();
    _checkStorage();
  }

  static const _storageChannel = MethodChannel('filebridge/storage');

  Future<void> _checkStorage() async {
    if (!isManageStorageSupported) return;
    final granted = await isFullStorageGranted();
    if (mounted) setState(() => _fullStorageGranted = granted);
  }

  /// 直接跳到系统的「所有文件访问」授权页给本应用授权
  /// (默认的应用详情页里没有该开关, 用户会找不到本软件; 由原生返回再刷新状态)。
  Future<void> _requestFullStorage() async {
    try {
      await _storageChannel.invokeMethod('openAllFilesAccess');
    } catch (_) {
      // channel 不可用(非注入构建)时退回插件的系统请求
      await requestFullStorage();
    }
    // 用户切到系统开启后返回 App, 重新读取授权状态
    await Future.delayed(const Duration(milliseconds: 600));
    if (!mounted) return;
    final granted = await isFullStorageGranted();
    setState(() => _fullStorageGranted = granted);
  }

  void _load() {
    final hp = settingsGetHttpPort();
    final fp = settingsGetFtpPort();
    _httpPortCtrl.text = hp > 0 ? '$hp' : '';
    _ftpPortCtrl.text = fp > 0 ? '$fp' : '';
    _passwordEnabled = rustGetPassword().isNotEmpty;
    _httpEnabled = settingsGetHttpEnabled();
    _ftpEnabled = settingsGetFtpEnabled();
    _idleSecs = settingsGetIdleTimeout();
    _idleCtrl.text = '$_idleSecs';
    _showHidden = settingsGetShowHidden();
  }

  // ---- 启用/禁用(主页据此显示与可控); 启用不改变当前运行状态 ----

  void _toggleHttpEnabled(bool on) {
    settingsSetHttpEnabled(on);
    setState(() => _httpEnabled = on);
  }

  void _toggleFtpEnabled(bool on) {
    settingsSetFtpEnabled(on);
    setState(() => _ftpEnabled = on);
  }

  void _saveIdleTimeout() {
    final v = int.tryParse(_idleCtrl.text.trim());
    final secs = (v == null || v < 5) ? 90 : v.clamp(5, 3600);
    settingsSetIdleTimeout(secs);
    setState(() {
      _idleSecs = secs;
      _idleCtrl.text = '$secs';
    });
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('会话空闲超时已保存')),
    );
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
    _idleCtrl.dispose();
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
          // 存储权限: 仅在未授权时提示引导; 已授权无需任何展示
          if (isManageStorageSupported && !_fullStorageGranted) ...[
            const Text('存储权限',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading:
                          Icon(Icons.warning_amber, color: Colors.orange),
                      title: Text('未授予「所有文件访问」'),
                      subtitle: Text(
                        'Android 11+ 需此权限才能让电脑读到完整目录',
                        style: TextStyle(fontSize: 12),
                      ),
                    ),
                    const SizedBox(height: 4),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: _requestFullStorage,
                        icon: const Icon(Icons.lock_open_outlined),
                        label: const Text('去授权'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 28),
          ],
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
          const Text(
            '这里控制各服务是否启用。已启用的服务在主页显示并可由你启动/停止。',
            style: TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: SwitchListTile(
              secondary: const Icon(Icons.http_outlined),
              title: const Text('启用 HTTP 服务'),
              subtitle: const Text('在主页显示 HTTP(浏览器)入口并可启停'),
              value: _httpEnabled,
              onChanged: _toggleHttpEnabled,
            ),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: SwitchListTile(
              secondary: const Icon(Icons.dns_outlined),
              title: const Text('启用 FTP 服务'),
              subtitle: const Text('在主页显示 FTP(文件管理器)入口并可启停'),
              value: _ftpEnabled,
              onChanged: _toggleFtpEnabled,
            ),
          ),
          const SizedBox(height: 28),
          const Text('安全与目录',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 4),
          const Text(
            '会话空闲超时: HTTP 长连接无活动多久后自动断开(5-3600 秒)。',
            style: TextStyle(color: Colors.grey, fontSize: 12),
          ),
          const SizedBox(height: 12),
          Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextField(
                    controller: _idleCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: '会话空闲超时(秒)',
                      border: OutlineInputBorder(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.tonalIcon(
                      onPressed: _saveIdleTimeout,
                      icon: const Icon(Icons.save_outlined),
                      label: const Text('保存'),
                    ),
                  ),
                  const Divider(height: 24),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('显示隐藏文件'),
                    subtitle: const Text('在网页目录列表中显示以 . 开头的文件/夹'),
                    value: _showHidden,
                    onChanged: (v) {
                      setState(() => _showHidden = v);
                      settingsSetShowHidden(v);
                    },
                  ),
                ],
              ),
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