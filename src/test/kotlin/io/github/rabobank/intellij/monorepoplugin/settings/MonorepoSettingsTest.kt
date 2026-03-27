package io.github.rabobank.intellij.monorepoplugin.settings

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoPluginStorage
import javax.swing.JPanel
import javax.swing.JTextField

class MonorepoSettingsTest : BasePlatformTestCase() {
    private lateinit var codeOwnersStorage: MonorepoPluginStorage

    override fun setUp() {
        super.setUp()
        codeOwnersStorage = myFixture.project.service()
    }

    fun `test file selector updates settings state`() {
        val configurable = MonorepoSettings(project)
        val component = configurable.createComponent()
        val fileSelector =
            component.components
                .filterIsInstance<JPanel>()
                .first()
                .components
                .filterIsInstance<JTextField>()
                .first()
        fileSelector.text = "path/to/code-owners.json"
        configurable.apply()
        assertEquals("path/to/code-owners.json", codeOwnersStorage.state.codeOwnersPath)
    }
}
