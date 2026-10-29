# 樹說 Android 版

Kotlin + Jetpack Compose 原生 App，在 claude.ai/code 雲端 session 開發。
雲端 VM 沒有模擬器，模擬器操作與錄影交給 GitHub Actions（`.github/workflows/android-record.yml`）。

**根目錄 `CLAUDE.md` 的產品約束全部適用**：反目標（不把一天變成可比較的數字）、結算式種樹、不做逐則回應、
切日 4:00、補種窗口、記憶的界線、「本機」只涵蓋儲存、錯誤用療癒文案……動手前先讀過一遍。
這份只寫 Android 才有的事。

## 和網頁版的關係

- **先各自獨立**：手機自己存 SQLite、自己呼叫 Claude，跟電腦版的資料不互通。未來可能做同步，
  所以下面幾件事現在就要守，以後補不回來：
  - **主鍵用 UUID**，不用遞增整數——兩台裝置各自從 1 編號，同步時會撞號
  - **刪除留 `deleted_at`，不真的刪**——否則同步時另一邊的那筆會被同步回來，刪掉的記憶就復活了（收回訊息也一樣）
  - **時間存當地時間＋時差**——手機會跟著人出國，切日照當地的凌晨 4:00（`TreeDay.effectiveDate`）
  - **匯出 JSON 對齊網頁版 `/api/export` 的格式**——同步做出來前，手機匯出、電腦匯入（只加不蓋）就是手動同步；照片不在 JSON 裡
- **匯出檔兩邊讀得懂，用網頁版真的後端驗證**（`scripts/web-roundtrip.py`，要網頁版的虛擬環境）：
  - 電腦 → 手機：`WebRoundtripTest` 匯入 `app/src/test/resources/web-export/export.json`，那是網頁版真的走一輪
    （改字、收回、補種、跨過 4:00、週報、放下記憶）之後 `/api/export` 的原樣輸出
  - 手機 → 電腦：`WebRoundtripTest` 寫出 `app/build/web-check/android-export.json`（出國的時差、墓碑都在裡面），
    CI 跑完單元測試後用 `check` 匯進網頁版，每一天、每一則話、週報、記憶都要對得上，網頁版的每一頁都要讀得出來
  - 網頁版改了匯出或匯入：在 repo 根目錄跑 `python android/scripts/web-roundtrip.py fixture` 重新產生對照檔，
    再跑一次 `check`——網頁版的變更不會觸發 Android 的 CI
- 同一天兩邊都種了樹、兩邊的週報各自整理記憶，這類衝突留到真的做同步時再設計，現在不處理
- **規則照搬，先搬測試**：`backend/tests/` 的 pytest 案例就是規格，先改寫成 JUnit 再寫實作，規則才不會在改寫時走樣
- prompt 模板、JSON schema、今日問題題庫，之後盡量抽成兩邊共讀的檔案。題庫依「日期 mod 題數」定題，兩邊差一題，同一天就問不同題。
  抽出來之前：
  - `QuestionsTest` 直接讀 `backend/questions.py` 比對 `Questions.ALL`，網頁版加題時 Android 這邊要在同一個位置跟著加
  - `Prompts.kt` 逐字照搬 `backend/prompts.py`。`PromptsTest` 拿 `app/src/test/resources/web-prompts/` 的對照檔比對
    （網頁版用同一份 `cases.json` 產生的 prompt 與 schema），並核對 `prompts.py` 的 hash——網頁版改了 prompt，
    在 repo 根目錄跑 `python3 android/scripts/prompt-fixtures.py` 重新產生對照檔（只用標準函式庫），再把 `Prompts.kt` 改到測試通過

## Android 才有的約束

- **`android:allowBackup="false"`**：開著的話，系統會把整本日記自動備份到 Google 雲端硬碟，跟「本機」的說法對不上。
  換手機靠匯出匯入。真的要改成 `true`，文案要明白寫出日記會備份到 Google 雲端硬碟
- **沒有 key 也能先寫，按下種樹（或打開週報）時才請使用者給 key**：白天丟訊息不需要 AI，一開門就先要 key
  跟零門檻丟訊息的定位相反。給完 key 回到原本的畫面接著種（`TreeSayApp` 的 `pendingPlant`）。
  key 存進 Android Keystore，不能打包進 APK、不能寫進 repo。`ApiKeyStore` 用 Keystore 裡的 AES-GCM 金鑰加密後放
  SharedPreferences；存之前 `ApiKeyCheck` 先拿這把 key 列一次模型（不耗 token）確認打得開門。之後在「設定」換 key 或清除
- **「設定」是工具，入口是首頁右上角的小字**，不進底部的主導覽（首頁、森林、週報、記憶）。
  裡面有 API key、模型與 effort、AI 費用、畫面動態（開／關／跟系統）、匯出與匯入
- **種樹、週報的模型與 effort 在「設定」裡選**（`AiProfiles`，規則照搬網頁版 `ai.resolve_profile`）。
  只給選網頁版 `ai.PRICES` 上的模型，effort 不開 max；改了網頁版的預設或價目表，這裡要一起改
- **呼叫 Claude 用官方 Java SDK**（`com.anthropic:anthropic-java`，Kotlin 直接用）。請求一樣不帶任何工具、
  SDK 自己的重試一樣關掉（`maxRetries(0)`）。SDK 3.x 把 API key 放在 `Authorization: Bearer` 送出，不是 `x-api-key`。
  SDK 自帶 Jackson 的 R8 規則，`app/proguard-rules.pro` 只補了 OpenTelemetry 參照的 AutoValue
- **種樹交給 WorkManager**（`PlantWorker`），使用者切走 App 也會跑完；同一天只會有一份在跑（unique work、KEEP）。
  被系統停掉時 `Diary.plant` 會把鎖解開；整個行程被殺掉的話，`planting` 逾期機制照網頁版（5 分鐘後可以再按）。
  週報不走 WorkManager，在 `AppContainer.scope` 裡跑完並快取，切走頁面不會中斷
- **不做推播提醒**：「今天還沒種樹喔」就是在檢查人
- 照片縮圖與 EXIF 轉正用 `Bitmap` + `ExifInterface`（`PhotoPrep`），規格同網頁版的 `ai.prepare_image`（長邊 1568px）；
  數字與預算的算術在 `Photos`（純 Kotlin，有 JVM 測試）。原圖照存在 `filesDir/photos/<日期>/<檔名>`，跟網頁版的 `photo_path` 同一種寫法；
  HEIC 這類 API 不收的格式在送出前轉成 JPEG
- **畫面要不要動只問 `LocalReduceMotion`**（`MotionStore`：開（預設）／關／跟系統，跟系統看「移除動畫」），
  元件不要自己去問系統。Compose 預設照系統的動畫速度走，「移除動畫」（速度 0）時所有動畫直接停在終點，App 的「開」蓋不過；
  所以 `MainActivity` 自己建 Recomposer（`createLifecycleAwareWindowRecomposer`，其餘照 Compose 預設），
  動畫速度換成 `AppMotionScale`：0 當成 1，0.5 倍、2 倍照系統。跟網頁版一樣，App 的「開」蓋得過作業系統。
  季節與入夜只問 `LocalSky`（整個 App 一個每分鐘的節拍，規則在 `Seasons`，只看時鐘、不接任何使用者資料）
- **轉螢幕不能丟東西**：輸入到一半的字、挑好的照片、種樹畫面、樹回話打到哪裡都存在 `rememberSaveable`

## 程式的分層

- 規則都在純 Kotlin、有 JVM 測試：`Diary`（網頁版 `main.py` 每一支 API 背後的規則）、`Planting`、`Messages`、`Weekly`、
  `AiOutput`、`Prompts`、`Photos`、`Seasons`、`Format`。測試照搬 `backend/tests/` 的案例
- `Diary` 透過 `Sql` 介面存取資料庫：手機上是 `AndroidSql`（`databases/treesay.db`，關掉 WAL，匯入前整個檔案抄一份當備份），
  JVM 測試是 `JdbcSql`（sqlite-jdbc，SQL 跑在真的 SQLite 上）。**Android 9 的 SQLite 是 3.22**：不要用 UPSERT、
  window function、`VACUUM INTO`、`RETURNING` 這些新語法，JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑
- 呼叫 Claude 的是 `Claude`（`TreeAi` 介面，測試換成假的）；畫面在 `*Screen.kt`，整個 App 的導覽在 `TreeSayApp`，
  共用的東西（日記本、key、設定）在 `AppContainer`（`TreeSayApplication.container`）
- 收回訊息、放下記憶只蓋 `deleted_at` 留墓碑，墓碑不留內容。匯入檔裡的照片路徑跑出 photos 資料夾的不認（收回時會刪照片）

## 專案約定

- Gradle 專案根目錄是 `android/`，所有 `./gradlew` 都在這裡跑（雲端 session 從 repo 根目錄開始，先 `cd android`）
- applicationId / namespace：`com.twtrubiks.treesay`（`scripts/ci/record.sh` 依賴這個值，改了要一起改）。
  不要改：applicationId 一變，手機會當成另一個 App，原本的日記就接不回來
- 單一 module `app`，主畫面 `MainActivity`
- **minSdk 28（Android 9）**，compileSdk 37，targetSdk 36，Java / Kotlin target 17
  （`java.time` 在 API 26 就內建，不需要 core library desugaring；新增 API 29+ 的 API 前先跑 `./gradlew lintDebug` 看 NewApi）
  （Compose 1.12 / core 1.19 的 AAR metadata 要求 compileSdk ≥ 37；缺 `platforms;android-37.0` 時 AGP 會自動下載）
- 依賴版本集中在 `gradle/libs.versions.toml`
- release 版開 R8（minify + shrinkResources）。簽名看環境變數：有 `TREESAY_KEYSTORE`、`TREESAY_KEYSTORE_PASSWORD`
  就用發版金鑰（PKCS12，alias `treesay`）；沒有就用這台機器的 debug 金鑰（錄影的 CI、自己 build），能和同一台機器的 debug 版互相覆蓋安裝
- **給別人（包括使用者）裝的 APK 一律從 GitHub Release 來**，不要再把 VM 裡 build 的 APK 傳給使用者：
  VM 的 debug 金鑰（`~/.android/debug.keystore`）每個 session 都不一樣，簽章對不上的 APK 不能直接更新，
  只能移除重裝——**移除就會刪掉手機上的日記和照片**（照片不在匯出檔裡）
  - 發版：`.github/workflows/android-release.yml`。把 `android/release-version` 改成新版本（例如 `2026.10.29`，
    同一天第二版寫 `2026.10.29.2`）推上去，就會 build、簽名、建 `android-v<版本>` 的 tag 並發 Release；
    那個版本已經發過的話跳過（merge 進 main 時不會重發）。雲端 session 的 git 只能推自己的 branch，推 tag 會斷線，
    所以用版本檔；使用者自己也可以推 tag 或手動觸發。使用者要 APK 時就發一版，給他 Release 的連結
  - 金鑰在 repo 的 Secrets：`ANDROID_SIGNING_KEYSTORE`（PKCS12 的 base64）、`ANDROID_SIGNING_PASSWORD`。
    簽完核對憑證 SHA-256 要等於 workflow 裡的 `EXPECTED_CERT`（`317fbf9e…f839`），Secret 被換成別把金鑰就不發
  - 這把金鑰是 2026-10-28 那個 session 的 debug 金鑰（使用者手機上第一批 APK 就是它簽的），換了密碼重新包成 PKCS12，
    檔案備份在使用者手上。弄丟了就再也發不出能直接更新的版本
  - versionCode 是 2026-01-01 起算的分鐘數（workflow 算好用環境變數帶進來），不管從哪個 branch 發都只會變大
  - README 的下載連結是 `releases/latest/download/treesay.apk`。之後網頁版也發 Release 的話，「latest」會指到網頁版那一個，連結要改
- AGP 9.x 使用 built-in Kotlin：**不要**套 `org.jetbrains.kotlin.android`，Compose 要套 `org.jetbrains.kotlin.plugin.compose`
- 版本起點（2026-10 查的最新穩定版）：AGP 9.4.1、Kotlin / Compose compiler plugin 2.4.21、Gradle wrapper 9.8.2、
  Compose BOM 2026.09.00、activity-compose 1.13.0、core-ktx 1.19.1、lifecycle-runtime-ktx 2.11.0、
  androidx.test runner 1.7.0、core 1.7.0、ext:junit 1.3.0、uiautomator 2.4.0、anthropic-java 3.3.0、
  work-runtime-ktx 2.12.0、exifinterface 1.4.2、sqlite-jdbc 3.53.4.0（只給 JVM 測試）。
  JSON 用 anthropic-java 帶進來的 Jackson（tree model，不靠反射，R8 不用另外加規則）。
  mockwebserver 刻意停在 4.12.0：要跟 anthropic-java 帶進來的 OkHttp 4.x 同一個大版本，lint 的 NewerVersionAvailable 不用理。
  若 build 報版本相容問題，以錯誤訊息為準調整，並更新這段
- 色票取自網頁版 `frontend/src/assets/main.css`（oklch 換算成 sRGB），不要另外配色

## 雲端 VM 環境

- Android SDK 由環境的 setup script（`android/scripts/cloud-setup.sh`）裝在 `/opt/android-sdk`，`ANDROID_HOME` 由環境變數提供
- SDK 不存在時先看 `/var/log/android-sdk-setup.log`
- 新 session 第一次 build 可能遇到 Maven Central 回 `429 Too Many Requests`（共用出口 IP 被限流），
  隔 20 秒左右重跑 `./gradlew` 即可，依賴進快取後就不會再遇到；不要為此改 repository 設定（CI 不受影響）。
  錯誤訊息通常不會直接寫 429，而是 `Plugin [id: '...'] was not found in any of the following sources`、
  `Could not resolve <group>:<artifact>:<version>` 或 `Configuration cache state could not be cached`，
  加 `--info` 才看得到底下的 `HTTP 429`。新 session 第一次 build 看到這幾種，先重跑，不要去改版本

## 在 VM 內驗證（每次 push 前）

```bash
cd android
./gradlew assembleDebug assembleRelease testDebugUnitTest assembleDebugAndroidTest
```

`assembleRelease` 會跑 R8，可以先抓到缺 class 之類的錯誤；執行時才會出現的問題要看 CI 的 release 冒煙測試。
和 Android 無關的邏輯（切日、補種窗口、資料清洗）寫成純 Kotlin，放在 JVM 單元測試裡測，幾秒就有結果。
CI 錄影前也會跑 `testDebugUnitTest`，沒過就不會錄影（失敗原因在 `gradle-build.txt`）。

## 錄影測試：DemoRecordingTest

`app/src/androidTest/java/com/twtrubiks/treesay/DemoRecordingTest.kt`，CI 只錄這個 class，
`record.sh` 在它執行期間用 `screenrecord` 錄整個螢幕。找元件、輪詢、截圖這些跟 `SystemUiTest` 共用，放在 `UiTest`。

- 用 UiAutomator 操作（真實時間驅動，動畫會正常播放），不要用 Compose test rule
- **不要用 `ActivityScenario` 開 App**，用 `context.startActivity(Intent(...))`：樹的風與呼吸是無限動畫，主執行緒每一格都排著畫面更新，
  `ActivityScenario` 與 `Instrumentation.waitForIdleSync()` 等主執行緒閒下來會永遠等不到，測試卡到 job 逾時。
  同理，UiAutomator 的 `waitForIdleTimeout` 設成 0（`Configurator`），找元件用測試裡的 `waitFor` 每 0.1 秒輪詢，
  不要用 `device.wait(Until...)`（一秒才看一次）或 `device.waitForIdle()`，否則每一步都白等。
  元件找到之後畫面又重組了（例如打字機打完字），按之前要重新找一次，否則會拿到 `StaleObjectException`。
  動畫的值只在畫的時候讀（State 傳進 Canvas／graphicsLayer），不要在組畫面時讀，否則每秒重組 60 次
- 一個 `@Test` 跑完整的展示流程，每個操作之間停 1～1.5 秒讓影片看得清楚
- 全長控制在 150 秒內（`screenrecord` 上限 180 秒）
- 用 `By.text(...)` 找元件；要用 testTag 時，在 root 加 `Modifier.semantics { testTagsAsResourceId = true }` 再用 `By.res(...)`
- 要確認細節的畫面用測試裡的 `screenshot("名稱")` 另存截圖
- CI 沒有 API key，種樹這類要呼叫 Claude 的流程不能真的打 API：
  - 第一次要 key 時輸入一把假 key 讓真的 Claude API 回 401，順便驗證 SDK 在模擬器上連得到、解讀得了錯誤
  - 之後的種樹與週報打到測試在模擬器上開的假 Claude 伺服器（MockWebServer，回應照 Messages API 的格式）：
    測試設定 `AppContainer.claudeBaseUrlForTests`，只有 debug 版認這個值；`app/src/debug/` 的 network security config
    只對 127.0.0.1 放行明碼連線。請求一樣經過真的 SDK 序列化、回應由 SDK 解析
  - 在測試裡直接 `ApiKeyStore.save` 一把假 key（只會送到假伺服器），結束前清掉
  - 過去一個月的日記、記憶用 `Diary.import` 先放進去，森林、週報、記憶才有東西看；日期都相對今天算
- 鍵盤開著才按返回鍵收起（`hideKeyboard()`）——沒開的時候按返回鍵會離開那一頁

## 模擬器上的測試：DeviceTest

`app/src/androidTest/java/com/twtrubiks/treesay/DeviceTest.kt`，`record.sh` 在錄影前先跑、不錄影，輸出在 `device-test.txt`。
只放模擬器才測得到的事：`Diary` 在 Android 9 的 SQLite 上跑一輪（訊息、種樹、週報、記憶、匯出匯入、備份），
`PhotoPrep` 用真的 Bitmap 與 ExifInterface 縮圖轉正（直拍的 JPEG、手機拍的 HEIC）。
`androidTest/assets/portrait.heic` 是 pillow-heif 產生的：方向放在 HEIF 的 irot 裡（跟手機拍的一樣），
上半紅下半藍，轉正之後紅色要在右邊——只看長寬的話，轉錯方向也會過。

## 系統畫面的測試：SystemUiTest

`app/src/androidTest/java/com/twtrubiks/treesay/SystemUiTest.kt`，不錄影。錄影留下的今天已經種下了、送不了新的話，
所以 `record.sh` 在錄影結束、截圖 pull 回來之後先 `pm clear` 再跑，輸出在 `system-ui-test.txt`、`logcat-system-ui.txt`，截圖是 `system-*.png`。
只放不是 App 畫的畫面，每個 Android 版本長得不一樣，只能點一次才知道接不接得上：
- 系統的照片挑選器：Android 9、10 是檔案畫面（找得到檔名），Android 11 以後是照片挑選器（只有縮圖，挑第一張）。
  照片先放進相簿：Android 10 以後 App 直接寫 MediaStore，Android 9 要權限，交給 shell 複製再廣播掃描
- 存檔與開檔的檔案畫面：匯出到 Download（先從左上角的選單切到 Downloads，停在「最近」的話不能存），
  用 shell 讀出檔案內容核對，再從開檔畫面挑同一份匯回來
- 系統「移除動畫」：隔 0.7 秒拍兩張比對，App 選「開」要在動，選「關」或「跟系統」要靜止。結束時把動畫速度改回 1

## 錄影流程（GitHub Actions）

1. push 到目前的 branch；改到 `android/app/**`、`android/gradle/**`、`android/*.gradle.kts`、`android/gradle.properties` 會自動觸發。
   網頁版（`backend/`、`frontend/`）的變更不會觸發。
   只改 `android/scripts/ci/**` 或 workflow 時要手動觸發：
   `gh api -X POST 'repos/{owner}/{repo}/actions/workflows/android-record.yml/dispatches' -f ref=<branch>`
   錄影一律用 API 28（最低支援的 Android 9）的模擬器，push 和手動觸發都是；不要另外再跑其他 API level。
   使用者明確要求其他版本時才在手動觸發加上 `-f "inputs[api-level]=<level>"`（例如 Android 14 是 34），
   不同 API level 會同時跑，但都寫到同一個 `android-media` branch，用 `info.txt` 的 `api level` 分辨
   （後跑完的會蓋掉先跑完的：每 15 秒 fetch 一次，看到哪個 level 就先 `git archive` 存起來）。
   2026-10 在 API 28、34、36 都跑過一輪（DeviceTest、錄影、SystemUiTest、release 冒煙測試全過），三個都有 `default` 映像檔。
   只改文件（`CLAUDE.md`、`docs/**`）不會觸發；萬一只改文件卻觸發了 build 或錄影，不用問使用者，直接取消：
   `gh api -X POST 'repos/{owner}/{repo}/actions/runs/<id>/cancel'`
2. 查進度：`gh api "repos/{owner}/{repo}/actions/runs?branch=<branch>&per_page=1" --jq '.workflow_runs[0] | {id, status, conclusion, head_sha}'`
   （雲端 session 的 GitHub proxy 擋 GraphQL，`gh pr` / `gh issue` 不能用，一律用 `gh api` 打 REST）
3. 看哪一步失敗：`gh api 'repos/{owner}/{repo}/actions/runs/<id>/jobs' --jq '.jobs[] | {name, conclusion, steps: [.steps[] | select(.conclusion=="failure") | .name]}'`
4. 不論成功或失敗，run 結束後都讀 `android-media` branch（每次會被覆蓋，先核對 `info.txt` 的 commit 是不是自己這次的）：

   ```bash
   git fetch origin android-media
   rm -rf /tmp/media && mkdir -p /tmp/media
   git archive origin/android-media | tar -x -C /tmp/media
   ```

   - `/tmp/media/frames/*.png`：每 2 秒一張影格，用 Read 看圖確認畫面
   - `/tmp/media/screenshots/*.png`：測試在關鍵畫面存的截圖（半解析度）
   - `/tmp/media/screenshots/system-*.png`：`SystemUiTest` 的截圖（挑選器、檔案畫面、移除動畫時的首頁）
   - `/tmp/media/screenshots/release-*.png`：錄影結束後覆蓋安裝 release 版拍的截圖。`release-1-launch.png` 是剛打開的首頁，
     `release-2-key-check.png` 是用 `uiautomator dump` 找元件：丟一句話、按種樹、在輸入 key 的畫面輸入假 key 之後等到 401 文案。
     樹一直在動的話 dump 等不到畫面靜止（`could not get idle state`）。App 的「開」蓋得過系統的移除動畫，
     所以趁 debug 版還在時 `pm clear`、再用 `run-as` 把「畫面動態」設成「關」（`shared_prefs/ui.xml`），覆蓋安裝 release 版時資料會留著；
     系統的轉場與鍵盤動畫也設成 0。
     輸入框拿到焦點後游標會閃、鍵盤會打開，一樣等不到，所以每一頁先在沒有人拿到焦點時 dump 一次記下座標，打完字收起鍵盤再按
     （R8 壓過的 SDK 只有真的打 API 才跑得到，等不到文案 job 會失敗）；
     `release-smoke.txt` 是啟動時間，`logcat-release.txt` 有 `FATAL EXCEPTION` 時 job 會失敗
   - `/tmp/media/info.txt`：branch、commit、api level、run 網址、job status
   - `/tmp/media/gradle-build.txt`：CI 的 Gradle build 輸出
   - `/tmp/media/web-roundtrip.txt`：手機匯出檔匯進網頁版後端的結果
   - `/tmp/media/device-test.txt`：DeviceTest 的輸出
   - `/tmp/media/instrument.txt`、`logcat.txt`：DemoRecordingTest 的輸出與 log
   - `/tmp/media/system-ui-test.txt`、`logcat-system-ui.txt`：SystemUiTest 的輸出與 log
   - `/tmp/media/demo.mp4`：壓縮後的影片（給使用者看）

看完 frames 確認畫面正常後，把 `/tmp/media/demo.mp4` 複製到 session 的 scratchpad 目錄，
用 SendUserFile（`display: render`）直接傳給使用者，讓使用者不用 pull 就能播放。
傳之前先核對 `info.txt` 的 commit 是這次的。

## 環境設定

claude.ai/code 的環境設定步驟寫在 `android/docs/cloud-setup.md`，改了 `android/scripts/cloud-setup.sh` 或網路設定要一起更新。
