package io.github.rabobank.intellij.monorepoplugin.services

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoPluginStorage.PluginState

@Service(Service.Level.PROJECT)
@State(
    name = "MonorepoSettings",
    storages = [Storage("monorepo-plugin-settings.xml")],
)
class MonorepoPluginStorage : PersistentStateComponent<PluginState> {
    private var pluginState = PluginState()

    override fun getState() = pluginState

    override fun loadState(state: PluginState) {
        XmlSerializerUtil.copyBean(state, pluginState)
    }

    class PluginState : BaseState() {
        var selectedTeams by stringSet()
        var codeOwnersPath by string("code-owners.json")
        var favoriteTeams by stringSet()
        var pluginExclusions by stringSet()
    }
}
