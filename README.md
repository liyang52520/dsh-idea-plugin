# DeepSeek Harness for IntelliJ IDEA

把 DeepSeek Harness 智能体工作台完整嵌入 IntelliJ IDEA，提供类 Qoder 的 AI 编程体验：
在 IDE 内嵌的 Web 界面中与智能体对话，让它读写你的项目文件，通过进程内 MCP 工具获取 IDE 上下文，
并用原生 diff 工具审查/还原它的改动。

Embed the [DeepSeek Harness](https://github.com/deepseek-ai) AI agent workbench directly inside
IntelliJ IDEA for a Qoder-like AI coding experience: chat with the agent in an embedded web UI,
let it read and modify your project files, expose IDE context via an in-process MCP endpoint,
and review/restore its changes with native diff tooling.

> 声明 / Disclaimer：本插件非 DeepSeek 官方产品；DeepSeek Harness 与 DeepSeek 商标归其各自所有者。
> This plugin is not an official DeepSeek product; DeepSeek Harness and the DeepSeek trademark belong
> to their respective owners.

---

## 功能特性 / Features

- **内嵌 Web UI / Embedded Web UI** — 完整 DeepSeek Harness 界面（对话、会话、目标、工作流）通过 JCEF 在 IDE 工具窗口运行，体验与浏览器版一致。
- **以项目为工作区 / Workspace-bound agent** — 智能体以当前项目目录为工作区（启动时自动注册），可读取、新建、修改项目文件。
- **进程内 MCP IDE 工具 / IDE context via in-process MCP** — 智能体可通过 `mcp__ide__*` 工具读取当前选中代码、打开的文件、项目树，并打开/定位文件；MCP 终结点与 IDE 同 JVM 进程（无独立翻译进程、随机 token 鉴权）。
- **发送选中代码 / Send selection to DSH** — 右键选中代码发送紧凑文件引用（`@路径#L1-14`）；完整代码可随时被智能体拉取（剪贴板兜底）。
- **运行日志一键解释 / One-click log explanation** — 在项目运行控制台选中一段日志，右键"DSH 一键解释"，自动把解释请求 + 日志提交给 DSH（无需手动粘贴/回车）。
- **每项目独立工作区 / Per-project workspace** — 每个项目使用独立的 DSH_HOME，工作区按项目隔离；旧版单 Home 的会话数据自动迁移到对应项目的工作区。
- **审查与还原 / Review & restore** — 基线快照 + 原生 diff（修改/新增/删除），支持还原单个/全部、忽略、重新基线。
- **多服务商支持 / Multi-provider support** — 除内置 DeepSeek 外，支持添加自定义服务商（OpenAI、Anthropic 等兼容 API），可为每个服务商配置多个模型，支持从 API 端点一键拉取模型列表。
- **使用本机 Node.js 与 DSH / Uses your installed Node.js & DSH** — 插件直接启动你本机已安装的 DeepSeek Harness：在设置中填入 Node.js 与 `@deepseek-ai/dsh/lib/bin.js` 路径即可，不内置、也不下载任何运行时。
- **生命周期管理 / Lifecycle** — 每项目一个实例（并发上限 3）；项目关闭 / IDE 退出自动终止进程；崩溃自动退避重启（最多 3 次）。
- **中英双语界面 / Bilingual UI** — 插件 UI 跟随 IDE 语言（English / 简体中文）。

## 环境要求 / Requirements

- IntelliJ IDEA Community / Ultimate **2024.1 – 2026.2**（build 241 – 262；Windows 10/11 x64、macOS arm64/x64、Linux x64）
- 持有 DeepSeek API Key（`deepseek-chat` / `deepseek-reasoner`），或自定义服务商的 API Key
- 本机已安装 **Node.js** 与 **DeepSeek Harness**（`npm i -g @deepseek-ai/dsh`），并在插件设置中填入两者的路径（Node.js 可执行文件 与 `…/@deepseek-ai/dsh/lib/bin.js`）。插件不内置也不下载运行时。

## 安装 / Install

1. 构建插件 zip（见下）或下载 [Releases](../../releases) 中的 `deepseek-harness-idea-<version>.zip`。
2. IDEA 中 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 zip，重启 IDE。
3. `Settings → Tools → DeepSeek Harness` 填入 **Node.js 路径**（`node` 可执行文件）与 **DSH 路径**（`…/@deepseek-ai/dsh/lib/bin.js`），设置 DeepSeek API Key；如需其他服务商，点击"Add Provider"添加。
4. 打开右侧 **DeepSeek Harness** 工具窗口（路径未配好前会停留在设置引导卡），开始对话。

## 运行时配置 / Runtime configuration

插件**仅使用你本机已安装的 Node.js 与 DeepSeek Harness**——不内置、不下载任何运行时。若工具窗口一直停留在设置引导卡，请检查：

- **Node.js path**：`node` 可执行文件的绝对路径（Windows 为 `node.exe`）；
- **DSH path**：DeepSeek Harness 包入口的绝对路径，形如 `…/node_modules/@deepseek-ai/dsh/lib/bin.js`（`npm i -g @deepseek-ai/dsh` 安装后可 `npm root -g` 查看）。

两个路径都指向存在的文件且保存后，工具窗口即会启动 DSH。

### 自定义服务商 / Custom Providers

在 `Settings → Tools → DeepSeek Harness → Model Providers` 中可添加自定义服务商：

- 支持三种 API 协议：`openai-completions`、`openai-responses`、`anthropic-messages`
- 可为每个服务商配置多个模型，或从 API 端点一键拉取模型列表
- API Key 安全存储在 IDE 密码保险箱中，凭据文件变动自动热同步

## 构建 / Build

```bash
# 要求：JDK 21 作为 JAVA_HOME（推荐直接用 IDE 自带的 JBR：<IDEA 安装目录>/jbr）
# 构建工具链：Gradle 9.5.1 + Kotlin 2.4.10 + IPGP 2.18.1

# 全量测试（冒烟测试需设置 DSH_IDEA_NODE / DSH_IDEA_DSH 环境变量，否则自动跳过）
gradlew test

# 打包插件 zip（输出到 build/distributions/）
gradlew buildPlugin
```

> 首次构建会下载 ideaIC 2024.1.7 平台 SDK（约 1 GB 缓存）。若要基于本地 IDE SDK 构建（跳过下载），执行：
> `gradlew buildPlugin -PlocalIdePath=<IDEA 安装目录>`

## 架构 / Architecture

```
IntelliJ IDEA (JVM/EDT)
├─ Tool Window: JBCefBrowser ──loads──► http://127.0.0.1:<port>（DSH Web UI）
├─ Snapshot & Review Manager（基线快照 → diff → 还原/忽略）
├─ DshSessionLauncher（启动编排：ensureHome → patch → 凭据 → 进程）
│    └─ IdeMcpServer（进程内 MCP 终结点：127.0.0.1 随机端口 + token 鉴权，写 ide.yml 补丁）
│         POST /mcp（JSON-RPC 2.0，McpRpcHandler，7 个 ide_* 工具直连 PSI/VFS）
└─ ProcessManager（Node 子进程：端口发现、健康检查、崩溃退避重启）
        │  node <dsh>/bin.js --profile web --patch ide.yml --host 127.0.0.1 --port 0 --no-open
        ▼
Node 子进程（DSH）
├─ dsh web server（仅 loopback）
└─ mcp-client(ide) ──streamable-http──► IDE 进程内 IdeMcpServer（X-DSH-IDE-Token）
        ├─ ide_get_selection / ide_get_open_files / ide_get_project_tree
        ├─ ide_get_sent_selection
        └─ ide_open_file / ide_reveal_file / ide_refresh_files
```

## 测试 / Tests

- **91 个单元测试**，覆盖：MCP 补丁生成、快照 diff、凭据掩码/同步、端口解析、平台检测、JSON 编解码、工程忽略规则、发送选择队列、日志解释构建、工作区初始化、旧会话迁移、运行时注册等。
- **5 个集成冒烟测试**：真实 dsh 启动、MCP 终结点 7 个 `ide_*` 工具链路（strict patch）、切换项目工作区顺序、旧会话升级迁移、send-prompt 后端 RPC。
- 冒烟测试与插件同契约（仅路径）：设置 `DSH_IDEA_NODE`（node 可执行文件）与 `DSH_IDEA_DSH`（`…/@deepseek-ai/dsh/lib/bin.js`）两个环境变量才会真正启动 dsh；未设置自动跳过。

## 许可 / License

[MIT](LICENSE)