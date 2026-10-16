# ateng-boot-samples 自验演示与集成测试套件

> 基于 H2 内存库与 MockMvc 的端到端自动化测试金字塔与工程化最佳实践 Demo。

---

## 1. 模块定位与职责

`ateng-boot-samples` 是 `ateng-boot` 框架的官方自验工程与消费端示例。
- **内存自闭环**：基于 H2 内存数据库与内置数据源，无需安装外部 MySQL、Redis 等即可一键启动运行；
- **端到端准出门禁**：包含覆盖全技术栈特性的金字塔测试用例（`SampleApplicationIntegrationTest` 等），是框架代码合入与发版的唯一准出标准；
- **开箱即用最佳实践**：为外部开发者提供标准 DDD/MVC 分层、实体继承、异常拦截、数据安全与接口防御的完整参考实现。

---

## 2. 本地一键启动体验

```bash
# 启动样例工程服务
mvn -pl ateng-boot-samples spring-boot:run
```

服务启动于 `http://localhost:8080`，可直接通过 `curl` 或 API 工具测试：
```bash
# 验证统一响应 Result<T> 与 X-Trace-Id 链路追踪
curl -i http://localhost:8080/api/users/1
```
