#!/usr/bin/env bash
# M2: flutter create 生成 android 骨架后，向 AndroidManifest 注入所需权限。
set -euo pipefail
MANIFEST="android/app/src/main/AndroidManifest.xml"
if [ ! -f "$MANIFEST" ]; then
  echo "manifest not found: $MANIFEST"
  exit 1
fi

# 在 <manifest 第一个 <uses-permission 之前插入（无则放在 application 前）。
PERMS=$(cat <<'PERMS'
    <uses-permission android:name="android.permission.INTERNET" />
PERMS
)

if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
  echo "INTERNET already present"
else
  # 插入到 <application 之前
  python3 - "$MANIFEST" <<'PY'
import sys,re
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
perm='    <uses-permission android:name="android.permission.INTERNET" />\n'
if '<application' in s:
    s=s.replace('<application', perm+'    <application',1)
open(p,'w',encoding='utf-8').write(s)
PY
  echo "INTERNET added"
fi

# 展示结果
echo "---- manifest head ----"
sed -n '1,12p' "$MANIFEST"

# 前台服务(flutter_foreground_task): 权限 + service 声明(Android 13/14 类型要求)
python3 - "$MANIFEST" <<'PY'
import sys,re
p=sys.argv[1]
s=open(p,encoding='utf-8').read()

perms='''    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
'''
if 'FOREGROUND_SERVICE' not in s:
    s=s.replace('<application', perms+'    <application',1)

srv='''        <service
            android:name="com.pravera.flutter_foreground_task.service.ForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
'''
if 'ForegroundService' not in s:
    s=re.sub(r'(<application\b[^>]*>)', lambda m: m.group(1)+'\n'+srv, s, count=1)

open(p,'w',encoding='utf-8').write(s)
print("foreground service injected")
PY

# 桌面显示名: flutter create 默认 label 是工程名, 这里改为产品名「文件流」
python3 - "$MANIFEST" <<'PY'
import sys,re
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
# <application ... android:label="xxx" ...> → 改为 文件流
s=re.sub(r'android:label="[^"]*"', 'android:label="文件流"', s, count=1)
open(p,'w',encoding='utf-8').write(s)
print("app label set to 文件流")
PY

# 存储访问: Rust 引擎直接 std::fs 读 /storage/emulated/0, 分区存储下必须声明权限。
# - READ 到 API32、WRITE 到 API29(legacy 模式); API30+ 需 MANAGE_EXTERNAL_STORAGE(用户在系统设置授权).
# - requestLegacyExternalStorage 令 API<=29 免运行时授权即可列目录/读文件(修复「看不到文件」)。
python3 - "$MANIFEST" <<'PY'
import sys
p=sys.argv[1]
s=open(p,encoding='utf-8').read()
perms='''    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
'''
add=''
for line in perms.splitlines():
    if line.strip() and line.strip() not in s:
        add += line+'\n'
if add and '<application' in s:
    s=s.replace('<application', add+'    <application',1)
if 'requestLegacyExternalStorage' not in s:
    s=s.replace('android:label="文件流"','android:label="文件流" android:requestLegacyExternalStorage="true"',1)
open(p,'w',encoding='utf-8').write(s)
print("storage permissions + legacy injected")
PY