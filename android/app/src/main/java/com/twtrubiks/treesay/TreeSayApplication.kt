package com.twtrubiks.treesay

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class TreeSayApplication : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as TreeSayApplication).container

/** 整個 App 共用的東西：日記本、API key、AI 設定、畫面動態。 */
class AppContainer(private val context: Context) {
    val keyStore = ApiKeyStore(context)
    val settings = AiSettingsStore(context)
    val motion = MotionStore(context)
    val photosDir = File(context.filesDir, "photos")

    /** 不跟著畫面走的工作（例如週報生成到一半使用者切走了），在這裡跑完。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 只給錄影測試用：把 Claude 指到裝置上的假伺服器，CI 沒有真的 key 也能走完種樹。
     * 只有 debug 版認這個值，release 版永遠連 Anthropic。
     */
    @Volatile
    var claudeBaseUrlForTests: String? = null

    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val diary: Diary by lazy {
        Diary(
            sql = AndroidSql(context),
            photosDir = photosDir,
            ai = { prompt, schema, images, purpose ->
                Claude(
                    apiKey = keyStore::get,
                    profile = settings::profile,
                    recordUsage = { diary.recordUsage(it) },
                    baseUrl = claudeBaseUrlForTests?.takeIf { debuggable },
                ).ask(prompt, schema, images, purpose)
            },
            prepareImage = PhotoPrep::prepare,
        )
    }

    private val reports = ConcurrentHashMap<LocalDate, Deferred<WeeklyResult>>()

    /** 週報。同一週同時只生成一次；使用者切走了也會生成完、存進快取。 */
    suspend fun report(weekStart: LocalDate): WeeklyResult {
        // LAZY：等放進表裡、有人 await 才開始跑，跑完時從表裡拿掉的那一下才不會搶在放進去之前
        val job = reports.computeIfAbsent(weekStart) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    diary.report(weekStart)
                } finally {
                    reports.remove(weekStart)
                }
            }
        }
        return job.await()
    }
}
