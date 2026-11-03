# ateng-boot-sample-ai AI 智能体与认知图底座自验体验工程

> 专注于演示与自验 `ateng-boot-ai` 核心特性在真实业务场景下的开箱使用，涵盖 OpenAI-Compatible 万能网关多模型路由、发票凭单视觉多模态分析、DeepSeek-R1 `<think>` 思考链提炼与双通道打字机流式推流。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleAiApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-ai
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (`!local` Profile)**：内置 OkHttp `MockWebServer` 纯内存离线桩，全真模拟 OpenAI 协议与 SSE 打字机流式响应，无需任何外部大模型 API Key 即可启动运行并跑通自动化回归测试（构建秒级全绿）；
- **Local 真实模式 (`local` Profile)**：若需直连真实在线大模型服务（如 DeepSeek、DashScope/通义千问、Ollama、OpenAI 等），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Base URL 与 API Key，启动自动生效（该文件已被 `.gitignore` 强制收敛，CI 零泄露风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **同步对话分析** | `AiChatController` | `POST /sample/ai/chat/sync` | `curl -X POST "http://localhost:8080/sample/ai/chat/sync" -H "Content-Type: application/json" -d "{\"prompt\":\"请列举Spring Boot 3的核心特性\"}"` |
| **指定命名模型对话** | `AiChatController` | `POST /sample/ai/chat/sync` | `curl -X POST "http://localhost:8080/sample/ai/chat/sync" -H "Content-Type: application/json" -d "{\"prompt\":\"求解数学难题\",\"model\":\"reasoner\"}"` |
| **发票凭单图文识别** | `AiChatController` | `POST /sample/ai/chat/sync` | `curl -X POST "http://localhost:8080/sample/ai/chat/sync" -H "Content-Type: application/json" -d "{\"prompt\":\"请识别发票报销金额与纳税人识别号\",\"images\":[\"https://images.example.com/invoice.png\"]}"` |
| **双通道打字机流式推流** | `AiChatController` | `GET /sample/ai/chat/stream` | `curl -N "http://localhost:8080/sample/ai/chat/stream?prompt=请深度推导高阶微积分定理"` |
| **单轮智能体审计决策** | `AiAgentController` | `POST /sample/ai/agent/audit` | `curl -X POST "http://localhost:8080/sample/ai/agent/audit" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD_20261103001\",\"remarks\":\"年度财务大额专项审计\"}"` |
| **跨轮多会话智能体审计** | `AiAgentController` | `POST /sample/ai/agent/audit/{sessionId}` | `curl -X POST "http://localhost:8080/sample/ai/agent/audit/sess_audit_1001" -H "Content-Type: application/json" -d "{\"orderId\":\"ORD_20261103001\",\"remarks\":\"多轮复核第1轮\"}"` |

> **流式推流说明**：调用 `GET /sample/ai/chat/stream` 时，控制台或客户端将持续接收由 `event: reasoning`（思考过程）、`event: text`（正文内容）与 `event: done`（`[DONE]` 结束信号）组成的标准 Server-Sent Events 事件流。

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-ai,ateng-boot-samples/ateng-boot-sample-ai -am -T 1C -nsu -B
```

> 详细架构原理、API 全景速查矩阵与生产级配置清单，请参阅核心 Starter 文档：[ateng-boot-ai README](../../ateng-boot-ai/README.md)。
