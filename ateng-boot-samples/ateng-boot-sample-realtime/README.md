# ateng-boot-sample-realtime

> 实时通信全双工与流式双模引擎场景实测工程

---

## 1. 模块简介
本工程为 `ateng-boot-realtime` 的 1:1 独立端到端自验沙箱，演示以下核心工业级能力：
- **WebSocket 原生端点**：并发安全装饰器、64KB 缓冲优化与全双工消息收发；
- **声明式上行路由**：基于 `@RealtimeAction("event")` 注解的方法级事件分发；
- **统一消息推送门面**：使用 `RealtimeTemplate` 执行单播、组播与全服广播；
- **打字机流式推流**：基于 `SseStreamHelper` 的 SSE 优雅低延迟打字机流；
- **三阶握手鉴权**：无缝桥接 Sa-Token 会话，支持 Header / QueryParam / Protocol 传参。

---

## 2. 快速运行与场景自验
在项目根目录下执行集成测试准出验证：
```bash
mvn test -pl ateng-boot-realtime,ateng-boot-samples/ateng-boot-sample-realtime -am -T 1C -nsu -B
```

本地快速启动演示服务：
```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-realtime
```
启动后可通过前端或 WebSocket 调试工具连接：
- **WebSocket 端点**：`ws://localhost:8080/ws/realtime`
- **SSE 端点**：`http://localhost:8080/sse/realtime`
- **LLM 打字机流演示**：`http://localhost:8080/api/realtime/stream-llm`
