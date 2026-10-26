package com.airtouch.di

import android.content.Context
import com.airtouch.settings.SettingsRepository

/** Hand-wired dependencies (AD-7: no DI framework). Each phase adds the components it builds. */
class AppGraph(context: Context) {
    val settings = SettingsRepository(context.applicationContext)
}
