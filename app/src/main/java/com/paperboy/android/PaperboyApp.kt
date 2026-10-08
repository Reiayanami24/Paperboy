package com.paperboy.android

import android.app.Application

/** Installs crash recording before any activity starts. */
class PaperboyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
