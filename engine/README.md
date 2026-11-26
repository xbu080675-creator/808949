# Multimodal Engine v0.1.0（连接验证版）

本版不包含 Qwen3-VL、Whisper、FFmpeg 或任何推理模型。只提供 Android 原生界面、前台 MCP 服务、系统媒体元数据读取和显式授权共享。TPG 不自动发送视频或注入未识别内容。

## 安装与配对
1. 在 GitHub Actions 的 Build Multimodal Engine APK 工作流选择成功的运行，下载 Multimodal-Engine-v0.1.0 artifact，解压其中的 debug APK。当前本地环境未编译 APK，工作流结果待确认。
2. 安装 APK 与 Tavo Multimodal v0.0.19.tpg（覆盖旧插件）；打开引擎并启动服务，复制配对令牌。不要把令牌公开。
3. Tavo → 更多 → MCP，添加外部服务（不是 Tavo 自己的 MCP Server）。若有传输选择，选 Streamable HTTP，URL 为 http://127.0.0.1:8765/mcp，Authorization: Bearer <复制的令牌>（若是 token 专用框，只填令牌）。启用服务，并按宿主提示允许脚本调用 MCP 工具。
4. 返回聊天 → 高级诊断与原生说明 → 配对本地 MCP 引擎 → 选择刚添加的服务 → 检测本地引擎。
5. 引擎内选择视频/音频，读取完成后点击授权 MCP 读取本次结果；Tavo 导出本机媒体任务报告。仅有元数据，不等于理解画面。无需调用文本模型。可撤销授权、停止服务。

## 构建与验证
Gradle 8.9 / JDK 17 / Android SDK 35；`gradle -p engine :app:assembleDebug`。minSdk 26，targetSdk 34。GitHub Actions 对生成的 APK 执行 apksigner verify，实际运行状态尚未确认。Debug 签名用于测试，未来更新需保持同一签名或卸载旧版。

本地 JDK 通过了 MCP JSON-RPC 双协议握手、工具查询、调用、无共享数据检查、未知参数拒绝、错误 token、Origin/Host 拒绝、坏 JSON、超大请求、GET 不支持共 13 项实际 HTTP 检查。旧 TPG 回归和配对模拟通过。尚未验证 Android 编译、UI 实机、宿主 localhost HTTP 接入、SSE 兼容、后台保活或推理。服务器无 SSE、无云端、只监听 127.0.0.1，要求 Bearer token，不接受 Origin 请求。
