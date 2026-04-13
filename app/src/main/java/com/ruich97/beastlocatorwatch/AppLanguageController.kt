package com.ruich97.beastlocatorwatch

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageController {
    private const val JAPANESE_TAG = "ja"

    fun applyPolicy(context: Context) {
        val store = DestinationStore(context)
        if (store.isNonJapaneseLanguageEnabled()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(JAPANESE_TAG))
        }
    }
}
