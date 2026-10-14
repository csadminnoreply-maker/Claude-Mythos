#!/data/data/com.termux/files/usr/bin/bash
# Build otomatis: cari Android SDK, tulis local.properties, build, salin APK ke Download.
cd "$(dirname "$0")" || exit 1

ok() { [ -d "$1/platforms" ] && [ -d "$1/build-tools" ]; }
SDK=""
for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/android_sdk" "$HOME/sdk" "$PREFIX/share/android-sdk"; do
  [ -n "$d" ] && ok "$d" && SDK="$d" && break
done
if [ -z "$SDK" ]; then
  for f in "$HOME"/agentapp*/agentapp/local.properties "$HOME"/kita/local.properties "$HOME"/*/local.properties; do
    [ -f "$f" ] || continue
    d=$(grep '^sdk.dir=' "$f" | head -1 | cut -d= -f2-)
    [ -n "$d" ] && ok "$d" && SDK="$d" && break
  done
fi
if [ -z "$SDK" ]; then
  for p in $(find "$HOME" -maxdepth 4 -type d -name platforms 2>/dev/null); do
    d=$(dirname "$p"); ok "$d" && SDK="$d" && break
  done
fi
if [ -z "$SDK" ]; then
  echo "SDK Android tidak ditemukan. Kirim hasil: ls ~ dan echo \$ANDROID_HOME"
  exit 1
fi

echo "SDK: $SDK"
echo "sdk.dir=$SDK" > local.properties
gradle assembleDebug || exit 1

APK=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$APK" ]; then
  mkdir -p "$HOME/storage/downloads"
  cp "$APK" "$HOME/storage/downloads/kita-agent.apk" && echo "APK siap: Download/kita-agent.apk"
else
  echo "APK tidak ditemukan"
fi
