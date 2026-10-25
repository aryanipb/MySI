package com.mysi.agent

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Agent.init(this)
    }
}
