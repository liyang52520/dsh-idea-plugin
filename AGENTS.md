# AGENTS.md — DeepSeek Harness IntelliJ IDEA Plugin

## Project Overview

A Kotlin IntelliJ Platform plugin that embeds DeepSeek Harness (DSH) as a tool window
inside IntelliJ IDEA. It launches the Node.js + DeepSeek Harness runtime that the user has
already installed (paths configured in plugin Settings), hosts an embedded JCEF browser,
and an in-process MCP endpoint for IDE tools and AI-assisted code review.

## Build & Test

### Prerequisites

- macOS arm64 (primary target)

- JDK 17+

- Node.js at `/Users/liyang/.nvm/versions/node/v24.15.0/bin/node`

- DSH at `/Users/liyang/.nvm/versions/node/v24.15.0/lib/node_modules/@deepseek-ai/dsh/lib/bin.js`
  (these two paths are exactly what goes into the plugin Settings → Node.js path / DSH path)

- IntelliJ IDEA Ultimate 2026.2.1 at `/Applications/IntelliJ IDEA.app/Contents`

- Gradle toolchain: the IDE's own JBR 25 (`.../jbr/Contents/Home`) must be registered via
  `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties` — IPGP 2.12+ maps
  the platform Java (IDEA 2026.2 = Java 25) onto compile tasks and Gradle resolves it as a toolchain.

### Build

```bash
./gradlew buildPlugin --console=plain --no-daemon \
  -PlocalIdePath="/Applications/IntelliJ IDEA.app/Contents" \
  -x test
```

Artifact: `build/distributions/deepseek-harness-idea-1.0.0.zip`

### Constraints

- IntelliJ Platform Gradle Plugin **2.18.1** (`org.jetbrains.intellij.platform`).
  NOT the legacy `org.jetbrains.intellij` 1.x line (it only supports platforms ≤ 2024.1
  and prints a loud warning against 242+). Requires Gradle 9.0.0+ — the wrapper is
  **Gradle 9.5.1** and the Kotlin Gradle Plugin is **2.4.10** (KGP 2.0.x is incompatible
  with Gradle 9; KGP 2.4.0–2.4.10 fully supports Gradle ≤ 9.5).

- **`-PlocalIdePath`** **is required** — without it, Gradle downloads the IntelliJ Platform
  SDK (\~1.5GB) from JetBrains. Always pass the path to the local IDE installation to
  resolve SDK jars locally.

- Instrumentation and searchable-options are disabled via the `intellijPlatform`
  extension in `build.gradle.kts` (no `-x instrumentCode` needed).

- Plugin compatibility range is managed in `intellijPlatform.pluginConfiguration.ideaVersion`
  (241 → 262.\*) — do NOT remove it, otherwise IPGP re-defaults `since-build` to the local
  IDE build (e.g. 262.9437), silently narrowing the supported IDE range.

- `compileKotlin`/`compileJava` bytecode targets are explicitly pinned to **Java 17** in
  `build.gradle.kts` (task-level, overriding IPGP's platform-Java-25 convention). The plugin
  targets `since-build 241`, which must also load on IDEs running JBR 17/21 — do not remove
  those pins (the compiler itself runs on the JBR 25 toolchain; only the output target is 17).

- `runIde` fails on macOS with "Index: 1, Size: 1" — install zip manually

- `compileOnly` for JCEF jars, NOT `implementation`

- Network: `plugins.gradle.org` is reachable (direct, or via `export all_proxy=http://127.0.0.1:7890`)

## Architecture

```
src/main/kotlin/com/yg/dsh/idea/
├── config/          # Configuration & credentials
│   ├── CredentialFileWatcher.kt  # File watcher: sync API key changes from DSH web UI → PasswordSafe
│   ├── Credentials.kt            # API key read/write (PasswordSafe + credential file fallback)
│   ├── DshHomeManager.kt         # DSH_HOME maintenance, config sync, runtime path resolution (settings-only; no process management)
│   └── ProviderSettingsWriter.kt # YAML generation for settings.yaml + .credentials.yaml
├── i18n/            # Internationalization
│   └── I18nBundle.kt             # DynamicBundle wrapper for messages/*.properties
├── mcp/             # In-JVM MCP endpoint (IDE-side server; no separate Node.js process)
│   ├── IdeMcpServer.kt           # Loopback HTTP server: POST /mcp (JSON-RPC) + GET /health; token auth; writes ide.yml patch
│   ├── McpRpcHandler.kt          # Stateless streamable-http JSON-RPC 2.0 handler with 7 IDE tools (direct PSI/editor/VFS access)
│   ├── PatchGenerator.kt         # Generates ide.yml for --patch overlay (mcp-client config)
│   └── SentSelectionQueue.kt     # Ring buffer for sent-selection (≤10 entries, ≤64KB each)
├── review/          # AI review workflow
│   ├── ReviewDialog.kt           # Diff list dialog (restore, ignore, rebaseline, show diff)
│   ├── ReviewManager.kt          # Diff computation, show diff, restore/ignore/rebaseline
│   ├── SnapshotDiff.kt           # Baseline vs current diff algorithm (MODIFIED/NEW/DELETED)
│   └── SnapshotManager.kt        # Baseline snapshot with LRU spill-to-disk (≤200MB memory)
├── runtime/         # DSH process lifecycle
│   ├── AppShutdownListener.kt    # IDE exit hook: dispose all DSH panels
│   ├── DshApiClient.kt           # DSH backend /api/ JSON-RPC client (goal.create for send-question)
│   ├── DshSessionLauncher.kt     # Bootstrap orchestration: ensureHome → patch → credentials → process; returns DshSession
│   ├── LegacySessionMigrator.kt  # v0.1.2 → v0.1.3+ session migration (with sentinel)
│   ├── PanelRegistry.kt          # Project open/close lifecycle + IDE exit cleanup
│   ├── RuntimeRegistry.kt        # Live-instance registry per project (no concurrency cap; tracking + cleanup)
│   ├── WorkspaceInitializer.kt   # Auto-register project as DSH workspace on startup
│   └── process/        # DSH subprocess management
│       ├── PortParser.kt             # Parse "dsh web: http://127.0.0.1:<port>" from stdout
│       └── ProcessManager.kt         # Start/stop/monitor DSH Node process (port discovery, health check, crash restart)
├── settings/        # Persistent settings + IDE Settings page
│   ├── DshSettingsConfigurable.kt # Settings → Tools → DeepSeek Harness (UI DSL v2; runtime paths, provider list, advanced)
│   └── SettingsState.kt          # ProviderConfig, node/dsh paths, args
├── ui/              # Tool window & actions
│   ├── actions/
│   │   ├── OpenSettingsAction.kt  # Opens the IDE Settings dialog (ShowSettingsUtil → DshSettingsConfigurable)
│   │   ├── RestartAction.kt
│   │   ├── ReviewChangesAction.kt     # Toolbar: open review diff dialog
│   │   ├── SendLogExplanationAction.kt # Send console log to DSH for explanation (Ctrl+Alt+Shift+D)
│   │   └── SendSelectionAction.kt     # Send selection to DSH (Ctrl+Alt+K)
│   ├── BrowserManager.kt          # JCEF browser lifecycle, composer draft injection (the only JS injection point)
│   ├── CardBuilder.kt             # UI card construction (loading/error/guide)
│   ├── DeepSeekKeyDialog.kt       # Built-in DeepSeek API key dialog (password safe + test connection)
│   ├── LogExplanationBuilder.kt   # "DSH 一键解释" message builder
│   ├── ProviderEditorDialog.kt    # Custom provider add/edit dialog (UI DSL v2, duplicate-ID validation, fetch models)
│   ├── ToolWindowFactory.kt       # ToolWindowFactory (registers tool window + toolbar actions)
│   └── ToolWindowPanel.kt         # Tool window panel (card presentation only; startup delegated to DshSessionLauncher; guide card when unconfigured; onSettingsApplied callback)
└── util/            # Shared utilities (no platform dependencies)
    ├── Constants.kt               # Shared constants (loopback host, default model, file names)
    ├── FileUtils.kt               # Atomic file write with chmod 600 for credentials
    ├── HashUtils.kt               # MD5 helpers
    ├── JsonCodec.kt               # Minimal JSON encoder/decoder (no Gson dependency)
    ├── LanguageDetector.kt        # PSI language detection for VirtualFile
    ├── Notifications.kt           # IDE balloon notifications + clipboard
    ├── Platform.kt                # OS/arch detection
    ├── ProjectIgnoreRules.kt      # Snapshot/project-tree ignore rules (dirs, hidden files, >1MB)
    └── TextUtils.kt               # UTF-8 truncation, HTML/JS escaping
```

## Tool Window Layout

`ToolWindowPanel` uses `BorderLayout` with a nested `CardLayout`:

- **CENTER**: `CardLayout` panel with cards: `loading`, `browser`, `error`, `guide`

- Cards are heavyweight (JCEF browser) — do NOT overlay lightweight Swing components

### Toolbar actions (left to right):

1. Review Changes (`AllIcons.Actions.Diff`)
2. Restart (`AllIcons.Actions.Restart`)
3. Settings (`AllIcons.General.Settings`)

## Key Config Files

| File                                | Purpose                                             |
| ----------------------------------- | --------------------------------------------------- |
| `~/.dsh/settings.yaml`              | Provider config, default model                      |
| `~/.dsh/.credentials.yaml`          | API keys (`PROVIDER_NAME_API_KEY`)                  |
| `<config>/dsh-idea/dsh-home/`       | Global DSH config (shared across projects)          |
| `<config>/dsh-idea/dsh-home/<md5>/` | Per-project DSH home (isolated sessions/workspaces) |

There is **no** `<config>/dsh-idea/runtime/` directory anymore: the plugin never bundles or
downloads a runtime. Node.js + DeepSeek Harness come exclusively from the paths configured in
plugin Settings (`nodePath` / `dshPath`), and `DshHomeManager.hasRuntime()` is true only when
both configured files exist.

## Provider Configuration

`SettingsState.providers` holds `List<ProviderConfig>`. On save:

- `syncProviderSettings()` writes `settings.yaml` with `apiKeyEnv: PROVIDER_API_KEY`

- API keys written to `.credentials.yaml` as `PROVIDER_API_KEY: <value>`

- Both skip writes if content is unchanged (content comparison)

## Icons

| File                       | Usage                                           |
| -------------------------- | ----------------------------------------------- |
| `icons/deepseek.svg`       | Plugin icon, loading logo                       |
| `icons/deepseek-light.svg` | Tool window icon (visible on blue selection bg) |
| `icons/deepseek-16.png`    | Menu action icons (16×16)                       |

Generated via `rsvg-convert`:

```bash
rsvg-convert -w 16 -h 16 icons/deepseek.svg -o icons/deepseek-16.png
```

## i18n

- `src/main/resources/messages/DshBundle.properties` — English

- `src/main/resources/messages/DshBundle_zh_CN.properties` — Chinese

## DSH Process Lifecycle

1. `DshHomeManager.hasRuntime()` checks that the Node.js + DSH paths configured in Settings
   point at existing files — no provisioning/download (settings-only runtime)
2. `ToolWindowPanel.start()` registers the instance via `RuntimeRegistry.register`
   (no concurrency cap — every open project may run its own DSH); **on bootstrap failure
   the registration is released** (rollback in the catch block)
3. `DshSessionLauncher.launch()` runs on a pooled thread, synchronously:

   - `DshHomeManager.ensureHome()` (skips `copyGlobalConfigTo` / `syncProviderSettings` /
     `migrateLegacySessions` when content unchanged / sentinel `.migration-done` exists)

   - `IdeMcpServer` constructed in-process: HTTP server ready immediately (no subprocess,
     no port-wait, no silent empty-patch degradation); `writePatch()` writes `ide.yml` before
     the DSH process starts

   - `syncCredentials()` + `CredentialFileWatcher` registration (watches per-project
     `.credentials.yaml` for DSH Web UI key changes)

   - `ProcessManager` spawn
4. `ProcessManager` spawns `node <dshBin>` with `--patch ide.yml`

   - Port discovery: parses `dsh web: http://127.0.0.1:<port>` from stdout (30s timeout guard)

   - Health check: 6 tries × 500ms = 3s max

   - Crash restart: 3 attempts with backoff (500ms / 2s / 5s), then GAVE\_UP + error card

   - Auto-registers project as workspace (`WorkspaceInitializer.ensureWorkspace`)
5. JCEF browser (`BrowserManager`) loads the URL; the only JS injection is the on-demand
   composer draft insertion (`injectToComposer`) — locale/welcome-notice are handled via
   settings.yaml, auto-send via backend RPC (`DshApiClient`)
6. Panel lifecycle: `PanelRegistry` tracks live panels per project; `AppShutdownListener` disposes all on IDE exit

MCP request threading: JSON-RPC requests are served on the IdeMcpServer HTTP worker threads —
synchronous VFS refresh and file IO happen there (never on EDT), pure UI actions
(open/reveal file) are dispatched to the EDT via `invokeLater`.

## Shared Utilities

| Utility                       | Used by                                                                                          |
| ----------------------------- | ------------------------------------------------------------------------------------------------ |
| `TextUtils.truncateUtf8`      | `SentSelectionQueue`, `SendSelectionAction`, `LogExplanationBuilder`                             |
| `TextUtils.escapeHtml`        | `ToolWindowPanel` (error display)                                                                |
| `TextUtils.escapeJs`          | `BrowserManager` (JCEF script injection)                                                         |
| `LanguageDetector.languageOf` | `McpRpcHandler`, `SendSelectionAction`                                                           |
| `HashUtils.md5`               | `DshHomeManager`, `ReviewManager`, `SnapshotManager`                                             |
| `FileUtils.writeUtf8`         | `DshHomeManager`, `ProviderSettingsWriter`                                                       |
| `Constants.*`                 | All files that previously hardcoded `"127.0.0.1"`, `"deepseek-chat"`, `"DEEPSEEK_API_KEY"`, etc. |

## Troubleshooting

### MCP / IDE Tools Not Working

When the DSH model says "I don't have access to IDE state" or similar, the MCP chain
likely broke. The endpoint lives in-JVM (`IdeMcpServer` + `McpRpcHandler`), so check:

1. **`ide.yml`** content in the per-project home
   (`<config>/dsh-idea/dsh-home/<md5>/ide.yml`) — it must contain the mcp-client config
   with `url: http://127.0.0.1:<port>/mcp` and the `X-DSH-IDE-Token` header, NOT `[]` (empty).
   An empty patch means `writePatch()` never ran — look for bootstrap errors in `idea.log`.
2. **Endpoint liveness** — `GET http://127.0.0.1:<port>/health` (same port as in ide.yml)
   must answer 200 with the correct token header; 401 means token mismatch, connection
   refused means the server was disposed (panel closed).
3. **IDE log** — `grep "mcp\|IdeMcpServer\|writePatch" idea.log` shows the exact failure point.

## Known Issues

- `runIde` fails on macOS — use manual zip install

- Instrumentation/searchable-options are disabled via the `intellijPlatform` extension
  (legacy 1.x used `-x instrumentCode`; no longer needed on IPGP 2.x)

- JCEF heavyweight component covers lightweight Swing — use BorderLayout

- Google Analytics argument removed from DSH start command (ARCH-1)

- `$settings`/`$credentials` directives removed from MCP patches (ARCH-1)

