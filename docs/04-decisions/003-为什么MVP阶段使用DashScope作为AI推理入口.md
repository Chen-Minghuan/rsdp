## 背景

RSDP 的核心流程依赖多模态视觉模型对产品白底图进行风格、六维标签、颜色、材质的识别。在项目早期需要快速验证：

1. 图片上传 → AI 识别 → 数据落库端到端是否可行。
2. Prompt 工程与返回格式是否符合业务预期。
3. 前端录入页面的交互逻辑是否合理。

本地 Ollama 部署需要准备大模型文件、GPU 显存和较长的首次加载时间，不利于快速迭代。

## 决策

MVP 阶段使用阿里云 DashScope 提供的 `qwen3-vl-plus` 模型，通过 OpenAI 兼容接口调用：

- 基地址：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- 模型：`qwen3-vl-plus`
- 鉴权：环境变量 `DASHSCOPE_API_KEY`

后端使用 Spring `RestClient` 以 OpenAI chat-completions 格式请求，并将 vision 模型封装为 `VisionService`。

## 影响

- 优点：零模型部署成本、响应稳定、可快速验证 Prompt 与数据流。
- 缺点：数据需上传到云端，存在合规与成本风险；无法离线运行。
- 后续：当视觉识别 Prompt 与数据格式稳定后，再迁移到本地 Ollama `qwen2.5-vl:7b`，实现数据不出机器。
