#!/usr/bin/env bash
# 体积/启动优化: flutter create 生成的 android 骨架默认 release 不启用 R8。
# 这里注入 minifyEnabled + shrinkResources, 精简 Java/Kotlin 层与应用体积、加快冷启动。
set -euo pipefail

GRADLE="android/app/build.gradle"
if [ ! -f "$GRADLE" ]; then
  GRADLE="android/app/build.gradle.kts"
fi
if [ ! -f "$GRADLE" ]; then
  echo "gradle not found"; exit 1
fi

python3 - "$GRADLE" <<'PY'
import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# 存储权限在最终 APK 里丢失的根因: 资源/代码收缩(shrinkResources / R8)会移除
# 只在 manifest 声明、未被代码引用的 permission。这里显式把 shrinkResources 置为 false
# (而非仅删行, 防止 AAPT/AGP 默认行为再次收缩 manifest 权限)。
# 1) 抹掉任何已存在的 shrinkResources true
s = re.sub(r'\n\s*shrinkResources\s*(=\s*)?true', '', s)

# 2) 若尚无显式 shrinkResources false, 在 release 块补上
if re.search(r'shrinkResources\s*(=\s*)?false', s):
    print('shrinkResources already false')
elif 'minifyEnabled true' in s:
    s = s.replace('minifyEnabled true',
                  'minifyEnabled true\n            shrinkResources false', 1)
    open(p, 'w', encoding='utf-8').write(s)
    print('shrinkResources false added')
else:
    m = re.search(r'buildTypes\s*\{\s*release.*?\n(\s*)\}', s, re.S)
    if m:
        indent = m.group(1)
        s = s[:m.end()-1] + f'\n{indent}    shrinkResources false' + s[m.end()-1:]
        open(p, 'w', encoding='utf-8').write(s)
        print('shrinkResources false added (fallback)')
    else:
        open(p, 'w', encoding='utf-8').write(s)
        print('warning: could not add shrinkResources=false; leaving as-is')
PY

# 前台服务插件(flutter_foreground_task / android_lifecycle)要求 compileSdk>=35,
# 而 Flutter 3.24.5 默认只到 34, 这里强制升级避免编译失败。
python3 - "$GRADLE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
if re.search(r'compileSdk(=|\s)\s*(=\s*)?35\b', s):
    print('compileSdk already 35'); sys.exit(0)
new = re.sub(
    r'compileSdk?\s*=\s*flutter\.compileSdkVersion',
    'compileSdk = 35', s, count=1)
new = re.sub(
    r'compileSdkVersion\s+flutter\.compileSdkVersion',
    'compileSdk 35', new, count=1)
if new != s:
    open(p, 'w', encoding='utf-8').write(new)
    print('compileSdk -> 35')
else:
    print('compileSdk line not found (leaving as-is)')
PY

# MANAGE_EXTERNAL_STORAGE 仅在 targetSdk>=30 时对应用可见/可授予。
# 固定 targetSdk=34、minSdk=26, 防止 flutter 脚手架把 targetSdk 拉低, 否则安卓13上「所有文件访问」开关会被系统置灰。
python3 - "$GRADLE" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = re.sub(r'minSdk\s*=\s*flutter\.minSdkVersion', 'minSdk = 26', s)
s = re.sub(r'targetSdk\s*=\s*flutter\.targetSdkVersion', 'targetSdk = 34', s)
s = re.sub(r'minSdkVersion\s+flutter\.minSdkVersion', 'minSdkVersion 26', s)
s = re.sub(r'targetSdkVersion\s+flutter\.targetSdkVersion', 'targetSdkVersion 34', s)
open(p, 'w', encoding='utf-8').write(s)
print('targetSdk=34, minSdk=26 pinned')
PY

# R8 权限被剔除的根因与标准解法: 开启了 R8 代码精简后, 只在 manifest 声明、且代码里
# 从未引用的 permission 会被 R8 当成「未使用项」从最终 APK 里删除(这解释了为什么
# FOREGROUND_SERVICE/POST_NOTIFICATIONS(被代码用到的)留存, 而 READ/WRITE/MANAGE_EXTERNAL_STORAGE
# (只在 manifest 声明、由 NDK 读文件)丢失)。解法:
#   1) 在 proguard 规则里 keep 住 android.Manifest$permission 常量类, 使其字段不被内联/移除;
#   2) 让 release 构建把 proguard-rules.pro 纳入 proguardFiles, 使规则真正生效。
python3 - "$GRADLE" <<'PY'
import os, re, sys
p = sys.argv[1]
root = os.path.dirname(p)
rules = os.path.join(root, 'proguard-rules.pro')

rule_keep = '-keep class android.Manifest$permission { *; }'
if os.path.exists(rules):
    r = open(rules, encoding='utf-8').read()
    if rule_keep not in r:
        open(rules, 'a', encoding='utf-8').write('\n' + rule_keep + '\n')
        print('proguard keep rule appended ->', rules)
    else:
        print('proguard keep rule already present')
else:
    open(rules, 'w', encoding='utf-8').write('# 保留 manifest 权限常量, 防止 R8 把未引用的权限从最终 APK 剔除\n' + rule_keep + '\n')
    print('proguard-rules.pro created with keep rule')

s = open(p, encoding='utf-8').read()
if re.search(r"proguardFiles[^\n]*proguard-rules\.pro", s):
    print("proguardFiles already wired")
else:
    # 优先: 挂在 release 的签名行后 (kts: signingConfig = signingConfigs.getByName("debug") / groovy: signingConfig signingConfigs.debug)
    # 注意: 替换串用双引号原样字符串, 让 proguardFiles 里的单引号原样输出(不加反斜杠),
    # 因为这里会同时命中 groovy(build.gradle) 与 kts(build.gradle.kts) 两种脚手架。
    line = "            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'\n"
    s2 = re.sub(
        r'(signingConfig\s*=\s*signingConfigs\.getByName\("debug"\))',
        lambda m: m.group(1) + '\n' + line,
        s, count=1)
    if s2 != s:
        s = s2; print('proguardFiles wired (kts signing line)')
    else:
        # 兜底: 直接塞进 release 块内
        s3 = re.sub(
            r'(release\s*\{)',
            lambda m: m.group(1) + '\n' + line,
            s, count=1)
        if s3 != s:
            s = s3; print('proguardFiles wired (release block fallback)')
        else:
            print('warning: could not wire proguardFiles; leaving as-is')
    open(p, 'w', encoding='utf-8').write(s)
PY