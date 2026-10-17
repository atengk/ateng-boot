# ateng-boot-sample-satoken 安全鉴权与审计流转自验工程

> 专一 Sa-Token 鉴权、路由拦截白名单、Service 方法级权限与审计字段自动流转示范。

---

## 1. 场景痛点与功能定位

在构建企业级微服务或单体系统时，权限控制和审计追踪（谁在何时修改了什么）常常面临配置割裂、框架层与持久层无法互通、必须依赖庞大 Redis 等痛点。

`ateng-boot-sample-satoken` 提供了清晰自包含的鉴权集成示范：
- **自适应存储降级**：无 Redis 环境下自动降级为基于内存的高速会话存储，无需启动外置 Redis 即可全功能验证；
- **双轨路由拦截与白名单放行**：优雅配置无需鉴权的公开接口与 OpenAPI/Swagger 路径；
- **方法级 AOP 鉴权**：在 Service 层标注 `@SaCheckRole("admin")` 或 `@SaCheckPermission("user:export")`；
- **全链路操作人与租户审计**：用户登录后，Sa-Token 自动打通 Core 模块的 `CurrentUserProvider` 与 `CurrentTenantProvider`，MyBatis-Plus 在执行保存/更新时无感自动填充 `create_user`、`update_user` 与 `tenant_id`。

---

## 2. 最小可用代码与启动测试

### 本地启动
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-satoken spring-boot:run
```

### 登录与受保护业务接口示例
```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class SaTokenSampleController {

    private final SaTokenSampleService sampleService;

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginUser loginUser) {
        LoginHelper.login(loginUser);
        return Result.success(LoginHelper.getTokenValue());
    }

    @GetMapping("/admin-service")
    public Result<String> testAdminService() {
        // 内部方法标注 @SaCheckRole("admin") 方法级鉴权拦截
        return Result.success(sampleService.adminServiceMethod());
    }

    @PostMapping("/order")
    public Result<Long> createOrder(@RequestBody @Validated OrderCreateDTO dto) {
        LoginHelper.checkLogin();
        // 执行持久化时由 CurrentUserProvider SPI 自动填充当前登录人至 create_by 与 update_by
        return Result.success(sampleService.createOrder(dto.getOrderNo(), dto.getAmount()));
    }
}
```

### 验证未登录拦截 (401)
```bash
curl -i http://localhost:8080/api/auth/admin-service
```

**未认证预期响应**：
```json
{
  "code": 401,
  "msg": "未能获取有效的登录凭证，请先登录",
  "data": null,
  "success": false,
  "traceId": "1a2b3c4d5e6f7a8b"
}
```

---

## 3. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-satoken -am -T 1C
```
涵盖未认证拦截、角色权限核验、登录注销、以及跨框架审计人注入数据库实体等 16 个测试用例，秒级全绿。
