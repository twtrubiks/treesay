package com.twtrubiks.treesay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/**
 * 只有真機才測得到的事：Android 9 內建的 SQLite（3.22，比 JVM 測試用的舊很多）跑不跑得動 [Diary] 的每一句 SQL、
 * Bitmap 與 ExifInterface 縮圖轉正對不對。`scripts/ci/record.sh` 在錄影前先跑這個 class，不錄進影片。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("device-test.db", "device-test-import.db")
    private val photos = File(context.cacheDir, "device-test-photos")

    @After
    fun cleanUp() {
        names.forEach { context.deleteDatabase(it) }
        context.getDatabasePath(names[0]).parentFile
            ?.listFiles { f -> f.name.startsWith("device-test") && f.name.contains(".bak-") }
            ?.forEach { it.delete() }
        photos.deleteRecursively()
    }

    private fun diary(name: String, now: () -> OffsetDateTime, reply: (Purpose) -> ObjectNode): Diary {
        context.deleteDatabase(name)
        return Diary(
            sql = AndroidSql(context, name),
            photosDir = photos,
            ai = { _, _, _, purpose -> reply(purpose) },
            prepareImage = PhotoPrep::prepare,
            now = now,
        )
    }

    private val mapper = ObjectMapper()
    private fun json(text: String) = mapper.readTree(text) as ObjectNode

    @Test
    fun diaryRunsOnTheDevicesSqlite() = runBlocking {
        var now = OffsetDateTime.parse("2026-07-21T12:00:00+08:00")
        val diary = diary(names[0], { now }) { purpose ->
            if (purpose == Purpose.PLANT) {
                json("""{"diary":"日記","emotion":"calm","tree_reply":"回覆","memory":["在準備搬家"],"keywords":["搬家"]}""")
            } else {
                json("""{"good_things":["好事"],"bad_things":[],"keywords":["家"],"advice":"慢慢來","memories":["正在搬家"]}""")
            }
        }
        val first = diary.send("說錯了")
        diary.edit(first.id, "改好了")
        diary.retract(diary.send("收回").id)
        diary.send("", NewPhoto(jpeg(800, 600), ".jpg"))
        assertEquals(LocalDate.of(2026, 7, 21), diary.today().day.date)
        assertEquals(DayStatus.PLANTED, diary.plant(LocalDate.of(2026, 7, 21)).status)
        diary.recordUsage(AiUsage(Purpose.PLANT, "claude-opus-5-5", 1000, 500))

        now = OffsetDateTime.parse("2026-07-22T12:00:00+08:00")
        diary.send("隔天")
        now = OffsetDateTime.parse("2026-07-23T12:00:00+08:00")
        assertEquals(LocalDate.of(2026, 7, 22), diary.today().backfillCandidate)

        now = OffsetDateTime.parse("2026-07-28T12:00:00+08:00")
        diary.send("隔週")
        assertEquals(
            listOf(LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 28)),
            diary.month(YearMonth.of(2026, 7)).map { it.date },
        )
        val report = diary.report(LocalDate.of(2026, 7, 20)) as WeeklyResult.Ready
        assertEquals("慢慢來", report.report.advice)
        assertEquals(listOf("正在搬家"), diary.memories().map { it.content })
        assertEquals(1, diary.usage().months.size)

        val other = diary(names[1], { now }) { error("匯入不呼叫 AI") }
        other.send("手機上原本就有的")
        val result = other.import(diary.export())
        assertEquals(2, result.daysAdded)
        assertEquals(1, result.daysSkipped)
        assertTrue(File(requireNotNull(result.backup)).isFile)
        assertEquals("日記", other.day(LocalDate.of(2026, 7, 21))?.diary)
    }

    /** 直拍的照片：像素是橫的、方向記在 EXIF。送給樹看的那一份要轉正、縮到長邊 1568px 以內。 */
    @Test
    fun portraitPhotoIsTurnedUprightAndScaledDown() {
        val file = File(context.cacheDir, "portrait.jpg")
        file.writeBytes(jpeg(4000, 3000, topHalfRed = true))
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val prepared = requireNotNull(PhotoPrep.prepare(file.readBytes()))
        file.delete()
        assertEquals("image/jpeg", prepared.mediaType)
        val bitmap = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
        assertTrue("轉正之後是直的：${bitmap.width}x${bitmap.height}", bitmap.height > bitmap.width)
        assertTrue(maxOf(bitmap.width, bitmap.height) <= Photos.MAX_EDGE)
        assertTrue(bitmap.width.toLong() * bitmap.height <= Photos.MAX_PIXELS)
        // 順時針轉 90 度：原本在上面的紅色到了右邊
        assertTrue(Color.red(bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)) > 150)
        assertTrue(Color.red(bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)) < 100)
    }

    /**
     * 手機拍的 HEIC：API 不收，要轉成 JPEG；方向跟手機拍的一樣記在 HEIF 的 irot 裡（不是 EXIF），像素是橫的。
     * `assets/portrait.heic` 用 pillow-heif 產生：400x300、上半紅下半藍、要順時針轉 90 度才正，
     * 轉正之後紅色在右邊——只看長寬的話，轉錯方向也會過。
     */
    @Test
    fun heicPhotoBecomesAnUprightJpeg() {
        val heic = InstrumentationRegistry.getInstrumentation().context.assets.open("portrait.heic").use { it.readBytes() }
        assertNull("HEIC 不能原檔照送", Photos.sniffMediaType(heic))
        val prepared = requireNotNull(PhotoPrep.prepare(heic)) { "這台的 Android 解不開 HEIC" }
        assertEquals("image/jpeg", prepared.mediaType)
        val sent = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
        for (bitmap in listOf(sent, requireNotNull(PhotoPrep.thumbnail(heic, 200)))) {
            assertTrue("轉正之後是直的：${bitmap.width}x${bitmap.height}", bitmap.height > bitmap.width)
            val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2)
            val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2)
            assertTrue("左邊應該是藍的：${Integer.toHexString(left)}", Color.blue(left) > Color.red(left))
            assertTrue("右邊應該是紅的：${Integer.toHexString(right)}", Color.red(right) > Color.blue(right))
        }
    }

    @Test
    fun smallUprightPhotoIsSentAsIs() {
        val bytes = jpeg(640, 480)
        val prepared = requireNotNull(PhotoPrep.prepare(bytes))
        assertTrue(prepared.bytes.contentEquals(bytes))
    }

    /** 壞掉的檔案先在本機擋下來，不要交給 API 去解（壞一張整次請求 400）。 */
    @Test
    fun brokenPhotoIsSkipped() {
        assertNull(PhotoPrep.prepare(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)))
        assertNotNull(PhotoPrep.thumbnail(jpeg(2000, 1000), 400))
    }

    private fun jpeg(width: Int, height: Int, topHalfRed: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            if (topHalfRed) {
                // 上半紅、下半藍：轉正之後看得出轉的方向對不對
                drawColor(Color.rgb(40, 80, 200))
                drawRect(0f, 0f, width.toFloat(), height / 2f, Paint().apply { color = Color.rgb(220, 60, 40) })
            } else {
                drawColor(Color.rgb(206, 121, 81))
            }
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }
}
