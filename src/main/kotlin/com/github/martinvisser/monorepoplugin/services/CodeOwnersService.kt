package com.github.martinvisser.monorepoplugin.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
        var cachedRules: List<CodeOwnerRule> = emptyList()
    )

    data class CodeOwnerRule(
        val name: String,
        val paths: List<String>
    )

    data class CodeOwnersConfig(
        val teams: List<CodeOwnerRule>
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
        
        logger.info("Found code-owners.json at: ${codeOwnersFile.path}")
        val filePath = Paths.get(codeOwnersFile.path)
        val currentModifiedTime = Files.getLastModifiedTime(filePath).toInstant().toEpochMilli()

        // Return cached rules if file hasn't been modified
        if (currentModifiedTime <= state.lastReadTime) {
            logger.info("Using cached rules, file not modified since last read")
            return state.cachedRules
        }

        try {
            val content = Files.readString(filePath)
            logger.info("Read content from code-owners.json: $content")
            val config = gson.fromJson(content, CodeOwnersConfig::class.java)
            logger.info("Parsed config: $config")
            
            // Filter out any null or invalid rules
            val validRules = config.teams.filter { it.name.isNotBlank() }
            logger.info("Valid rules after filtering: $validRules")
            
            state.lastReadTime = currentModifiedTime
            state.cachedRules = validRules
            return validRules
        } catch (e: IOException) {
            logger.error("Failed to read code-owners.json", e)
            return state.cachedRules
        } catch (e: JsonSyntaxException) {
            logger.error("Invalid JSON in code-owners.json", e)
            return state.cachedRules
        }
    }

    fun getAllTeams(): List<String> {
        val rules = getCodeOwnerRules()
        logger.info("Getting all teams from rules: $rules")
        val teams = rules
            .filter { it.name.isNotBlank() }
            .map { it.name }
            .distinct()
            .sorted()
        logger.info("Found teams: $teams")
        return teams
    }

    private fun getCodeOwnersFile(): VirtualFile? {
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        val customPath = settings.codeOwnersPath
        logger.info("Looking for code-owners.json with path: $customPath")
        
        val fileManager = VirtualFileManager.getInstance()
        val file = if (customPath != "code-owners.json") {
            // Try to find the file at the custom path
            val path = Paths.get(customPath)
            logger.info("Looking for file at custom path: $path")
            fileManager.findFileByNioPath(path)
        } else {
            // Look for code-owners.json in the project root
            logger.info("Looking for code-owners.json in project root")
            project.baseDir.findChild("code-owners.json")
        }
        
        if (file == null) {
            logger.warn("code-owners.json not found")
        } else {
            logger.info("Found code-owners.json at: ${file.path}")
        }
        return file
    }

    companion object {
        private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(CodeOwnersService::class.java)
    }
} 