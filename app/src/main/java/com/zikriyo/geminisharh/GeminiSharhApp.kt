package com.zikriyo.geminisharh

import android.app.Application
import androidx.work.Configuration

class GeminiSharhApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
