#!/bin/bash
# Claude 雲端環境的 setup script：安裝 Android SDK。
# 雲端 VM 只預裝 OpenJDK 21 與 Gradle，沒有 Android SDK。
#
# 使用方式：在 claude.ai/code 的環境設定 > Setup script > Choose file 選這個檔案。
# 需要的網路存取：Custom，加入 dl.google.com，並勾選
#   「Also include default list of common package managers」（或直接用 Full）。
# 還要在環境變數加入：ANDROID_HOME=/opt/android-sdk
#
# setup script 失敗（exit 非 0）會讓整個 session 起不來，
# 所以這裡失敗只寫 log，讓 Claude 進 session 後能讀 log 排查。

ANDROID_HOME=/opt/android-sdk
CMDLINE_TOOLS_ZIP=commandlinetools-linux-15859902_latest.zip
LOG=/var/log/android-sdk-setup.log

install_sdk() {
  set -euo pipefail

  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    command -v unzip >/dev/null || (apt-get update -qq && apt-get install -y -qq unzip)
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    curl -sSfL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
    rm -rf /tmp/cmdline-tools
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools
    mv /tmp/cmdline-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip
  fi

  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$sdkmanager" --licenses >/dev/null || true
  "$sdkmanager" --install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
}

# 不能寫成 if (install_sdk)：在 if 條件裡 set -e 會失效
(install_sdk) >>"$LOG" 2>&1
if [ $? -eq 0 ]; then
  echo "Android SDK ready: $ANDROID_HOME"
else
  echo "Android SDK 安裝失敗，詳見 $LOG"
fi
exit 0
