# Multimodal Engine v0.2.0 / Tavo Multimodal v0.0.20

本版本是在 v0.1.0 已通过手机 MCP 连接的基础上实现的实验版本。Android 端源码新增四个页面、设备内存/CPU/ABI/存储检测、系统媒体解码、最长边1280 JPEG、场景指纹差异去重（最多扫描48次、保留24帧）、内置 ML Kit 图像标签模型、异步媒体任务和显式授权的 MCP 结果共享。标签不是视觉语言模型的详细描述，可能遗漏动作、人物细节或未采样画面。

## 两种构建
- [普通视觉版](https://github.com/xbu080675-creator/808949/actions/workflows/engine.yml)：内置图像标签模型，不含 Whisper 原生库。
- [Whisper ARM64 实验版](https://github.com/xbu080675-creator/808949/actions/workflows/engine-whisper.yml)：增加 whisper.cpp v1.7.6、JNI、MediaCodec 音轨解码与16kHz单声道重采样；首次在模型页明确下载约148MB多语言 Base 权重（系统下载管理、使用前校验 SHA-256）。最多分析前180秒音频，每30秒分段推理；取消在分段之间检查。运行时、模型下载与转录尚未实机验证。只有 ARM64 设备适用。

两个工作流均验证签名；Whisper 版还检查 APK 内原生库。当前开发环境没有 Android SDK/NDK，尚未拿到新 APK 构建结果。Debug 签名可能与旧版不同，如安装提示签名冲突，须先卸载旧引擎（删除其令牌、权重与本地数据），再安装及重新配对。不要卸载 Tavo 或 TPG。

## 配对与使用
1. 安装成功构建的 APK、覆盖安装 v0.0.20 TPG；在引擎启动服务。
2. Tavo 原生 MCP 连接 http://127.0.0.1:8765/mcp，Streamable HTTP，Bearer token 取自引擎。启用脚本调用 MCP 工具权限。
3. 高级诊断与原生说明 → 配对本地 MCP 引擎。原有 engine_status/get_media_result 保留，新增 get_task_status/cancel_task。远程取消必须在引擎授权本次任务；没有授权返回 false。
4. 引擎任务页选择视频或音频，处理完成后授权共享本次分析。插件可导出本机任务报告、授权分析结果注入下次回复（只一次、限当前聊天）。原始媒体及抽帧图不经 MCP 传输，用户批准后只有文字交给聊天模型。没有真实标签或转录时插件拒绝注入。
5. 引擎任务与模型权重留在本机；聊天回复仍可能消耗现有文本 API 额度。原始视频的自动跨应用提交、完整视觉语言模型（SmolVLM/Qwen）、持久后台任务、模型切换及全时长音频理解尚未完成。视觉模型已内置但未进行 Android 运行验证。

## 检查
JDK 17 编译 MCP 核心并在本地执行14项真实 HTTP 断言：两个协议版本、通知、4个工具发现、状态/结果、一次性取消授权、未知参数拒绝、鉴权及 Origin/Host 拒绝。TPG JavaScript 语法、原功能模拟回归、任务状态/取消/分析授权/单次注入模拟测试、TPG ZIP 完整性及 GitHub blob 一致性检查通过。以上不等于 Android 模型推理已实测。
