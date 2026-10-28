# ateng-boot-sample-http 声明式与命令式 HTTP 通信端到端自验工程

> 本样例工程基于 Spring 6 原生 `RestClient`、Apache HttpClient 5 生产级高并发池化连接池与 `HttpTemplate` 命令式门面构建，提供 100% 离线自洽的端到端自动化测试验证沙箱。

---

## 1. 场景定位与功能基线

`ateng-boot-sample-http` 作为 `ateng-boot-http` 的 1:1 独立场景沙箱，聚焦以下核心通信场景：
- **纯内存与零网络依赖（Zero External Network）**：基于 OkHttp `MockWebServer` 动态随机端口，在本地与 CI 环境中实现秒级全绿离线端到端验证；
- **生产级池化连接管理**：默认装配 Apache HttpClient 5 连接池（最大 200 连接、单路由 50 并发、后台空闲驱逐、借出保活探测与快速失败）；
- **命令式统一门面 `HttpTemplate`**：流畅执行 GET、POST、PUT、DELETE 动词并自动完成 JSON 实体反序列化；
- **双模优雅降级**：支持强制指定或类路径缺失时平滑回退至 JDK 17 原生 `HttpClient`。

---

## 2. 核心集成验证清单

| 序号 | 验证场景 | 覆盖核心机制 / API | 测试方式 / 动作 | 预期校验点 |
| :---: | :--- | :--- | :--- | :--- |
| **01** | 自动装配与连接池注入 | `AtengHttpAutoConfiguration` | 启动 Spring Boot 测试容器 | `HttpTemplate` 与 `HttpComponentsClientHttpRequestFactory` 正常注入 |
| **02** | HTTP GET 请求与反序列化 | `HttpTemplate.get(url, Class<T>)` | 模拟 MockWebServer 返回 JSON | HTTP 200 + 正确反序列化为 Map/POJO，验证请求方法与路径 |
| **03** | HTTP POST JSON 请求 | `HttpTemplate.post(url, body, Class<T>)` | 模拟发送 JSON 载荷 | 上送 Content-Type 与 Body 校验正确，接收响应反序列化正常 |
| **04** | HTTP PUT 与 DELETE 请求 | `HttpTemplate.put(...)` / `delete(...)` | 模拟 PUT 与 DELETE 交互 | 动词语义准确，RecordedRequest 校验通过 |

---

## 3. 本地自验执行指南

执行以下 Maven 命令即可在纯内存环境中以并行加速模式完成端到端离线自验：

```bash
mvn test -pl ateng-boot-http,ateng-boot-samples/ateng-boot-sample-http -am -T 1C -nsu -B
```
