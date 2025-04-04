package com.github.martinvisser.monorepoplugin.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(
    name = "CodeOwnersSettings",
    storages = [Storage("code-owners-settings.xml")]
)
class CodeOwnersSettingsState : PersistentStateComponent<CodeOwnersSettingsState> {
    var selectedTeams: List<String> = emptyList()
    var codeOwnersPath: String = "code-owners.json" // Default to project root

    override fun getState(): CodeOwnersSettingsState = this

    override fun loadState(state: CodeOwnersSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun updateSelectedTeams(teams: List<String>) {
        selectedTeams = teams
    }
}
