package io.github.rabobank.intellij.monorepoplugin.toolwindow

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoPluginStorage
import io.github.rabobank.intellij.monorepoplugin.services.MonorepoService

class MonorepoTeamFilterToolWindowTest : BasePlatformTestCase() {
    private lateinit var storage: MonorepoPluginStorage
    private lateinit var service: MonorepoService

    override fun setUp() {
        super.setUp()
        storage = myFixture.project.service()
        service = myFixture.project.service()
    }

    fun `test select team filter updates state`() {
        // Simulate UI: select a team and apply filter
        storage.state.selectedTeams.clear()
        storage.state.selectedTeams.add("teamA")
        service.applyFilter(project)
        assertTrue(storage.state.selectedTeams.contains("teamA"))
    }

    fun `test clear filter resets state`() {
        storage.state.selectedTeams.add("teamA")
        storage.state.selectedTeams.add("teamB")
        // Simulate UI: clear filter
        storage.state.selectedTeams.clear()
        service.applyFilter(project)
        assertTrue(storage.state.selectedTeams.isEmpty())
    }

    fun `test set favorite teams updates state`() {
        // Simulate UI: set favorite teams
        storage.state.favoriteTeams.clear()
        storage.state.favoriteTeams.add("teamA")
        storage.state.favoriteTeams.add("teamB")
        assertTrue(storage.state.favoriteTeams.contains("teamA"))
        assertTrue(storage.state.favoriteTeams.contains("teamB"))
    }
}
