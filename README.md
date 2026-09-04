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

- **发送选中代码 / Send selection to DSH** — 右键选中代码发送紧凑文件引用（`@路径#L1-14`）；完整代码可随时被智能体拉取（剪贴板兜底）。
- **运行日志一键解释 / One-click log explanation** — 在项目运行控制台选中一段日志，右键"DSH 一键解释"，自动把解释请求 + 日志提交给 DSH（无需手动粘贴/回车）。
- **多服务商支持 / Multi-provider support** — 除内置 DeepSeek 外，支持添加自定义服务商（OpenAI、Anthropic 等兼容 API），可为每个服务商配置多个模型，支持从 API 端点一键拉取模型列表。
- **审查与还原 / Review & restore** — 基线快照 + 原生 diff（修改/新增/删除），支持还原单个/全部、忽略、重新基线。
- **进程内 MCP IDE 工具 / IDE context via in-process MCP** — 智能体可通过 `mcp__ide__*` 工具读取当前选中代码、打开的文件、项目树，并打开/定位文件；MCP 终结点与 IDE 同 JVM 进程（无独立翻译进程、随机 token 鉴权）。

## 环境要求 / Requirements

- IntelliJ IDEA Community / Ultimate **2024.1 – 2026.2**（build 241 – 262；Windows 10/11 x64、macOS arm64/x64、Linux x64）
- 持有 DeepSeek API Key（`deepseek-chat` / `deepseek-reasoner`），或自定义服务商的 API Key
- 本机已安装 **Node.js** 与 **DeepSeek Harness**（`npm i -g @deepseek-ai/dsh`），并在插件设置中填入两者的路径（Node.js 可执行文件 与 `…/@deepseek-ai/dsh/lib/bin.js`）。

## 许可 / License

[MIT](LICENSE)