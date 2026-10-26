package com.airtouch

import android.app.Application
import com.airtouch.di.AppGraph

class AirTouchApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
