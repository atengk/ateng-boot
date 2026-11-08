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
| **方法级免鉴权探针** | `AuthSessionController` | `GET /sample/satoken/auth/public/ping` | `curl "http://localhost:8080/sample/satoken/auth/public/ping"` |
| **方法级免鉴权文章(通配)** | `AuthSessionController` | `GET /sample/satoken/auth/public/article/{articleId}` | `curl "http://localhost:8080/sample/satoken/auth/public/article/10086"` |
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
| **防爆破安全登录** | `LoginDefenseController` | `POST /sample/satoken/defense/login` | `curl -X POST "http://localhost:8080/sample/satoken/defense/login" -H "Content-Type: application/json" -d '{"account":"test_user","password":"wrong_password","clientIp":"192.168.1.100"}'` |
| **查询账号防爆破锁定状态** | `LoginDefenseController` | `GET /sample/satoken/defense/account-status` | `curl "http://localhost:8080/sample/satoken/defense/account-status?account=test_user"` |
| **查询IP防爆破熔断状态** | `LoginDefenseController` | `GET /sample/satoken/defense/ip-status` | `curl "http://localhost:8080/sample/satoken/defense/ip-status?clientIp=192.168.1.100"` |
| **管理员紧急解锁账号** | `LoginDefenseController` | `POST /sample/satoken/defense/unlock-account` | `curl -X POST "http://localhost:8080/sample/satoken/defense/unlock-account?account=test_user"` |
| **管理员解除客户端IP熔断** | `LoginDefenseController` | `POST /sample/satoken/defense/unlock-ip` | `curl -X POST "http://localhost:8080/sample/satoken/defense/unlock-ip?clientIp=192.168.1.100"` |
| **在线会话总数统计** | `OnlineSessionController` | `GET /sample/satoken/online/count` | `curl "http://localhost:8080/sample/satoken/online/count"` |
| **增量游标分页检索在线会话** | `OnlineSessionController` | `GET /sample/satoken/online/page` | `curl "http://localhost:8080/sample/satoken/online/page?keyword=admin&page=1&size=10"` |
| **按Token强制会话下线** | `OnlineSessionController` | `POST /sample/satoken/online/kickout-token` | `curl -X POST "http://localhost:8080/sample/satoken/online/kickout-token?tokenValue=<TOKEN>"` |
| **按设备类型精准踢人** | `OnlineSessionController` | `POST /sample/satoken/online/kickout-device` | `curl -X POST "http://localhost:8080/sample/satoken/online/kickout-device?userId=10086&device=PC"` |
| **用户全端强制下线** | `OnlineSessionController` | `POST /sample/satoken/online/kickout-user` | `curl -X POST "http://localhost:8080/sample/satoken/online/kickout-user?userId=10086"` |
| **模拟同端顶替下线** | `OnlineSessionController` | `POST /sample/satoken/online/replaced` | `curl -X POST "http://localhost:8080/sample/satoken/online/replaced?userId=10086&device=PC"` |
| **对违规账号阶梯封禁** | `OnlineSessionController` | `POST /sample/satoken/online/disable` | `curl -X POST "http://localhost:8080/sample/satoken/online/disable?userId=10086&durationSeconds=300"` |
| **解除账号阶梯封禁** | `OnlineSessionController` | `POST /sample/satoken/online/untie-disable` | `curl -X POST "http://localhost:8080/sample/satoken/online/untie-disable?userId=10086"` |
| **查询账号封禁状态** | `OnlineSessionController` | `GET /sample/satoken/online/disable-status` | `curl "http://localhost:8080/sample/satoken/online/disable-status?userId=10086"` |
| **受限业务接口校验(@SaCheckDisable)** | `OnlineSessionController` | `GET /sample/satoken/online/restricted-action` | `curl "http://localhost:8080/sample/satoken/online/restricted-action" -H "satoken: <TOKEN>"` |
| **管理端独立登录** | `MultiAccountController` | `POST /sample/satoken/multi-account/admin/login` | `curl -X POST "http://localhost:8080/sample/satoken/multi-account/admin/login" -H "Content-Type: application/json" -d '{"userId":"admin-001","username":"admin"}'` |
| **获取管理端当前资料** | `MultiAccountController` | `GET /sample/satoken/multi-account/admin/me` | `curl "http://localhost:8080/sample/satoken/multi-account/admin/me" -H "satoken-admin: <ADMIN_TOKEN>"` |
| **管理端特权角色校验** | `MultiAccountController` | `GET /sample/satoken/multi-account/admin/manage-roles` | `curl "http://localhost:8080/sample/satoken/multi-account/admin/manage-roles" -H "satoken-admin: <ADMIN_TOKEN>"` |
| **管理端独立注销** | `MultiAccountController` | `POST /sample/satoken/multi-account/admin/logout` | `curl -X POST "http://localhost:8080/sample/satoken/multi-account/admin/logout" -H "satoken-admin: <ADMIN_TOKEN>"` |
| **会员端独立登录** | `MultiAccountController` | `POST /sample/satoken/multi-account/member/login` | `curl -X POST "http://localhost:8080/sample/satoken/multi-account/member/login" -H "Content-Type: application/json" -d '{"userId":"member-001","username":"vip_user"}'` |
| **获取会员端当前资料** | `MultiAccountController` | `GET /sample/satoken/multi-account/member/me` | `curl "http://localhost:8080/sample/satoken/multi-account/member/me" -H "satoken-member: <MEMBER_TOKEN>"` |
| **会员端特权权限校验** | `MultiAccountController` | `GET /sample/satoken/multi-account/member/points` | `curl "http://localhost:8080/sample/satoken/multi-account/member/points" -H "satoken-member: <MEMBER_TOKEN>"` |
| **会员端独立注销** | `MultiAccountController` | `POST /sample/satoken/multi-account/member/logout` | `curl -X POST "http://localhost:8080/sample/satoken/multi-account/member/logout" -H "satoken-member: <MEMBER_TOKEN>"` |
| **开启客服会话模拟切换** | `ImpersonateAuditController` | `POST /sample/satoken/impersonate/switch` | `curl -X POST "http://localhost:8080/sample/satoken/impersonate/switch?targetUserId=10086" -H "satoken: <TOKEN>"` |
| **结束客服会话模拟** | `ImpersonateAuditController` | `POST /sample/satoken/impersonate/end-switch` | `curl -X POST "http://localhost:8080/sample/satoken/impersonate/end-switch" -H "satoken: <TOKEN>"` |
| **查询会话模拟状态** | `ImpersonateAuditController` | `GET /sample/satoken/impersonate/status` | `curl "http://localhost:8080/sample/satoken/impersonate/status" -H "satoken: <TOKEN>"` |
| **瞬态模拟补偿客诉订单** | `ImpersonateAuditController` | `POST /sample/satoken/impersonate/compensate-order` | `curl -X POST "http://localhost:8080/sample/satoken/impersonate/compensate-order?targetUserId=10086&orderNo=COMP-001&amount=99.00" -H "satoken: <TOKEN>"` |
| **查询系统安全审计事件** | `ImpersonateAuditController` | `GET /sample/satoken/impersonate/events` | `curl "http://localhost:8080/sample/satoken/impersonate/events"` |
| **清空系统安全审计事件** | `ImpersonateAuditController` | `POST /sample/satoken/impersonate/events/clear` | `curl -X POST "http://localhost:8080/sample/satoken/impersonate/events/clear"` |
| **公开商城广播公告(@Anonymous)** | `PublicPortalController` | `GET /sample/satoken/portal/announcement` | `curl "http://localhost:8080/sample/satoken/portal/announcement"` |
| **公开商品详情(路径通配)** | `PublicPortalController` | `GET /sample/satoken/portal/products/{productId}` | `curl "http://localhost:8080/sample/satoken/portal/products/SEC-001"` |
| **VIP底价查询(@Anonymous(false))** | `PublicPortalController` | `GET /sample/satoken/portal/products/{productId}/vip-price` | `curl "http://localhost:8080/sample/satoken/portal/products/SEC-001/vip-price" -H "satoken: <TOKEN>"` |
| **SPI动态白名单开放商品类目** | `PublicPortalController` | `GET /sample/satoken/portal/categories` | `curl "http://localhost:8080/sample/satoken/portal/categories"` |
| **查看当前动态路径权限规则** | `PublicPortalController` | `GET /sample/satoken/portal/rules` | `curl "http://localhost:8080/sample/satoken/portal/rules"` |
| **动态权限受控接口查询** | `PublicPortalController` | `GET /sample/satoken/portal/reports/daily` | `curl "http://localhost:8080/sample/satoken/portal/reports/daily" -H "satoken: <TOKEN>"` |
| **运行时热刷新动态路由规则** | `PublicPortalController` | `POST /sample/satoken/portal/rules/refresh` | `curl -X POST "http://localhost:8080/sample/satoken/portal/rules/refresh"` |
| **内网机密清算凭证(@CheckSameToken)** | `InternalClusterController` | `GET /sample/satoken/internal/clearing/voucher` | `curl "http://localhost:8080/sample/satoken/internal/clearing/voucher" -H "SA-SAME-TOKEN: SATOKEN_INTERNAL_SAME_TOKEN_SECRET_987654"` |
| **演示跨微服务内部清算调用** | `InternalClusterController` | `POST /sample/satoken/internal/call-clearing` | `curl -X POST "http://localhost:8080/sample/satoken/internal/call-clearing"` |
| **查询当前集群节点唯一实例ID** | `InternalClusterController` | `GET /sample/satoken/cluster/instance-id` | `curl "http://localhost:8080/sample/satoken/cluster/instance-id"` |
| **广播指定用户权限缓存全网失效** | `InternalClusterController` | `POST /sample/satoken/cluster/refresh-user` | `curl -X POST "http://localhost:8080/sample/satoken/cluster/refresh-user?userId=10086"` |
| **广播指定角色权限缓存全网失效** | `InternalClusterController` | `POST /sample/satoken/cluster/refresh-role` | `curl -X POST "http://localhost:8080/sample/satoken/cluster/refresh-role?roleCode=admin"` |
| **广播全网清空两级权限缓存** | `InternalClusterController` | `POST /sample/satoken/cluster/clear-all` | `curl -X POST "http://localhost:8080/sample/satoken/cluster/clear-all"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-satoken,ateng-boot-samples/ateng-boot-sample-satoken -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-satoken README](../../ateng-boot-satoken/README.md)。
