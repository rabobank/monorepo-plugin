package com.github.martinvisser.monorepoplugin.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

@Service(Service.Level.PROJECT)
@State(
    name = "CodeOwnersService",
    storages = [Storage("code-owners-service.xml")]
)
class CodeOwnersService(private val project: Project) : PersistentStateComponent<CodeOwnersService.State> {
    private val gson = Gson()
    private var state = State()

    data class State(
        var lastReadTime: Long = 0,
        var cachedRules: List<CodeOwnerRule> = emptyList(),
    )

    @Tag("CodeOwnerRule")
    data class CodeOwnerRule(
        @Tag("name")
        val name: String = "",
        @Tag("paths")
        val paths: List<String> = emptyList(),
    )

    data class CodeOwnersConfig(
        val teams: Map<String, Team> = emptyMap(),
    )

    data class Team(
        val id: String = "",
        val paths: List<String> = emptyList(),
    )

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun getCodeOwnerRules(): List<CodeOwnerRule> {
        val codeOwnersFile = getCodeOwnersFile()
        if (codeOwnersFile == null) {
            logger.warn("code-owners.json file not found")
            return emptyList()
        }

        return try {
            val content = Files.readString(Paths.get(codeOwnersFile.path))
            val config = gson.fromJson(content, CodeOwnersConfig::class.java)

            // Convert the map to a list of CodeOwnerRule
            val rules = config.teams.map { (name, team) ->
                CodeOwnerRule(name, team.paths)
            }.filter { it.name.isNotBlank() }

            // Update the cached rules and last read time
            state.cachedRules = rules
            state.lastReadTime = System.currentTimeMillis()

            rules
        } catch (e: IOException) {
            logger.error("Failed to read code-owners.json", e)
            emptyList()
        } catch (e: JsonSyntaxException) {
            logger.error("Invalid JSON in code-owners.json", e)
            emptyList()
        }
    }

    fun getAllTeams(): List<String> =
        getCodeOwnerRules()
            .filter { it.name.isNotBlank() }
            .map { it.name }
            .distinct()
            .sorted()

    private fun getCodeOwnersFile(): VirtualFile? {
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        val customPath = settings.codeOwnersPath

        val fileManager = VirtualFileManager.getInstance()
        val file = if (customPath != "code-owners.json") {
            // Try to find the file at the custom path
            val path = Paths.get(customPath)
            fileManager.findFileByNioPath(path)
        } else {
            // Look for code-owners.json in the project root
            fileManager.findFileByNioPath(Paths.get(project.basePath!!, "code-owners.json"))
        }

        if (file == null) {
            logger.warn("code-owners.json not found")
        }
        return file
    }

    companion object {
        private val logger = Logger.getInstance(CodeOwnersService::class.java)
    }
}
