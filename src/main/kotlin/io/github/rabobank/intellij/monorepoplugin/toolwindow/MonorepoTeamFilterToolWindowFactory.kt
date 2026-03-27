package io.github.rabobank.intellij.monorepoplugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.icons.AllIcons.Actions.Refresh
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.text
import com.intellij.util.ui.JBUI
import io.github.rabobank.intellij.monorepoplugin.ResourceBundle
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoPluginStorage
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoService
import io.github.rabobank.intellij.monorepoplugin.settings.MonorepoSettings.CodeOwnersFileChangedListener
import io.github.rabobank.intellij.monorepoplugin.settings.MonorepoSettings.CodeOwnersFileChangedNotifier
import io.github.rabobank.intellij.monorepoplugin.settings.MonorepoSettings.FavoritesChangedListener
import io.github.rabobank.intellij.monorepoplugin.settings.MonorepoSettings.FavoritesChangedNotifier
import org.jetbrains.annotations.NotNull
import java.util.*

class MonorepoTeamFilterToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val toolWindowContent = TeamFilter(project, toolWindow)
        val content =
            ContentFactory.getInstance().createContent(toolWindowContent.contentPanel, "", false)

        toolWindow.contentManager.addContent(content)
    }

    private class TeamFilter(
        private val project: Project,
        private val toolWindow: ToolWindow,
    ) {
        val contentPanel: DialogPanel
        private val monorepoService = project.service<MonorepoService>()
        private val settingsState = project.service<MonorepoPluginStorage>().state

        init {
            // Register listener for code owners file changes
            CodeOwnersFileChangedNotifier.addListener(
                object : CodeOwnersFileChangedListener {
                    override fun onCodeOwnersFileChanged() {
                        resetView()
                    }
                },
            )
            // Register listener for favorites changes
            FavoritesChangedNotifier.addListener(
                object : FavoritesChangedListener {
                    override fun onFavoritesChanged() {
                        resetView()
                    }
                },
            )
            contentPanel = buildContent(project)

            toolWindow.component.add(contentPanel)
        }

        private fun buildContent(project: Project): DialogPanel {
            val teams = monorepoService.getAllTeams()

            val filterText = ""

            // Keep references to Team rows to toggle visibility when applying/resetting the filter
            data class TeamRow(
                val row: Row,
                val name: String,
            )

            val teamRows = mutableListOf<TeamRow>()

            val selected = settingsState.selectedTeams

            val listsPanel =
                panel {
                    buttonsGroup {
                        if (settingsState.favoriteTeams.isNotEmpty()) {
                            group(ResourceBundle.message("toolwindow.label.favorites")) {
                                settingsState.favoriteTeams
                                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                                    .forEach { name ->
                                        row {
                                            checkBox(name).bindSelected({ name in selected }) {
                                                if (it) {
                                                    selected.add(name)
                                                } else {
                                                    selected.remove(name)
                                                }
                                            }
                                        }
                                    }
                            }
                        }

                        group {
                            teams
                                .filterNot { it in settingsState.favoriteTeams }
                                .forEach { name ->
                                    val row =
                                        row {
                                            checkBox(name).bindSelected({ name in selected }) {
                                                if (it) {
                                                    selected.add(name)
                                                } else {
                                                    selected.remove(name)
                                                }
                                            }
                                        }
                                    teamRows += TeamRow(row, name)
                                }
                        }
                    }
                }

            return panel {
                row {
                    cell(createToolBar())
                        .align(AlignX.FILL)
                }

                lateinit var filterField: Cell<JBTextField>
                row {
                    filterField =
                        textField()
                            .bindText({ filterText }, { })
                            .align(AlignX.FILL)
                            .applyToComponent {
                                emptyText.text = ResourceBundle.message("toolwindow.filter.search.teams")
                            }.resizableColumn()
                            .comment("Type to filter the teams list by name")
                            .onChanged {
                                val q = it.text.trim()
                                val isBlank = q.isBlank()
                                val normalizedQ = q.lowercase(Locale.ROOT)

                                teamRows.forEach { (r, name) ->
                                    val visible = isBlank || name.lowercase(Locale.ROOT).contains(normalizedQ)
                                    r.visible(visible)
                                }

                                listsPanel.revalidate()
                                listsPanel.repaint()
                            }
                }

                // Row 3: one scroll cell that contains both groups (Favorites + Teams)
                row {
                    scrollCell(listsPanel)
                        .align(Align.FILL)
                        .resizableColumn()
                        .applyToComponent {
                            border = JBUI.Borders.empty(8)
                        }
                }.resizableRow()

                // Last row for Apply / Clear buttons
                row {
                    button(ResourceBundle.message("toolwindow.button.apply.filter")) {
                        listsPanel.apply()
                        settingsState.selectedTeams = selected
                        monorepoService.applyFilter(project)
                    }

                    button(ResourceBundle.message("toolwindow.button.clear.filter")) {
                        listsPanel.reset()
                        settingsState.selectedTeams.clear()
                        selected.clear()
                        filterField.text("")
                        teamRows.forEach { (r, _) -> r.visible(true) }

                        resetView()

                        monorepoService.applyFilter(project)
                    }
                }
            }
        }

        @NotNull
        private fun createToolBar(): DialogPanel {
            // Create a refresh action
            val refreshAction =
                object : AnAction(
                    ResourceBundle.message("toolwindow.action.refresh"),
                    ResourceBundle.message("toolwindow.action.refresh.description"),
                    Refresh,
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        // Reparse the file first
                        monorepoService.getCodeOwnerRules()

                        resetView()
                    }
                }

            // Create a settings action
            val settingsAction =
                object : AnAction(
                    ResourceBundle.message("toolwindow.action.settings"),
                    ResourceBundle.message("toolwindow.action.settings.description"),
                    AllIcons.Actions.InlayGear,
                ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Monorepo Team Filter")
                    }
                }

            val actionGroup =
                DefaultActionGroup().apply {
                    add(refreshAction)
                    add(settingsAction)
                }

            val actionToolbar =
                ActionManager
                    .getInstance()
                    .createActionToolbar("MonorepoActionToolbar", actionGroup, true)
            actionToolbar.targetComponent = toolWindow.component

            return panel {
                row {
                    label(ResourceBundle.message("toolwindow.title.select.team"))
                        .align(AlignX.FILL)
                    cell(actionToolbar.component)
                        .align(AlignX.RIGHT)
                }
            }
        }

        private fun resetView() {
            val contentManager = toolWindow.contentManager
            val oldContent = contentManager.selectedContent ?: contentManager.contents.firstOrNull()
            if (oldContent != null) {
                // remove from content manager and also from the tool window component to avoid stacking
                contentManager.removeContent(oldContent, true)
                toolWindow.component.remove(oldContent.component)
            }

            val newPanel = buildContent(project)
            val newContent =
                ContentFactory.getInstance().createContent(newPanel, "", false)
            contentManager.addContent(newContent)

            // Ensure UI updates
            toolWindow.component.revalidate()
            toolWindow.component.repaint()
        }
    }
}
