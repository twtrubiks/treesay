#!/usr/bin/env bash
# 在模擬器上先跑 DeviceTest（Android 9 的 SQLite、Bitmap，不錄影），再一邊錄影一邊跑 DemoRecordingTest，
# 錄完清掉 App 資料跑 SystemUiTest（系統的照片挑選器、檔案畫面、移除動畫，不錄影）。
# 輸出到 $1（預設 out/）：device-test.txt、demo.raw.mp4、instrument.txt、logcat.txt、system-ui-test.txt、screenshots/，
# 以及 release 版冒煙測試的 release-smoke.txt、logcat-release.txt、screenshots/release-*.png
# 由 .github/workflows/android-record.yml 在 android-emulator-runner 裡呼叫，工作目錄是 android/。
set -uo pipefail

OUT=${1:-out}
APP_ID=com.twtrubiks.treesay
TEST_CLASS=$APP_ID.DemoRecordingTest
DEVICE_TEST_CLASS=$APP_ID.DeviceTest
SYSTEM_UI_TEST_CLASS=$APP_ID.SystemUiTest
mkdir -p "$OUT"

adb install -r -t app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1

# 喚醒並解鎖螢幕、在影片裡顯示點擊位置
# （不用 keyevent 82：MENU 鍵在桌面會叫出長按選單，被錄進影片開頭）
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put system show_touches 1 || true

# 剛開機時系統服務還很忙：System UI 會 ANR、測試要幾十秒才啟動。
# 等 1 分鐘 load average 降到 2.5 以下再開始，最多等 3 分鐘。
for _ in $(seq 1 36); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1)
  echo "loadavg: $load"
  awk -v l="$load" 'BEGIN { exit !(l < 2.5) }' && break
  sleep 5
done

# 開機時卡死的系統程式（Quickstep、System UI 等）會一直跳 ANR 對話框，按 Wait 5 秒後又會再跳，
# 對話框在的時候 UiAutomator 也讀不到 App 畫面。錄影前直接重新啟動它們（系統會自動重開）。
anr_pkgs=$(adb shell dumpsys window windows | grep -o 'Application Not Responding: [A-Za-z0-9_.]*' | sed 's/.*: //' | sort -u)
for pkg in $anr_pkgs; do
  echo "重新啟動 ANR 的程式：$pkg"
  adb shell am force-stop "$pkg" || true
done
if [ -n "$anr_pkgs" ]; then
  sleep 5
fi

# 只有真機才測得到的事：Android 9 內建的 SQLite 跑不跑得動 Diary 的 SQL、Bitmap 縮圖轉正。不錄影
# 兩支測試都設時間上限：卡住的話早點停下來，後面的步驟還能把 log 與截圖收回來，不用等到整個 job 逾時
timeout 300 adb shell am instrument -w -e class "$DEVICE_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/device-test.txt"

adb logcat -c || true

# pixel_7_pro 是 1440x3120，直接錄可能超過模擬器編碼器上限，所以錄一半尺寸
# screenrecord 單次最長 180 秒
adb shell screenrecord --size 720x1560 --bit-rate 8000000 --time-limit 180 /sdcard/demo.mp4 &
REC_PID=$!
sleep 2

timeout 600 adb shell am instrument -w -e class "$TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.txt"

sleep 1
adb shell 'pkill -INT screenrecord 2>/dev/null || pkill -l 2 screenrecord' || true
wait "$REC_PID" || true
# 等 screenrecord 把檔案寫完
sleep 2

adb pull /sdcard/demo.mp4 "$OUT/demo.raw.mp4" || true
# DemoRecordingTest 在關鍵畫面存的截圖
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt" || true

# 系統的畫面（照片挑選器、存檔與開檔的檔案畫面、移除動畫）：不錄影。
# 錄影留下的今天已經種下了，送不了新的話，先清掉 App 資料（截圖已經 pull 回來了）
adb shell pm clear "$APP_ID" || true
adb logcat -c || true
timeout 300 adb shell am instrument -w -e class "$SYSTEM_UI_TEST_CLASS" \
  "$APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/system-ui-test.txt"
adb pull "/sdcard/Android/data/$APP_ID/files/screenshots" "$OUT/" || true
adb logcat -d > "$OUT/logcat-system-ui.txt" || true

# 給 release 版冒煙測試用：沒有 UiAutomator 測試 APK 可用，靠 uiautomator dump 找元件
# 剛跑完錄影測試時，前一個 UiAutomation 連線可能還沒放掉，dump 會回 null root node：隔兩秒再試
ui_dump() {
  local out
  for _ in 1 2 3 4 5; do
    out=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1)
    case "$out" in
      *dumped*) adb shell cat /sdcard/ui.xml; return 0 ;;
    esac
    echo "uiautomator dump：$out" >&2
    sleep 2
  done
  return 1
}

# 鍵盤開著才按返回鍵收起來；沒開的時候按返回鍵會離開 App
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  fi
}

# 從一份 dump（$1）裡找第一個符合的元件（$2 是 dump 裡的屬性，例如 'resource-id="api_key_input"'），印出中心座標
center() {
  local xy
  xy=$(printf '%s' "$1" | tr '>' '\n' | grep -F "$2" | head -1 \
    | sed -nE 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/p' \
    | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
  if [ -z "$xy" ]; then
    echo "畫面上找不到 $2" >&2
    return 1
  fi
  echo "$xy"
}

status=0
# 測試失敗時 am instrument 的 exit code 仍是 0，要看輸出判斷
if ! grep -q '^OK (' "$OUT/device-test.txt"; then
  echo "DeviceTest 沒有通過，詳見 $OUT/device-test.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/instrument.txt"; then
  echo "DemoRecordingTest 沒有通過，詳見 $OUT/instrument.txt 與 $OUT/logcat.txt"
  status=1
fi
if ! grep -q '^OK (' "$OUT/system-ui-test.txt"; then
  echo "SystemUiTest 沒有通過，詳見 $OUT/system-ui-test.txt、$OUT/logcat-system-ui.txt 與 screenshots/system-*.png"
  status=1
fi

# release 版冒煙測試：R8 壓縮過的程式在 debug 版測不到，覆蓋安裝後打開 App 截一張圖，確認沒有閃退。
# 兩個版本都用 debug 金鑰簽，可以直接覆蓋。先清掉 App 資料，從第一次打開的樣子開始
RELEASE_APK=app/build/outputs/apk/release/app-release.apk
if [ -f "$RELEASE_APK" ]; then
  mkdir -p "$OUT/screenshots"
  # 首頁的樹一直在風裡動，uiautomator dump 等不到畫面靜止會直接放棄（could not get idle state）。
  # App 的「開」蓋得過系統的移除動畫，所以趁 debug 版還在、run-as 進得去，清掉資料後把「畫面動態」設成「關」；
  # 覆蓋安裝 release 版時資料會留著
  # （從標準輸入直接寫進去，不必讓 App 的身分去讀 shell 放的檔案）
  adb shell pm clear "$APP_ID" || true
  printf '%s\n' '<map><string name="motion">off</string></map>' \
    | timeout 30 adb shell "run-as $APP_ID sh -c 'mkdir -p shared_prefs && cat > shared_prefs/ui.xml'"
  adb shell "run-as $APP_ID cat shared_prefs/ui.xml" | tee "$OUT/release-prefs.txt"
  adb install -r "$RELEASE_APK" || exit 1
  # 系統的轉場與鍵盤動畫也停掉，dump 少等一點。錄影已經結束，不影響影片
  for scale in animator_duration_scale window_animation_scale transition_animation_scale; do
    adb shell settings put global "$scale" 0 || true
  done
  adb logcat -c || true
  adb shell am start -W -n "$APP_ID/.MainActivity" | tee "$OUT/release-smoke.txt"
  sleep 3
  adb exec-out screencap -p > "$OUT/screenshots/release-1-launch.png"

  # 沒有 key 也能先寫：丟一句話（壓過的資料庫程式跑一次），按種樹才請使用者給 key。
  # 壓過的 SDK（OkHttp + Jackson）要真的打一次 API 才跑得到：輸入假 key，等 Claude API 回 401 的文案
  # 輸入框一拿到焦點，游標就開始閃、鍵盤也打開，之後的 dump 都等不到畫面靜止。
  # 所以每一頁先在還沒有人拿到焦點時 dump 一次、記下座標；打完字收起鍵盤，版面就回到原本的位置
  key_checked=0
  if today=$(ui_dump) \
    && composer=$(center "$today" 'resource-id="composer_input"') \
    && send=$(center "$today" 'text="丟給樹"') \
    && plant=$(center "$today" 'text="種下今天的樹') \
    && adb shell input tap $composer && adb shell input text release-smoke && hide_keyboard \
    && adb shell input tap $send && sleep 2 && adb shell input tap $plant && sleep 2 \
    && key_screen=$(ui_dump) \
    && key_input=$(center "$key_screen" 'resource-id="api_key_input"') \
    && connect=$(center "$key_screen" 'text="連上森林"') \
    && adb shell input tap $key_input && adb shell input text sk-ant-release-smoke \
    && adb shell input tap $connect; then
    for _ in $(seq 1 15); do
      sleep 2
      if ui_dump | grep -q '打不開門'; then
        key_checked=1
        break
      fi
    done
  fi
  adb exec-out screencap -p > "$OUT/screenshots/release-2-key-check.png"

  adb logcat -d > "$OUT/logcat-release.txt" || true
  if grep -q 'FATAL EXCEPTION' "$OUT/logcat-release.txt"; then
    echo "release 版閃退，詳見 $OUT/logcat-release.txt"
    status=1
  fi
  if [ "$key_checked" -ne 1 ]; then
    echo "release 版驗證 API key 沒等到 401 的文案，詳見 $OUT/screenshots/release-2-key-check.png"
    status=1
  fi
fi

exit $status
