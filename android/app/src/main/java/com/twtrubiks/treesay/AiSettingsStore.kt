package com.twtrubiks.treesay

import android.content.Context
import androidx.core.content.edit

/** 種樹與週報各自用的模型與 effort。不是秘密，直接放 SharedPreferences；讀出來一律經過 [AiProfiles.resolve]。 */
class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun profile(purpose: Purpose): AiProfile = AiProfiles.resolve(
        purpose,
        prefs.getString("${purpose.key}_model", null),
        prefs.getString("${purpose.key}_effort", null),
    )

    fun save(purpose: Purpose, profile: AiProfile) = prefs.edit {
        putString("${purpose.key}_model", profile.model)
        putString("${purpose.key}_effort", profile.effort)
    }
}
