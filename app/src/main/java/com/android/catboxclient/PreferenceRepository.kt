package com.android.catboxclient

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

class PreferenceRepository(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("catbox_prefs", Context.MODE_PRIVATE)

    private val _userHash = MutableStateFlow(prefs.getString("user_hash", "") ?: "")

    fun getUserHash(): String = _userHash.value

    fun saveUserHash(hash: String) {
        prefs.edit { putString("user_hash", hash) }
        _userHash.value = hash
    }
}