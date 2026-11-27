# Multimodal Engine v0.4.0 / Tavo Multimodal v0.0.22

以实测通过的 v0.3.0 前台服务、锁屏、进度、断点恢复、Base/Small 和 MCP 架构为基础增量开发。本版 Android 源码和工作流已提交，尚未取得新版 APK 或进行 Android 编译/实机验证。不要把源码实现等同于推理成功。

## 安装入口
- [Whisper ARM64](https://github.com/xbu080675-creator/808949/actions/workflows/engine-whisper.yml)：推荐先测试 OCR、语音、队列、历史及分享。
- [Whisper + VLM ARM64 实验版](https://github.com/xbu080675-creator/808949/actions/workflows/engine-vlm.yml)：额外引入 MediaPipe tasks-genai 0.10.27，使用 Gemma 3n .task 模型的真实图像输入与文本输出。运行时与设备兼容性未验证，独立构建。
- [普通视觉版](https://github.com/xbu080675-creator/808949/actions/workflows/engine.yml)：无 Whisper/VLM 原生库。
- [TPG 构建](https://github.com/xbu080675-creator/808949/actions/workflows/build.yml)：0.0.22。最终本地 TPG r1 修正并发注入锁（manifest 版本仍0.0.22）。
- Debug 签名可能与旧版不同；卸载会清除权重、令牌、队列、检查点及历史，先导出重要报告。不要卸载 Tavo。

## 视觉与 OCR
模型页可选择基础标签、Gemma 3n E2B 或 E4B（实验）。前往官方 Hugging Face 页面同意许可后下载相应 .task（E2B 3.14 GB，E4B 4.41 GB），回到应用导入；模型导入需额外磁盘空间，保持应用打开，不索取 Hugging Face token。仅检查导入长度及完整复制，不是权重真实性的密码学证明。模型内存可能超过设备预算；先使用 E2B。最多约13个全时间轴锚点请求真正图像描述，其他画面仍用基础标签。没有运行库或推理失败会保留基础标签/OCR并记录失败或状态，不冒充视觉描述。采用 MediaPipe 已维护模式的 Java API 以复用原工程；后续可迁移 LiteRT-LM。SmolVLM/Qwen 尚未集成。OCR 用内置 ML Kit 中文、日文、韩文或拉丁模型，可关闭；OCR 仅覆盖采样帧。报告每帧标签、文字、描述共享 sampleMs，语音带时间戳；尚未做完整模型级语义融合。

## 语音
新任务采用28秒主窗口，前后最多1秒上下文（每次最多30秒），根据近似转录片段起点确定归属，不能保证所有跨边界词完整或不重复。可指定中英日韩、每段自动或首个有转录的窗口自动锁定语言；首段误识别可能导致锁错，混合语言建议每段自动。Base/Small 保留，设置在任务创建时固定。旧 v0.3.0 检查点保持30秒窗口以兼容。2小时媒体限制与原有进度/取消恢复保留。

## 任务与 Tavo
运行中选择另一个媒体可入队（最多8项，持久化），当前完成后手动启动下一项；若中断，先恢复或清除当前项。开启新任务时将已完成报告归档，最多12份，历史可查看/单独授权 MCP，队列/历史可清除。当前仍为单工作线程，不并行推理。显示 Java/native heap 估计，不等于进程全部内存或 GPU 用量。系统分享入口支持音视频单文件，确认后私有复制（最多200 MiB），服务执行或入队；是否能从 Tavo 发出分享取决于宿主。没有实现自动截获 Tavo 附件。原始文件不自动上传，MCP 仅分享经确认的分析文字。TPG 高级诊断新增角色注入状态、清除待注入资料；并发生成锁修复，仍需真实角色回复验证。大报告按时间段选择注入，受宿主长度限制。

## 验证
本地 TPG 语法及 ZIP 完整性、GitHub blob 比对通过。实际 Node 模拟 generation:prepare 单次注入、缺少 hook、hook 失败、并发触发通过（不代表 Tavo 真实环境通过）。JDK TimelineTest 旧时间轴与重叠归属断言通过。工作流统一 APK_NAME 与上传路径，验证签名和原生库；构建状态未取回。Android OCR、VLM、JNI、队列/历史、分享及旧功能回归均待实机验证。
