package com.github.martinvisser.monorepoplugin.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import kotlin.time.measureTime

class MonorepoPluginStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val projectDir = project.guessProjectDir() ?: return@invokeLater
            val module =
                ProjectFileIndex.getInstance(project).getModuleForFile(projectDir) ?: return@invokeLater
            val model = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry =
                model.contentEntries.find { projectDir.path.startsWith(it.file?.path.orEmpty()) }
            if (contentEntry == null) {
                logger.warn("No content entry found for project dir: ${projectDir.path}")
                return@invokeLater
            }
            runWriteAction {
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

                measureTime {
                    defaultExclusions
                        .mapNotNull(projectDir::findChild)
                        .filter(VirtualFile::isDirectory)
                        .forEach { file ->
                            logger.debug("Excluding default: ${file.path}")
                            contentEntry.addExcludeFolder(file.url)
                        }
                }.also { logger.debug("Time taken to add default exclusions: $it") }
                model.commit()
            }
            project.save()
        }
    }

    private companion object {
        private val logger = Logger.getInstance(MonorepoPluginStartupActivity::class.java)
    }
}
