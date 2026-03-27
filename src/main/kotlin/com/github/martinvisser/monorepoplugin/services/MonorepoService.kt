package com.github.martinvisser.monorepoplugin.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiManager
import com.intellij.util.xmlb.annotations.Tag
import java.io.IOException
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.time.measureTime

@Service(Service.Level.PROJECT)
class MonorepoService(
    private val project: Project,
) {
    private val gson = Gson()
    private val storage = project.service<MonorepoPluginStorage>()

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

    fun getCodeOwnerRules(): List<CodeOwnerRule> {
        val codeOwnersFile = getCodeOwnersFile()
        if (codeOwnersFile == null) {
            logger.warn("code-owners.json file not found")
            return emptyList()
        }

        return try {
            val content = runReadAction { PsiManager.getInstance(project).findFile(codeOwnersFile)!!.text }
            val config = gson.fromJson(content, CodeOwnersConfig::class.java)

            // Convert the map to a list of CodeOwnerRule
            config.teams
                .map { (name, team) -> CodeOwnerRule(name, team.paths) }
                .filter { it.name.isNotBlank() }
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
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    private fun getCodeOwnerPathsForTeam(teamNames: Set<String>): List<String> =
        getCodeOwnerRules()
            .find { it.name in teamNames }
            ?.paths
            .orEmpty()

    private fun getCodeOwnersFile(): VirtualFile? {
        val customPath = storage.state.codeOwnersPath

        if (customPath.isNullOrBlank()) {
            logger.debug("code-owners.json file not found")
            return null
        }

        val file =
            project.basePath
                ?.let { basePath ->
                    val path = Paths.get(basePath, customPath)
                    if (path.exists()) {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    } else {
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(customPath))
                    }
                }?.also { vf ->
                    val app = ApplicationManager.getApplication()
                    app.invokeLater(
                        { app.runWriteAction { vf.refresh(false, false) } },
                        ModalityState.defaultModalityState(),
                    )
                }
        if (file == null) {
            logger.debug("code-owners.json not found at $customPath within the project directory")
        }
        return file
    }

    fun applyFilter(project: Project) {
        val projectDir = project.guessProjectDir() ?: return
        val module = runWriteAction { ProjectFileIndex.getInstance(project).getModuleForFile(projectDir) } ?: return
        val model = ModuleRootManager.getInstance(module).modifiableModel
        val contentEntry =
            model.contentEntries.find { entry ->
                projectDir.path.startsWith(entry.file?.path.orEmpty())
            } ?: return

        logger.debug("Filtering code owners for team: ${storage.state.selectedTeams}")

        runWriteAction {
            measureTime {
                storage.state.pluginExclusions.forEach { exclusion ->
                    VirtualFileManager.getInstance().findFileByUrl(exclusion)?.let {
                        if (it.isDirectory) {
                            contentEntry.removeExcludeFolder(it.url)
                        } else {
                            contentEntry.removeExcludePattern(it.path)
                        }
                    }
                }
                storage.state.pluginExclusions.clear() // Clear previous plugin exclusions
            }.also { logger.debug("Time taken to clear exclusions: $it") }
        }

        if (storage.state.selectedTeams.isEmpty()) {
            save(project, model)
        } else {
            val allPaths = getCodeOwnerPathsForTeam(storage.state.selectedTeams)
            val allowedPaths = allPaths.filterNot { it.startsWith("!") }.toSet()
            val excludedPaths = allPaths.filter { it.startsWith("!") }.map { it.removePrefix("!") }.toSet()
            val includedPaths = allowedPaths - excludedPaths

            precomputePathInclusionMap(
                project,
                contentEntry,
                projectDir,
                includedPaths,
                excludedPaths,
                storage,
            )
            save(project, model)
        }
    }

    fun getTeamForFile(file: VirtualFile): String? {
        val base = project.basePath ?: return null
        val relPath = file.sanitize(base) // uses existing sanitize helper

        fun matchesPattern(
            path: String,
            pattern: String,
        ): Boolean {
            val pat = pattern.trim()
            val isStar = pat.endsWith("*")
            val raw = if (isStar) pat.removeSuffix("*") else pat
            val normalized = if (raw.startsWith("/")) raw else "/$raw"
            return if (isStar) {
                path.startsWith(normalized)
            } else {
                // exact match or folder prefix
                path == normalized || path.startsWith(normalized)
            }
        }

        return runReadAction {
            // Process rules and their patterns in order. Non-`!` patterns set the owner,
            // `!` patterns unset the owner so later rules can claim it.
            getCodeOwnerRules().firstNotNullOfOrNull { rule ->
                if (!rule.paths
                        .filter { it.startsWith("!") }
                        .map { it.removePrefix("!").trim() }
                        .any { matchesPattern(relPath, it) } &&
                    rule.paths.filterNot { it.startsWith("!") }.any { matchesPattern(relPath, it) }
                ) {
                    rule.name
                } else {
                    null
                }
            }
        }
    }

    private fun precomputePathInclusionMap(
        project: Project,
        contentEntry: ContentEntry,
        projectDir: VirtualFile,
        includedPaths: Set<String>,
        excludedPaths: Set<String>,
        storage: MonorepoPluginStorage,
    ) {
        val basePath = project.basePath ?: return

        val exclusions = mutableSetOf<VirtualFile>()
        val inclusions = mutableSetOf<VirtualFile>()

        val defaultExclusions =
            setOf(
                "/.idea/",
                "/.git/",
                "/.gradle/",
                "/.nx/",
                "/build/",
                "/out/",
                "/node_modules/",
            )

        fun processFolder(folder: VirtualFile) {
            VfsUtilCore.visitChildrenRecursively(
                folder,
                object : VirtualFileVisitor<Any>(SKIP_ROOT, ONE_LEVEL_DEEP, NO_FOLLOW_SYMLINKS) {
                    override fun visitFileEx(node: VirtualFile): Result {
                        val path = node.sanitize(basePath)
                        if (path.startsWith("/.") || path in defaultExclusions) return SKIP_CHILDREN
                        if (node.parent == projectDir && node.isFile) return CONTINUE

                        if (includedPaths.any { it.removeSuffix("*").startsWith(path) } ||
                            includedPaths.any { path.startsWith(it.removeSuffix("*")) }
                        ) {
                            inclusions.add(node)
                            if (node.isDirectory) {
                                exclusions.remove(node)
                                processFolder(node) // Go one level deeper
                            }
                        } else {
                            exclusions.add(node)
                        }

                        return CONTINUE
                    }
                },
            )
        }

        measureTime {
            processFolder(projectDir)
        }.also {
            logger.debug("Time taken to visit files: $it")
        }

        exclusions += excludedPaths.mapNotNull { projectDir.findFileByRelativePath(it.removeSuffix("/*")) }

        measureTime {
            defaultExclusions
                .mapNotNull(projectDir::findChild)
                .forEach { file ->
                    logger.debug("Excluding default: ${file.path}")
                    if (file.isDirectory) {
                        contentEntry.addExcludeFolder(file.url)
                    } else {
                        contentEntry.addExcludePattern(file.path)
                    }
                }
        }.also { logger.debug("Time taken to add default exclusions: $it") }

        measureTime {
            exclusions.forEach { file ->
                logger.debug("Excluding: ${file.path}")
                if (file.isDirectory) {
                    contentEntry.addExcludeFolder(file.url)
                } else {
                    contentEntry.addExcludePattern(file.path)
                }
            }
        }.also { logger.debug("Time taken to add exclusions: $it") }

        measureTime {
            storage.state.pluginExclusions.addAll(exclusions.map { it.url })
        }.also { logger.debug("Time taken to store exclusions: $it") }
    }

    private fun VirtualFile.sanitize(basePath: String) =
        path.removePrefix(basePath).let { if (isDirectory) "$it/" else it }

    private fun save(
        project: Project,
        model: ModifiableRootModel,
    ) {
        measureTime {
            ApplicationManager.getApplication().runWriteAction(model::commit)
        }.also {
            logger.debug("Time taken to save model: $it")
        }

        // Refresh the tree structure provider to apply the exclusion filter
        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project).refresh()
        }

        measureTime {
            project.save()
        }.also {
            logger.debug("Time taken to save project: $it")
        }

        VirtualFileManager.getInstance().asyncRefresh()

        logger.debug("Tree updated, and file system refreshed")
    }

    private companion object {
        private val logger = Logger.getInstance(MonorepoService::class.java)
    }
}
