package com.twtrubiks.treesay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 送給樹看的照片規格，跟網頁版 `ai.prepare_image`、`ai.image_blocks` 同一套。
 * 這裡只放跟 Android 無關的算術；真的解圖、縮圖、轉正在 [PhotoPrep]。
 */
object Photos {
    /** API 限制：單張照片 base64 後 10 MB。 */
    const val IMAGE_MAX_B64_BYTES = 10_000_000L

    /** 整個 request 32 MB，照片總預算抓在下面，留餘裕給文字。 */
    const val IMAGES_BUDGET_B64_BYTES = 24_000_000L

    /**
     * 縮到 Claude 的標準解析度等級（長邊 1568px、約 115 萬像素，一張約 1,500 token）。
     * 手機原圖會吃到單張四千多 token，比一次不附照片的種樹還貴——樹只要看懂你拍了什麼。
     * 縮的只是送出去的那一份，原圖照樣留在手機裡。
     */
    const val MAX_EDGE = 1568
    const val MAX_PIXELS = 1_150_000
    const val JPEG_QUALITY = 85

    /** 看檔頭認圖片格式，只認 API 支援的四種。不信副檔名：標錯一張整次請求就失敗。 */
    fun sniffMediaType(data: ByteArray): String? {
        fun startsWith(vararg bytes: Int) = data.size >= bytes.size && bytes.indices.all { data[it] == bytes[it].toByte() }
        fun ascii(from: Int, text: String) =
            data.size >= from + text.length && text.indices.all { data[from + it] == text[it].code.toByte() }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            else -> null
        }
    }

    /** 要縮成原本的幾倍：長邊不超過 [MAX_EDGE]、總像素不超過 [MAX_PIXELS]，本來就夠小的不放大。 */
    fun scaleFor(width: Int, height: Int): Double =
        min(1.0, min(MAX_EDGE.toDouble() / max(width, height), sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height))))

    /** 縮完的尺寸，至少 1px。 */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = scaleFor(width, height)
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    fun base64Length(bytes: Int): Long = (bytes + 2L) / 3 * 4

    /**
     * 照片依序放進預算，順序跟輸入對齊；送不出去的位置放 null。
     * 格式不支援、壞掉、單張太大、超出總預算，都只是這張樹看不到——一張照片不能拖垮整次種樹。
     */
    fun fitBudget(prepared: List<AiImage?>): List<AiImage?> {
        var budget = IMAGES_BUDGET_B64_BYTES
        return prepared.map { image ->
            val size = image?.let { base64Length(it.bytes.size) }
            if (image != null && size != null && size <= min(IMAGE_MAX_B64_BYTES, budget)) {
                budget -= size
                image
            } else {
                null
            }
        }
    }
}
