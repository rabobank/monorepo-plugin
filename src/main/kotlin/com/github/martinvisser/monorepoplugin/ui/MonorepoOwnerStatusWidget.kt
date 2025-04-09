package com.github.martinvisser.monorepoplugin.ui

import com.github.martinvisser.monorepoplugin.ResourceBundle
import com.github.martinvisser.monorepoplugin.services.MonorepoPluginStorage
import com.github.martinvisser.monorepoplugin.services.MonorepoService
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class MonorepoOwnerStatusWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.TextPresentation,
    DumbAware {
    private val service = project.service<MonorepoService>()
    private val storage = project.service<MonorepoPluginStorage>()

    private var lastText: String = ""

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        // listen to file editor changes to update text
        val connection = project.messageBus.connect()
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(
                    source: FileEditorManager,
                    file: VirtualFile,
                ) {
                    update(file, statusBar)
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    event.newFile?.run { update(this, statusBar) }
                }
            },
        )
        invokeLater {
            FileEditorManager
                .getInstance(project)
                .selectedEditor
                ?.file
                ?.run { update(this, statusBar) }
        }
    }

    private fun update(
        file: VirtualFile,
        statusBar: StatusBar,
    ) {
        val text = service.getTeamForFile(file).orEmpty()
        if (text != lastText) {
            lastText = text
            statusBar.updateWidget(ID)
        }
    }

    override fun getPresentation(): WidgetPresentation = this

    override fun getText(): String =
        if (lastText.isBlank()) "" else ResourceBundle.message("widget.code.owner", lastText)

    override fun getAlignment(): Float = 0.5f

    override fun getTooltipText(): String? =
        if (lastText.isBlank()) {
            ResourceBundle.message("widget.no.owner.found")
        } else {
            ResourceBundle.message("widget.code.owner.apply.filter", lastText)
        }

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer { _ ->
            val team = lastText.ifBlank { null } ?: return@Consumer
            storage.state.selectedTeams = mutableSetOf(team)
            // apply filter using service
            service.applyFilter(project)
            // request UI update
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }

    override fun dispose() {}

    companion object {
        const val ID = "Monorepo Status bar widget"
    }
}
