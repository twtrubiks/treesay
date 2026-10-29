package com.twtrubiks.treesay

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Before
import java.io.File

/**
 * 用 UiAutomator 操作整個 App 的測試共用的東西（[DemoRecordingTest]、[SystemUiTest]）。
 *
 * 樹一直在風裡動，主執行緒每一格都排著畫面更新，永遠閒不下來：不用 ActivityScenario（會等主執行緒閒下來），
 * UiAutomator 也不等畫面「靜止」，找元件靠 [waitFor] 自己每 0.1 秒看一次。
 */
abstract class UiTest {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val context = instrumentation.targetContext
    protected val container = context.container

    // record.sh 會把這個資料夾 pull 回來，放進 media branch 的 screenshots/
    private val screenshotDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepareUi() {
        // UiAutomator 每找一次元件都先等畫面「靜止」，預設最多等 10 秒。永遠等不到，每一步都白等
        Configurator.getInstance().waitForIdleTimeout = 0
        screenshotDir.mkdirs()
    }

    /** 直接開 App，畫面交給 UiAutomator 等（不用 ActivityScenario，見上面）。 */
    protected fun launchApp() {
        dismissAnrDialog()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    protected fun find(selector: BySelector): UiObject2 = waitFor(selector)

    // device.wait(Until...) 一秒才看一次，每一步都多等快一秒；自己每 0.1 秒看一次
    protected fun waitFor(selector: BySelector, timeout: Long = 10_000): UiObject2 {
        poll(timeout) { device.findObject(selector) }?.let { return it }
        // 找不到時可能被系統 ANR 對話框擋住，關掉再找一次
        dismissAnrDialog()
        return requireNotNull(poll(timeout) { device.findObject(selector) }) { "畫面上找不到 $selector" }
    }

    protected fun <T : Any> poll(timeout: Long, block: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeout
        while (true) {
            block()?.let { return it }
            if (SystemClock.uptimeMillis() > end) return null
            SystemClock.sleep(100)
        }
    }

    /** 往 [direction] 捲，直到 [selector] 整個出現在畫面上。 */
    protected fun reveal(selector: BySelector, direction: Direction = Direction.DOWN): UiObject2 {
        repeat(8) {
            device.findObject(selector)?.takeIf { it.visibleBounds.height() > 0 }?.let { return it }
            find(By.scrollable(true)).scroll(direction, 0.5f)
            SystemClock.sleep(300)
        }
        return find(selector)
    }

    protected fun waitUntil(timeout: Long = 10_000, condition: () -> Boolean) {
        checkNotNull(poll(timeout) { if (condition()) Unit else null }) { "等不到畫面變化" }
    }

    /** 鍵盤開著才按返回鍵收起來；沒開的時候按返回鍵會離開這一頁。 */
    protected fun hideKeyboard() {
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            SystemClock.sleep(500)
        }
    }

    protected fun waitGone(selector: BySelector) {
        poll(10_000) { if (device.findObject(selector) == null) Unit else null }
    }

    // 系統程式 ANR 時按「Close app」讓它重開；按 Wait 的話 5 秒後會再跳出來
    protected fun dismissAnrDialog() {
        device.findObject(By.res("android", "aerr_close"))?.click()
    }

    protected fun screenshot(name: String) {
        device.takeScreenshot(File(screenshotDir, "$name.png"), 0.5f, 90)
    }

    // 只是讓影片看得清楚的停頓；畫面一直在動，不等 idle
    protected fun pause(ms: Long = 800) {
        SystemClock.sleep(ms)
    }
}
