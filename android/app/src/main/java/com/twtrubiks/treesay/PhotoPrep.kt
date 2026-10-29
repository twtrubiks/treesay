package com.twtrubiks.treesay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 照片的解圖、縮圖、轉正，規格同網頁版 `ai.prepare_image`（數字在 [Photos]）。
 * 手機常把方向記在 EXIF 而不是真的轉像素，而模型不讀 metadata——不轉的話，直拍的照片樹會看成躺著的。
 */
object PhotoPrep {
    /**
     * 把一張照片整理成要送給樹看的樣子；解不開回 null。夠小、方向正確、格式 API 認得的原檔照送，
     * 其餘縮圖、轉正後重新編碼（HEIC 這類 API 不收的格式也在這一步轉成 JPEG）。
     * 先在本機解一次：壞掉的檔案送出去，整次請求會 400，連帶那天種不下去。
     */
    fun prepare(bytes: ByteArray): AiImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = orientation(bytes)
        val mediaType = Photos.sniffMediaType(bytes)
        if (mediaType != null && Photos.scaleFor(bounds.outWidth, bounds.outHeight) == 1.0 && upright(orientation)) {
            return if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) AiImage(bytes, mediaType) else null
        }
        val (width, height) = Photos.targetSize(bounds.outWidth, bounds.outHeight)
        val bitmap = decodeScaled(bytes, bounds.outWidth, bounds.outHeight, width, height) ?: return null
        val upright = rotate(bitmap, orientation)
        val out = ByteArrayOutputStream()
        return if (upright.hasAlpha()) {
            upright.compress(Bitmap.CompressFormat.PNG, 100, out)
            AiImage(out.toByteArray(), "image/png")
        } else {
            upright.compress(Bitmap.CompressFormat.JPEG, Photos.JPEG_QUALITY, out)
            AiImage(out.toByteArray(), "image/jpeg")
        }
    }

    /** 畫面上顯示用的小圖：長邊不超過 [maxEdge] px，方向轉正。解不開回 null。 */
    fun thumbnail(file: File, maxEdge: Int): Bitmap? = try {
        thumbnail(file.readBytes(), maxEdge)
    } catch (e: java.io.IOException) {
        null
    }

    fun thumbnail(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val scale = minOf(1.0, maxEdge.toDouble() / max(bounds.outWidth, bounds.outHeight))
        val width = max(1, (bounds.outWidth * scale).toInt())
        val height = max(1, (bounds.outHeight * scale).toInt())
        val bitmap = decodeScaled(bytes, bounds.outWidth, bounds.outHeight, width, height) ?: return null
        return rotate(bitmap, orientation(bytes))
    }

    /** 先用 inSampleSize 粗縮（省記憶體），再精確縮到目標尺寸。 */
    private fun decodeScaled(bytes: ByteArray, srcWidth: Int, srcHeight: Int, width: Int, height: Int): Bitmap? {
        var sample = 1
        while (srcWidth / (sample * 2) >= width && srcHeight / (sample * 2) >= height) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return if (decoded.width == width && decoded.height == height) decoded else decoded.scale(width, height)
    }

    /** 沒有 EXIF 的照片，ExifInterface 回的是「未定義」（0）而不是「正常」（1），兩個都是不用轉。 */
    private fun upright(orientation: Int) =
        orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED

    private fun orientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** EXIF 的八種方向轉回正向（對照 Glide 的 TransformationUtils）。 */
    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
