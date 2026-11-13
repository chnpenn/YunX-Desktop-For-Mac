package com.yunx.app.diagnostics

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Keeps diagnostic and test preferences separate from macOS CFPreferences. */
class MemoryPreferencesFactory : PreferencesFactory {
    private val user = Node(null, "")
    private val system = Node(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system

    private class Node(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
        private val values = mutableMapOf<String, String>()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = cachedChildren().map { it.name() }.toTypedArray()
        override fun childSpi(name: String): AbstractPreferences = Node(this, name)
        override fun removeNodeSpi() { values.clear() }
        override fun syncSpi() {}
        override fun flushSpi() {}
    }
}
