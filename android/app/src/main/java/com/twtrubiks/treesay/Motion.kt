package com.twtrubiks.treesay

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.core.content.edit

/**
 * 畫面要不要動，整個 App 只問這裡一個地方，跟網頁版 `motion.ts` 同一套：開（預設）／關／跟系統。
 * 預設「開」：系統關掉動畫不一定代表想關掉這棵樹。真的需要安靜畫面的人在「設定」切成「關」，
 * 或切「跟系統」讓開發者選項的「移除動畫」決定。
 */
enum class MotionPref(val key: String, val label: String) {
    ON("on", "開"),
    OFF("off", "關"),
    SYSTEM("system", "跟系統"),
}

class MotionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun get(): MotionPref = MotionPref.entries.firstOrNull { it.key == prefs.getString("motion", null) } ?: MotionPref.ON

    fun save(pref: MotionPref) = prefs.edit { putString("motion", pref.key) }

    /** 畫面該不該安靜下來。 */
    fun reduceMotion(pref: MotionPref = get()): Boolean = when (pref) {
        MotionPref.ON -> false
        MotionPref.OFF -> true
        MotionPref.SYSTEM ->
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * 交給 Compose 的動畫速度（見 `MainActivity`）。Compose 預設照系統的動畫速度走，「移除動畫」把它設成 0 時，
 * 所有動畫直接停在終點，連 App 的「開」也蓋不過。這裡把 0 當成 1：要不要動只由 [LocalReduceMotion] 決定，
 * 跟網頁版一樣是 App 自己的開關說了算。0.5 倍、2 倍這類調速照系統。回到 App 時（onStart）重讀一次。
 */
class AppMotionScale(private val context: Context) : MotionDurationScale {
    @Volatile
    override var scaleFactor: Float = read()
        private set

    fun refresh() {
        scaleFactor = read()
    }

    private fun read(): Float =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).takeIf { it > 0f } ?: 1f
}

/** 元件不要自己去問系統，一律看這個。 */
val LocalReduceMotion = compositionLocalOf { false }

/** 整個畫面共用一份天色、一個節拍（見 [Seasons]）。各自計時的話，跨過 18:00 時樹和按鈕會差一分鐘才入夜。 */
val LocalSky = compositionLocalOf { Sky(Season.SPRING, night = false) }
