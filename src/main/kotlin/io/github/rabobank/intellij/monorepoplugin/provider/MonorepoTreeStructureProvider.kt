package io.github.rabobank.intellij.monorepoplugin.provider

import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.components.service
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoPluginStorage

class MonorepoTreeStructureProvider : TreeStructureProvider {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: MutableCollection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): MutableCollection<AbstractTreeNode<*>> {
        val project = parent.project ?: return children
        val storage = project.service<MonorepoPluginStorage>()
        val excludedUrls = storage.state.pluginExclusions

        return children
            .filterNot { node ->
                (node is PsiFileNode && excludedUrls.contains(node.virtualFile?.url))
            }.toMutableList()
    }
}
