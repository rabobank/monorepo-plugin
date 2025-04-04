package com.github.martinvisser.monorepoplugin.services

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextComponentAccessor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.log

class CodeOwnersSettingsConfigurable : Configurable {
    private val logger = Logger.getInstance(CodeOwnersSettingsConfigurable::class.java)
    private lateinit var mainPanel: JPanel
    private lateinit var searchField: JBTextField
    private lateinit var teamList: CheckBoxList<String>
    private lateinit var codeOwnersPathField: TextFieldWithBrowseButton
    private lateinit var createScopeButton: JButton

    private var allTeams: List<String> = emptyList()
    private var selectedTeams: List<String> = emptyList()

    override fun getDisplayName(): String = "Code Owners Filter"

    override fun createComponent(): JComponent {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return JPanel()
        val codeOwnersService = project.getService(CodeOwnersService::class.java)
        val settings = project.getService(CodeOwnersSettingsState::class.java)

        // Create code-owners.json path field with browse button
        codeOwnersPathField = TextFieldWithBrowseButton().apply {
            text = settings.codeOwnersPath
            addBrowseFolderListener(
                "Select code-owners.json",
                "Choose the location of code-owners.json",
                project,
                FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter { file -> file.name == "code-owners.json" },
                TextComponentAccessor.TEXT_FIELD_SELECTED_TEXT
            )
            addActionListener {
                settings.codeOwnersPath = text
                loadTeams(codeOwnersService)
            }
        }

        searchField = JBTextField()
        teamList = CheckBoxList<String>()

        // Load teams initially
        loadTeams(codeOwnersService)

        // Add search functionality
        searchField.document?.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterTeams()
            override fun removeUpdate(e: DocumentEvent) = filterTeams()
            override fun changedUpdate(e: DocumentEvent) = filterTeams()
        })

        // Create scope button
        createScopeButton = JButton("Create File Scope").apply {
            addActionListener {
                val selectedTeams = mutableListOf<String>()
                teamList.let { list ->
                    for (i in 0 until list.itemsCount) {
                        if (list.isItemSelected(i)) {
                            selectedTeams.add(list.getItemAt(i)!!)
                        }
                    }

                    saveSelectedTeams()
                }
                if (selectedTeams.isNotEmpty()) {
                    createFileScope(project, selectedTeams)
                } else {
                    logger.warn("No teams selected")
                }
            }
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(createScopeButton)
            add(Box.createHorizontalGlue())
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Code Owners File:", codeOwnersPathField)
            .addLabeledComponent("Search Teams:", searchField)
            .addComponent(JBScrollPane(teamList))
            .addComponent(buttonPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel
    }

    private fun loadTeams(codeOwnersService: CodeOwnersService?) {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        selectedTeams = settings.selectedTeams

        allTeams = try {
            codeOwnersService?.getAllTeams() ?: emptyList()
        } catch (e: Exception) {
            logger.error("Failed to get teams from service", e)
            emptyList()
        }
        filterTeams()
    }

    private fun saveSelectedTeams() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        val selectedTeams = mutableListOf<String>()
        teamList.let { list ->
            for (i in 0 until list.itemsCount) {
                if (list.isItemSelected(i)) {
                    selectedTeams.add(list.getItemAt(i)!!)
                }
            }
        }
        settings.updateSelectedTeams(selectedTeams)
    }

    private fun filterTeams() {
        val searchText = searchField.text?.lowercase().orEmpty()
        val filteredTeams = allTeams.filter { it.lowercase().contains(searchText) }
        logger.info("Selected teams: $selectedTeams")
        val selectedTeamsOnTop = filteredTeams.sortedByDescending { selectedTeams.contains(it) }
        teamList.clear()
        selectedTeamsOnTop.forEach { team ->
            teamList.addItem(team, team, selectedTeams.contains(team))
        }
    }

    private fun createFileScope(project: Project, teams: List<String>) {
        val codeOwnersService = project.getService(CodeOwnersService::class.java) ?: return
        val rules = codeOwnersService.getCodeOwnerRules()

        // Get all paths for selected teams
        val teamPaths = teams.flatMap { teamName ->
            rules.find { it.name == teamName }?.paths ?: emptyList()
        }.distinct()

        if (teamPaths.isEmpty()) {
            return
        }

        // Create package set for inclusion paths
        val inclusionPaths = teamPaths.filter { !it.startsWith("!") }
        val exclusionPaths = teamPaths.filter { it.startsWith("!") }.map { it.substring(1) }

        val packageSetBuilder = buildString {
            // Always include root files
            append("(file[*]:*||")

            // Add inclusion paths
            if (inclusionPaths.isNotEmpty()) {
                append('(')
                append(inclusionPaths.joinToString("||") { "file[*]:${it.toPath()}" })
                append(')')
            }

            // Add exclusion paths
            if (exclusionPaths.isNotEmpty()) {
                if (inclusionPaths.isNotEmpty()) append("&&")
                append('(')
                append(exclusionPaths.joinToString("||") { "!file[*]:${it.toPath()}" })
                append(')')
            }
            append(')')
        }

        logger.info("Creating package set: $packageSetBuilder")
        val packageSet = PackageSetFactory.getInstance().compile(packageSetBuilder)

        // Create named scope
        val scopeName = "Code Owners: ${teams.joinToString(", ")}"
        val namedScope = NamedScope(scopeName, packageSet)

        // Get the scope manager and update scopes
        val scopeManager = project.getService(NamedScopeManager::class.java)
        val currentScopes = scopeManager.editableScopes
        val updatedScopes = currentScopes.filter { it.scopeId != scopeName } + namedScope
        scopeManager.scopes = updatedScopes.toTypedArray()
    }

    private fun String.toPath(): String {
        val path = if (endsWith("/*")) "${this}//*" else this
        return path.removePrefix("/")
    }

    override fun isModified(): Boolean {
        val currentSelection = currentSelection()
        val currentPath = codeOwnersPathField.text
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return false
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        return currentSelection != selectedTeams || currentPath != settings.codeOwnersPath
    }

    override fun apply() {
        val currentSelection = currentSelection()
        val currentPath = codeOwnersPathField.text
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        settings.selectedTeams = currentSelection
        settings.codeOwnersPath = currentPath
        selectedTeams = currentSelection
    }

    private fun currentSelection(): MutableList<String> {
        val currentSelection = mutableListOf<String>()
        teamList.let { list ->
            for (i in 0 until list.itemsCount) {
                if (list.isItemSelected(i)) {
                    currentSelection.add(list.getItemAt(i)!!)
                }
            }
        }
        return currentSelection
    }

    override fun reset() {
        searchField.text = ""
        val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        val settings = project.getService(CodeOwnersSettingsState::class.java)
        codeOwnersPathField.text = settings.codeOwnersPath
        filterTeams()
    }

    override fun disposeUIResources() {

    }
}
