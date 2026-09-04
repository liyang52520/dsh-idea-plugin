package com.yg.dsh.idea.runtime

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

class AppShutdownListener : AppLifecycleListener {

    override fun appClosing() {
        LOG.info("AppLifecycleListener.appClosing: disposing DSH instances")
        try {
            PanelRegistry.getInstance().onAppClosing()
        } catch (e: Throwable) {
            LOG.warn("app closing cleanup failed", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AppShutdownListener::class.java)
    }
}