# ateng-boot-sample-satoken 安全鉴权与会话治理体验工程

> 专注于演示与自验 `ateng-boot-satoken` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSaTokenApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-satoken
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存桩与自洽配置，`ateng.boot.redis.enabled: false`，完全无需依赖外部物理 Redis，基于内存与 H2 秒级自洽启动并跑通全量测试；
- **Local 真实模式**：若需连接真实物理 Redis 中间件，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验（按业务能力领域拆分为独立高内聚控制器）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **多端登录签发凭据** | `AuthSessionController` | `POST /sample/satoken/auth/login` | `curl -X POST "http://localhost:8080/sample/satoken/auth/login" -H "Content-Type: application/json" -d '{"userId":"10086","username":"admin","tenantId":"tenant_sz","device":"PC","roles":["audit_manager"],"permissions":["order:query"]}'` |
| **查询当前登录主体** | `AuthSessionController` | `GET /sample/satoken/auth/me` | `curl "http://localhost:8080/sample/satoken/auth/me" -H "satoken: <TOKEN>"` |
| **透明剥离读取用户ID** | `AuthSessionController` | `GET /sample/satoken/auth/user-id` | `curl "http://localhost:8080/sample/satoken/auth/user-id" -H "satoken: <TOKEN>"` |
| **获取当前租户上下文** | `AuthSessionController` | `GET /sample/satoken/auth/current-tenant` | `curl "http://localhost:8080/sample/satoken/auth/current-tenant" -H "satoken: <TOKEN>"` |
| **创建租户订单(审计回填)** | `AuthSessionController` | `POST /sample/satoken/auth/orders` | `curl -X POST "http://localhost:8080/sample/satoken/auth/orders" -H "satoken: <TOKEN>" -H "Content-Type: application/json" -d '{"orderNo":"ORD-20261108-01","amount":199.90}'` |
| **查询订单(零实体泄露)** | `AuthSessionController` | `GET /sample/satoken/auth/orders/{orderId}` | `curl "http://localhost:8080/sample/satoken/auth/orders/1" -H "satoken: <TOKEN>"` |
| **指定设备端注销下线** | `AuthSessionController` | `POST /sample/satoken/auth/logout-device` | `curl -X POST "http://localhost:8080/sample/satoken/auth/logout-device?device=PC" -H "satoken: <TOKEN>"` |
| **当前会话安全注销** | `AuthSessionController` | `POST /sample/satoken/auth/logout` | `curl -X POST "http://localhost:8080/sample/satoken/auth/logout" -H "satoken: <TOKEN>"` |
| **多角色 OR 声明式鉴权** | `AccessControlController` | `GET /sample/satoken/access/role-audit` | `curl "http://localhost:8080/sample/satoken/access/role-audit" -H "satoken: <TOKEN>"` |
| **细粒度权限码注解鉴权** | `AccessControlController` | `GET /sample/satoken/access/perm-order-query` | `curl "http://localhost:8080/sample/satoken/access/perm-order-query" -H "satoken: <TOKEN>"` |
| **穿透服务层权限校验** | `AccessControlController` | `GET /sample/satoken/access/service-permission` | `curl "http://localhost:8080/sample/satoken/access/service-permission" -H "satoken: <TOKEN>"` |
| **开启二级安全认证** | `AccessControlController` | `POST /sample/satoken/access/open-safe` | `curl -X POST "http://localhost:8080/sample/satoken/access/open-safe?timeout=120" -H "satoken: <TOKEN>"` |
| **二级认证大额资金结算** | `AccessControlController` | `POST /sample/satoken/access/safe-settle` | `curl -X POST "http://localhost:8080/sample/satoken/access/safe-settle" -H "satoken: <TOKEN>" -H "Content-Type: application/json" -d '{"settlementNo":"SETTLE-001","amount":8888.00,"recipientAccount":"ACC-6222-0001"}'` |
| **资料修改防水平越权** | `AccessControlController` | `POST /sample/satoken/access/profile/update` | `curl -X POST "http://localhost:8080/sample/satoken/access/profile/update" -H "satoken: <TOKEN>" -H "Content-Type: application/json" -d '{"userId":"10086","nickname":"本人昵称"}'` |
| **SPI反查防水平越权撤单** | `AccessControlController` | `POST /sample/satoken/access/orders/{orderId}/cancel` | `curl -X POST "http://localhost:8080/sample/satoken/access/orders/88001/cancel" -H "satoken: <TOKEN>"` |
| **两级缓存解析角色列表** | `PermissionCacheController` | `GET /sample/satoken/permission-cache/roles` | `curl "http://localhost:8080/sample/satoken/permission-cache/roles" -H "satoken: <TOKEN>"` |
| **两级缓存解析权限码集** | `PermissionCacheController` | `GET /sample/satoken/permission-cache/permissions` | `curl "http://localhost:8080/sample/satoken/permission-cache/permissions" -H "satoken: <TOKEN>"` |
| **精准刷新单角色缓存** | `PermissionCacheController` | `POST /sample/satoken/permission-cache/refresh-role` | `curl -X POST "http://localhost:8080/sample/satoken/permission-cache/refresh-role?roleCode=audit_manager" -H "satoken: <TOKEN>"` |
| **精准刷新单用户缓存** | `PermissionCacheController` | `POST /sample/satoken/permission-cache/refresh-user` | `curl -X POST "http://localhost:8080/sample/satoken/permission-cache/refresh-user?userId=10086" -H "satoken: <TOKEN>"` |
| **全量清空两级权限缓存** | `PermissionCacheController` | `POST /sample/satoken/permission-cache/clear-all` | `curl -X POST "http://localhost:8080/sample/satoken/permission-cache/clear-all" -H "satoken: <TOKEN>"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-satoken,ateng-boot-samples/ateng-boot-sample-satoken -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-satoken README](../../ateng-boot-satoken/README.md)。
