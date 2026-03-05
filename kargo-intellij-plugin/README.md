# Kargo IntelliJ Plugin

This module contains the Kargo integration for IntelliJ-based IDEs.
It was ported directly from Amper's plugin logic to minimize behavioral divergence, focusing purely on Kargo functionality.

## Features

- **Accurate Project Structure:** Maps `ContentEntry` URLs directly to the physical source parent directory in `KargoWorkspaceModelUpdater`. This ensures real 1:1 file system representation in the IDE without creating artificial virtual roots.
- **Native Floating Sync:** Uses IntelliJ's `ExternalSystemProjectTracker` for a seamless, floating "Sync Kargo" action when `project.yaml` or `module.yaml` changes, avoiding intrusive editor banners.
- **Clean Project View:** Employs a custom `ProjectViewNodeDecorator` to strip IntelliJ's default `[module-name]` suffixes from folders, keeping the file tree identical to physical directories.
- **Deep UI Integration:** High-quality Kargo SVG icons are automatically applied to Run Configurations, "Open Project" wizards, configuration files in the Project View, and the IDE's Plugins page.
- **Optimized & Lean:** A streamlined codebase with robust reflections to support both Kotlin K2 and older compiler APIs, without the overhead of telemetry or unused legacy code.

## Development

The plugin logic is written in Kotlin and built using Kargo itself (no Gradle!).
It relies on the `build.kargo.*` namespace.

### Building

To compile the plugin classes, run from the repository root:

```bash
cd kargo-intellij-plugin
./../kargo build
```

### Packaging

The IntelliJ platform expects plugins to be distributed as a zip archive containing a specific directory structure.
Since we are using Kargo to build this module instead of Gradle, we use a custom script to package the compiled classes:

```bash
# Run from repository root
python kargo-intellij-plugin/scripts/package-plugin.py
```

This will create `kargo-intellij-plugin/build/kargo-intellij-plugin-1.0-SNAPSHOT.zip`, which you can install into your IDE via `Install Plugin from Disk...`.
