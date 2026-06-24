# FNM Version Switcher — JetBrains Plugin

A JetBrains IDE plugin that automatically keeps your project's Node.js interpreter in sync with your `.node-version` / `.nvmrc` file, powered by [fnm](https://github.com/Schniz/fnm).

Supported IDEs: **WebStorm** and **IntelliJ IDEA Ultimate** (any build ≥ 242).

---

## What it does

| Trigger | Action |
|---------|--------|
| Project opens | Reads the version file and switches the Node.js interpreter |
| Git branch changes | Re-reads the version file for the new branch and switches if needed |
| `.node-version` / `.nvmrc` saved | Immediately re-applies the correct interpreter |
| Required version not installed | Installs it via `fnm install` (auto or with a prompt) |

A **status bar widget** shows the active Node version at a glance. Clicking it opens a picker listing all fnm-installed versions so you can switch manually — useful in monorepos where different sub-projects need different versions.

---

## Requirements

- [fnm](https://github.com/Schniz/fnm) installed and reachable (Homebrew, the install script, Winget, etc.)
- WebStorm **2024.2+** or IntelliJ IDEA Ultimate **2024.2+**
- A `.node-version` or `.nvmrc` file in the project root (or any sub-directory for multi-module projects)

### WSL

When WebStorm runs on Windows but the project lives in a WSL distribution (its path is a
`\\wsl$\…` / `\\wsl.localhost\…` UNC path), the plugin detects this and uses the **WSL**
toolchain: it runs `fnm` through `wsl.exe`, reads `FNM_DIR`/Node from the Linux filesystem,
and configures a WSL Node interpreter. Install fnm **inside** the distribution for this to work
(a Windows-side fnm.exe is not used for WSL projects).

---

## Installation

### From the marketplace (once published)

1. Open **Settings → Plugins → Marketplace**
2. Search for **FNM Version Switcher**
3. Install and restart

### From a local build

```bash
./gradlew buildPlugin
# Produces build/distributions/fnm-webstorm-*.zip
```

Then install via **Settings → Plugins → ⚙️ → Install Plugin from Disk…**

---

## Usage

The plugin works automatically — open a project that has a `.node-version` or `.nvmrc` file and the interpreter is switched without any manual steps.

### Settings

Open **Settings → Tools → FNM Version Switcher** to configure:

| Setting | Default | Description |
|---------|---------|-------------|
| fnm binary path | auto-detect | Full path to the `fnm` executable. Leave blank to auto-detect from the login-shell `PATH` and standard install locations. |
| FNM_DIR | auto-detect | Directory where fnm stores Node versions (`$FNM_DIR`). Leave blank to read from the environment or use platform defaults. |
| Auto-install missing versions | ✅ enabled | Silently runs `fnm install <version>` when the required version is not yet installed. Disable to get a prompt instead. |
| Auto-switch on project open | ✅ enabled | Checks and switches the interpreter when a project is loaded. |
| Auto-switch on branch change | ✅ enabled | Re-checks after every Git branch switch. |
| Show notification on switch | ✅ enabled | Displays a brief balloon after a successful interpreter switch. |

Settings are persisted per-project in `.idea/fnm.xml`.

### Version file format

Both file types are supported. The leading `v` is optional. Partial versions (`20`, `20.9`) resolve to the highest matching installed patch version.

```
# .node-version or .nvmrc
20.9.0
```

---

## Code overview

```
src/main/kotlin/eu/hannaweb/fnm/
│
├── listeners/
│   ├── FnmStartupActivity.kt   # PostStartupActivity — triggers version check on project open
│   ├── FnmBranchListener.kt    # GitRepositoryChangeListener — detects branch switches
│   └── FnmFileListener.kt      # BulkFileListener — watches .node-version / .nvmrc saves
│
├── services/
│   ├── FnmAppService.kt        # App-level service: fnm binary detection, install, list
│   ├── FnmProjectService.kt    # Project-level orchestrator: version detection & interpreter switching
│   └── FnmTarget.kt            # Host vs. WSL toolchain abstraction: paths, process invocation, interpreter type
│
├── settings/
│   ├── FnmSettings.kt          # PersistentStateComponent — per-project settings (.idea/fnm.xml)
│   └── FnmSettingsConfigurable.kt  # Swing settings panel (Settings → Tools → FNM Version Switcher)
│
├── notifications/
│   └── FnmNotifications.kt     # Thin wrapper around NotificationGroupManager
│
└── ui/
    └── FnmStatusBarWidget.kt   # Status bar widget + factory — shows active version, manual picker
```

### Key design notes

- **`FnmTarget`** encapsulates everything that differs between running fnm on the local host and inside a WSL distribution: path layout, process invocation, login-shell/env resolution, and interpreter type. Every other class talks to this interface instead of branching on the OS.
- **`FnmAppService`** (application-scoped) is a thin orchestrator over a given `FnmTarget`: detecting/caching the fnm binary and `FNM_DIR`, listing installed versions, and running installs. It has no OS-specific logic of its own.
- **`FnmProjectService`** (project-scoped) is the single entry point for all three triggers. It resolves the project's `FnmTarget`, caches `lastAppliedVersion` to skip redundant switches, and resolves partial version specifiers (e.g. `"20"` → `"20.9.0"`) by scanning the fnm versions directory.
- The plugin depends on `NodeJsInterpreterManager` from the bundled WebStorm JavaScript plugin, which is not a stable public API. All calls are wrapped defensively so that API changes on an IDE upgrade surface as logged warnings rather than crashes.

---

## Development

```bash
# Run the plugin inside a sandboxed WebStorm instance
./gradlew runIde

# Build a distributable zip
./gradlew buildPlugin

# Run tests
./gradlew test
```

The project targets **Kotlin JVM** and uses the [IntelliJ Platform Gradle Plugin v2](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).
