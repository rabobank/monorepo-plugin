# Monorepo Plugin

<div align="center">
  <picture>
    <source srcset="src/main/resources/icons/codeOwners.svg" media="(prefers-color-scheme: dark)">
    <img src="src/main/resources/icons/codeOwners.svg" alt="Monorepo Plugin Icon" width="96" height="96" style="margin-bottom: 0.5em;" />
  </picture>
</div>

![Build](https://github.com/martinvisser/monorepo-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/io.github.rabobank.monorepoplugin.svg)](https://plugins.jetbrains.com/plugin/31169-monorepo)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/io.github.rabobank.monorepoplugin.svg)](https://plugins.jetbrains.com/plugin/31169-monorepo)

## Overview

Monorepo Plugin is an IntelliJ-based plugin designed to improve navigation and productivity in large monorepo projects.
It enables teams to filter and focus the project view based on code ownership, as defined in a `code-owners.json`
configuration file. This helps developers quickly find and work with files relevant to their team, reducing noise and
improving collaboration in complex repositories.

### Key Features

- **Project View Filtering:** Limit visible files and folders to those owned by specific teams, as defined in
  `code-owners.json`.
- **Favorite Teams:** Mark teams as favorites for quick access and filtering.
- **Customizable Configuration:** Easily set the path to your `code-owners.json` file in the plugin settings.
- **Flexible Filtering:** Apply and clear filters as needed to switch between focused and full project views.

This plugin is ideal for organizations using a monorepo structure with multiple teams, making it easier to manage code
ownership and streamline development workflows.

### Example `code-owners.json`

The configuration can become quite large in real projects, so here is a shortened example. `[...]` indicates that more
teams or paths can be added.

```json
{
    "teams": {
        "Aurora Builders": {
            "paths": [
                "/apps/sunrise-portal/*",
                "/libs/ui/sky-panels/*",
                "[...]"
            ]
        },
        "Midnight Cartographers": {
            "paths": [
                "/services/map-engine/*",
                "/packages/navigation/shared/*",
                "!/packages/navigation/shared/legacy/*"
            ]
        },
        "[...]": {
            "paths": [
                "[...]"
            ]
        }
    }
}
```

Paths starting with `!` exclude matches from a broader included path. In the example above,
`"/packages/navigation/shared/*"` is included for `Midnight Cartographers`, while
`"!/packages/navigation/shared/legacy/*"` excludes the legacy subfolder from that team.

## Installation

You can install Monorepo Plugin in several ways:

- **Using the IDE built-in plugin system:**
    - Go to <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "
      Monorepo"</kbd> > <kbd>Install</kbd>

- **Using JetBrains Marketplace:**
    - Visit [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31169-monorepo) and click <kbd>Install
      to ...</kbd> if your IDE is running.
    - Or download the [latest release](https://plugins.jetbrains.com/plugin/31169-monorepo/versions) and install it
      manually via <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from
      disk...</kbd>

- **Manual installation:**
    - Download the [latest release](https://github.com/rabobank/monorepo-plugin/releases/latest) and install it manually
      using <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from
      disk...</kbd>

[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

<!-- Plugin description -->
This plugin allows users to filter the project view based on the code-owners.json configuration.
It enables limiting visible files to those owned by specific teams, making it easier to navigate and manage monorepo
projects.
Users can configure favorite teams, apply filters, and customize the code-owners.json path through the settings.
<!-- Plugin description end -->
