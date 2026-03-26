package com.github.martinvisser.monorepoplugin.settings

import com.github.martinvisser.monorepoplugin.ResourceBundle
import com.github.martinvisser.monorepoplugin.services.MonorepoPluginStorage
import com.github.martinvisser.monorepoplugin.services.MonorepoService
import com.intellij.ide.util.TreeFileChooserFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.emptyText
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel

private const val TOOL_WINDOW_ID = "Monorepo Team Filter"

class MonorepoSettings(
    private val project: Project,
) : Configurable {
    private val monorepoService = project.service<MonorepoService>()
    private val storage = project.service<MonorepoPluginStorage>()
    private val warningLabel =
        JBLabel(ResourceBundle.message("settings.warning.no.teams")).apply {
            isVisible = false
        }

    // Replace eager creation with lateinit to construct via DSL builder inside createComponent()
    private lateinit var fileSelector: TextFieldWithBrowseButton

    // Favorites UI
    private val favoritesCheckboxPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun refreshTeamCheckboxes() {
        val teamNames = monorepoService.getAllTeams()
        // preserve current selections and merge with saved favorites
        val preserved =
            favoritesCheckboxPanel.components
                .filterIsInstance<JCheckBox>()
                .filter { it.isSelected }
                .map { it.text }
                .toSet()
        val saved = storage.state.favoriteTeams

        ApplicationManager.getApplication().invokeLater {
            favoritesCheckboxPanel.removeAll()
            teamNames.forEach { name ->
                val cb =
                    JCheckBox(name).apply {
                        isSelected = preserved.contains(name) || saved.contains(name)
                        alignmentX = Component.LEFT_ALIGNMENT
                    }
                favoritesCheckboxPanel.add(cb)
            }
            favoritesCheckboxPanel.revalidate()
            favoritesCheckboxPanel.repaint()
        }
    }

    override fun createComponent() =
        panel {
            // Label on its own row
            row {
                label(ResourceBundle.message("settings.file.selector.description"))
            }

            // Create the file selector via the DSL on the next row and configure it.
            row {
                fileSelector =
                    TextFieldWithBrowseButton().apply {
                        emptyText.text = ResourceBundle.message("settings.file.selector.empty.text")
                        text = storage.state.codeOwnersPath.orEmpty()

                        addActionListener {
                            @Suppress("DialogTitleCapitalization")
                            val chooser =
                                TreeFileChooserFactory
                                    .getInstance(project)
                                    .createFileChooser(
                                        ResourceBundle.message("settings.file.selector.title"),
                                        null,
                                        null,
                                    ) { file -> file.name == "code-owners.json" }

                            chooser.showDialog()
                            val selectedVirtual = chooser.selectedFile?.virtualFile
                            if (selectedVirtual != null) {
                                val absolutePath = selectedVirtual.path
                                val projectRoot = project.basePath ?: ""
                                val relativePath =
                                    if (absolutePath.startsWith(projectRoot)) {
                                        absolutePath.removePrefix(projectRoot).removePrefix("/")
                                    } else {
                                        absolutePath
                                    }
                                text = relativePath
                                monorepoService.getCodeOwnerRules() // parse immediately
                                refreshTeamCheckboxes()

                                ToolWindowManager
                                    .getInstance(project)
                                    .getToolWindow(TOOL_WINDOW_ID)
                                    ?.component
                                    ?.repaint()

                                // Notify listeners of file change
                                CodeOwnersFileChangedNotifier.notifyChanged()
                            }
                        }
                    }

                // place the configured component into the DSL and allow it to expand full width
                cell(fileSelector).align(AlignX.FILL).resizableColumn()

                // If a path is already set, ensure we load and show checkboxes immediately
                if (fileSelector.text.isNotBlank()) {
                    monorepoService.getCodeOwnerRules()
                    refreshTeamCheckboxes()
                }
            }

            // Warning row directly under the file selector (spans the width)
            row {
                cell(warningLabel)
            }

            // Favorites: label above the scrollable checkbox list, then the list itself which should take remaining height
            row {
                label(ResourceBundle.message("settings.favorites.description"))
            }

            // Wrap the favoritesScroll in a BorderLayout panel so it can expand to fill available vertical space
            row {
                scrollCell(favoritesCheckboxPanel)
                    .align(Align.FILL)
            }
        }

    override fun isModified(): Boolean {
        // Block saving if the warning label is visible
        if (warningLabel.isVisible) {
            return false
        }
        if (::fileSelector.isInitialized && fileSelector.text != storage.state.codeOwnersPath) return true

        val currentSelected =
            favoritesCheckboxPanel.components
                .filterIsInstance<JCheckBox>()
                .filter { it.isSelected }
                .map { it.text }
                .toSet()
        return currentSelected != storage.state.favoriteTeams
    }

    override fun apply() {
        // Block applying settings if the warning label is visible
        if (warningLabel.isVisible) {
            logger.info("Cannot apply settings: Invalid Code Owners file.")
            return
        }
        logger.warn("Applying code owners settings")
        if (::fileSelector.isInitialized) {
            storage.state.codeOwnersPath = fileSelector.text
        }

        // Save to settings state
        val newFavorites =
            favoritesCheckboxPanel.components
                .filterIsInstance<JCheckBox>()
                .filter { it.isSelected }
                .map { it.text }
                .toMutableSet()
        storage.state.favoriteTeams = newFavorites

        // Notify listeners of favorites change
        FavoritesChangedNotifier.notifyChanged()

        ToolWindowManager
            .getInstance(project)
            .getToolWindow(TOOL_WINDOW_ID)
            ?.component
            ?.run {
                revalidate()
                repaint()
            }
        logger.warn("Applied code owners settings")
    }

    override fun getDisplayName(): String = ResourceBundle.message("settings.display.name")

    private companion object {
        private val logger = Logger.getInstance(MonorepoSettings::class.java)
    }

    interface CodeOwnersFileChangedListener {
        fun onCodeOwnersFileChanged()
    }

    // Utility to notify listeners
    object CodeOwnersFileChangedNotifier {
        private val listeners = mutableListOf<CodeOwnersFileChangedListener>()

        fun addListener(listener: CodeOwnersFileChangedListener) {
            listeners.add(listener)
        }

        fun notifyChanged() {
            listeners.forEach { it.onCodeOwnersFileChanged() }
        }
    }

    interface FavoritesChangedListener {
        fun onFavoritesChanged()
    }

    object FavoritesChangedNotifier {
        private val listeners = mutableListOf<FavoritesChangedListener>()

        fun addListener(listener: FavoritesChangedListener) {
            listeners.add(listener)
        }

        fun notifyChanged() {
            listeners.forEach { it.onFavoritesChanged() }
        }
    }
}
