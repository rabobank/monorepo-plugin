package io.github.rabobank.intellij.monorepoplugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import io.github.rabobank.intellij.monorepoplugin.ResourceBundle

class MonorepoOwnerStatusWidgetFactory :
    StatusBarWidgetFactory,
    DumbAware {
    override fun getId() = MonorepoOwnerStatusWidget.ID

    override fun getDisplayName() = ResourceBundle.message("widget.monorepo.owner.name")

    override fun isAvailable(project: Project) = true

    override fun createWidget(project: Project) = MonorepoOwnerStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {}

    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
