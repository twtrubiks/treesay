package com.twtrubiks.treesay

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * 系統的畫面：照片挑選器、存檔與開檔的檔案畫面、系統的「移除動畫」。這些不是 App 畫的，
 * 每個 Android 版本長得不一樣（Android 9 是檔案畫面，Android 11 以後是照片挑選器），只能在模擬器上點一次才知道接不接得上。
 * 不錄影（`record.sh` 在錄影之後、先清掉 App 資料再跑），截圖存成 `system-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class SystemUiTest : UiTest() {
    private val photoName = "treesay-picker.jpg"

    @After
    fun restore() {
        device.executeShellCommand("settings put global animator_duration_scale 1")
        container.motion.save(MotionPref.ON)
    }

    /** 跟網頁版一樣，畫面要不要動由 App 自己的開關決定：系統移除動畫時，選「開」樹照樣在動，選「關」才安靜。 */
    @Test
    fun appMotionSwitchWinsOverSystemRemoveAnimations() {
        device.executeShellCommand("settings put global animator_duration_scale 0")
        container.motion.save(MotionPref.ON)
        launchApp()
        waitFor(By.res("composer_input"), timeout = 30_000)
        pause(2000)
        screenshot("system-01-motion-on")
        assertTrue("系統移除動畫、App 選「開」：樹要在動", moving())

        container.motion.save(MotionPref.OFF)
        launchApp()
        waitFor(By.res("composer_input"), timeout = 30_000)
        pause(2000)
        assertFalse("App 選「關」：畫面要安靜", moving())

        container.motion.save(MotionPref.SYSTEM)
        launchApp()
        waitFor(By.res("composer_input"), timeout = 30_000)
        pause(2000)
        assertFalse("App 選「跟系統」、系統移除動畫：畫面要安靜", moving())
    }

    /** 隔一下拍兩張，App 的畫面（不含狀態列）有沒有變。 */
    private fun moving(): Boolean {
        val first = instrumentation.uiAutomation.takeScreenshot()
        SystemClock.sleep(700)
        val second = instrumentation.uiAutomation.takeScreenshot()
        val top = first.height / 10
        fun app(b: Bitmap) = Bitmap.createBitmap(b, 0, top, b.width, b.height - top * 2)
        return !app(first).sameAs(app(second))
    }

    /**
     * 從系統的照片挑選器挑一張照片丟給樹、從「設定」匯出到 Download、再從系統的檔案畫面把那份檔案匯回來。
     * 匯回來的是手機上已經有的日子，只加不蓋，所以會說「留著原樣」。
     */
    @Test
    fun photoPickerAndBackupFilesScreens() {
        container.motion.save(MotionPref.ON)
        val photo = putPhotoInGallery()
        launchApp()
        find(By.res("photo_button")).click()
        pickPhoto()
        // 選好之後回到首頁，輸入框上方有預覽和拿掉的 ✕
        waitFor(By.text("✕"), timeout = 20_000)
        pause(1500)
        screenshot("system-03-photo-picked")
        find(By.res("composer_input")).text = "從相簿挑的天空"
        hideKeyboard()
        find(By.text("丟給樹")).click()
        waitFor(By.text("從相簿挑的天空"))
        val sent = container.diary.today().day.messages.single()
        val saved = requireNotNull(container.diary.photoFile(requireNotNull(sent.photo) { "照片沒有跟著送出" }))
        assertTrue("存下來的是挑的那張原圖", saved.readBytes().contentEquals(photo))
        pause(1500)
        screenshot("system-04-photo-sent")

        // 匯出：系統的存檔畫面，存到 Download
        // 送出後捲到了最下面，「設定」在最上面
        reveal(By.text("設定"), Direction.UP).click()
        reveal(By.text("匯出日記")).click()
        val fileName = "treesay-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}.json"
        openDownloads()
        pause(1000)
        screenshot("system-05-export-screen")
        find(By.text(Pattern.compile("(?i)save|儲存"))).click()
        waitFor(By.textContains("匯出好了"), timeout = 20_000)
        val exported = device.executeShellCommand("cat /sdcard/Download/$fileName")
        val day = ObjectMapper().readTree(exported)["days"].single()
        assertEquals("從相簿挑的天空", day["messages"][0]["content"].asText())
        assertEquals(sent.photo, day["messages"][0]["photo"].asText())
        pause(1000)

        // 匯入：系統的開檔畫面，挑剛剛那份
        reveal(By.text("匯入")).click()
        openDownloads()
        pause(1000)
        screenshot("system-06-import-screen")
        find(By.text(fileName)).click()
        waitFor(By.textContains("留著原樣"), timeout = 20_000)
        pause(1000)
        screenshot("system-07-imported")
    }

    /** 放一張照片進相簿。Android 10 以後 App 可以直接寫進 MediaStore；Android 9 要權限，交給 shell 複製再掃描。 */
    private fun putPhotoInGallery(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(140, 178, 217))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, photoName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TreeSay")
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            val staged = File(context.getExternalFilesDir(null), photoName).apply { writeBytes(bytes) }
            device.executeShellCommand("cp ${staged.absolutePath} /sdcard/Pictures/$photoName")
            device.executeShellCommand(
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/$photoName",
            )
            staged.delete()
            SystemClock.sleep(2000)
        }
        return bytes
    }

    /**
     * 系統的挑選器：Android 11 以後是照片挑選器（格子、只有縮圖），Android 9、10 是檔案畫面（看得到檔名）。
     * 剛放進去的那張是最新的，排在第一個。
     */
    private fun pickPhoto() {
        val choices: List<BySelector> = listOf(
            By.text(photoName),
            By.res(Pattern.compile(".*:id/icon_thumbnail")),
            By.descStartsWith("Photo taken"),
        )
        val picked = poll(20_000) { choices.firstNotNullOfOrNull { device.findObject(it) } }
        pause(1000)
        screenshot("system-02-picker")
        requireNotNull(picked) { "挑選器裡找不到剛放進相簿的照片" }.click()
    }

    /**
     * 檔案畫面切到 Download（左上角的選單）。存檔畫面一打開可能停在「最近」，那裡不能存。
     * 標題列上可能也寫著 Downloads（被選單蓋住，點不到），選單裡的那個在它下面；選單收起來之後下面的按鈕才點得到。
     */
    private fun openDownloads() {
        val roots = poll(15_000) { device.findObject(By.desc("Show roots")) } ?: return
        roots.click()
        waitFor(By.text("Downloads"))
        pause(800)
        device.findObjects(By.text("Downloads")).maxBy { it.visibleCenter.y }.click()
        waitGone(By.text(Pattern.compile("Save to|Open from")))
        pause(500)
    }
}
