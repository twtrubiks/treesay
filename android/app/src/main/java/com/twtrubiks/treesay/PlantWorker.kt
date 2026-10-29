package com.twtrubiks.treesay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

/**
 * 種樹交給 WorkManager：按下去之後切走 App、關掉螢幕，樹一樣會種完。
 * 被系統停掉時 [Diary.plant] 會把鎖解開；整個行程被殺掉的話，鎖 5 分鐘後逾期，回來可以再按一次。
 */
class PlantWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val date = LocalDate.parse(requireNotNull(inputData.getString(KEY_DATE)))
        return try {
            applicationContext.container.diary.plant(date)
            Result.success()
        } catch (e: TreeSayException) {
            Result.failure(workDataOf(KEY_MESSAGE to e.message, KEY_KIND to e.kind.name))
        }
    }

    companion object {
        private const val KEY_DATE = "date"
        private const val KEY_MESSAGE = "message"
        private const val KEY_KIND = "kind"

        private fun name(date: LocalDate) = "plant-$date"

        /** 開始種 [date] 那天的樹，回傳那份工作的 id。那天已經有一份在跑的話，不另開一份，回傳原本那份。 */
        suspend fun start(context: Context, date: LocalDate): UUID {
            val manager = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<PlantWorker>()
                .setInputData(workDataOf(KEY_DATE to date.toString()))
                .build()
            manager.enqueueUniqueWork(name(date), ExistingWorkPolicy.KEEP, request).await()
            return running(context, date) ?: request.id
        }

        /** [date] 那天正在種的那份工作；沒有就是 null（例如上次種到一半，整個 App 被系統關掉）。 */
        suspend fun running(context: Context, date: LocalDate): UUID? =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(date)).first()
                .firstOrNull { !it.state.isFinished }?.id

        /** 等那份工作結束：種好了回 null，沒種成回給使用者看的那句話。 */
        suspend fun await(context: Context, id: UUID): TreeSayException? {
            val info = WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished }
            if (info?.state == WorkInfo.State.SUCCEEDED) return null
            val kind = info?.outputData?.getString(KEY_KIND)
                ?.let { k -> TreeSayException.Kind.entries.firstOrNull { it.name == k } }
                ?: TreeSayException.Kind.ASLEEP
            return TreeSayException(info?.outputData?.getString(KEY_MESSAGE) ?: Diary.TREE_ASLEEP, kind)
        }
    }
}
