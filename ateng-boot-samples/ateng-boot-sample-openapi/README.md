# OpenAPI 生产级场景样例 (ateng-boot-sample-openapi)

本工程为 `ateng-boot-openapi` 的 1:1 专属场景样例工程，演示原生 SpringDoc 接口契约治理、生产安全守卫、多业务域分组与 Sa-Token 锁头鉴权集成。

---

## 包含特性与演示场景

1. **统一契约保真**：Controller 统一显式返回 `Result<T>`，绝不通过反射对报文剥壳；
2. **多业务域分组路由**：配置驱动的 `admin`（运营管理端）与 `app`（移动端）多分组路由隔离；
3. **全局请求头自动透传**：所有 Operation 自动注入 `X-Trace-Id` 与 `X-Tenant-Id`；
4. **Sa-Token 锁头鉴权**：Swagger UI 右上角自动激活“Authorize”弹窗；
5. **状态码全景字典**：文档描述自动生成系统状态码表格；
6. **生产安全守卫机制**：演示 404 物理切断与 HTTP Basic 认证弹窗防护。

---

## 启动与体验步骤

### 1. 本地启动服务
```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-openapi
```

### 2. 访问接口文档
- 极简友好重定向：浏览器打开 `http://localhost:8080/docs` 或 `http://localhost:8080/doc`，将自动 302 重定向至 Swagger UI；
- 全局接口元数据：`http://localhost:8080/v3/api-docs`；
- 管理端分组元数据：`http://localhost:8080/v3/api-docs/admin`；
- 移动端分组元数据：`http://localhost:8080/v3/api-docs/app`。

### 3. 执行端到端自验单测
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-openapi -am -T 1C -nsu -B
```
