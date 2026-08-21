import 'package:flutter/material.dart';

import '../filebridge_bridge.dart';

/// 设置页: 访问口令(简化设置, 无需旧口令/重复输入) + 深浅色切换。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.onThemeChanged});

  final ValueChanged<ThemeMode> onThemeChanged;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _password = TextEditingController();
  bool _passwordEnabled = false;
  ThemeMode _theme = ThemeMode.system;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final pw = rustGetPassword();
    _passwordEnabled = pw.isNotEmpty;
    if (pw.isNotEmpty) {
      // 不回显已有口令, 留空让用户选择是否修改
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