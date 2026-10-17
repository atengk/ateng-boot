# ateng-boot-sample-basic 基础脚手架自验工程

> 面向生产就绪的极简 CRUD、多租户隔离、数据权限与分层双防校验脚手架示范。

---

## 1. 场景痛点与功能定位

在新建业务微服务或单体工程时，开发者常陷入样板代码泥潭：统一响应格式封装、实体审计字段填充、行级租户隔离、数据权限过滤、接口防重放加签以及批量数据校验等基础设施配置繁重且容易漏配。

`ateng-boot-sample-basic` 提供了最小且完整的开箱即用工程化示范：
- **纯内存闭环**：基于 H2 内存库与自动 schema 初始化，零外部数据库依赖，启动即测；
- **分层双防校验**：第一道防线 Controller JSR-303 + `ValidList`，第二道防线 Service `BusinessAssert` 与 `ValidatorUtil`；
- **MyBatis-Plus 全家桶**：通用实体五级阶梯链、审计自动填充、行级租户（`@IgnoreTenant`）、声明式数据权限（`@DataScope`）、高性能批量插入（`AtengBaseService`）；
- **接口安全防御**：`@ApiSign` 接口防重放与请求加签验签。

---

## 2. 最小可用代码与启动测试

### 本地启动
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-basic spring-boot:run
```

### 极简 CRUD 示例（接入层薄层 + Service 业务防腐）
```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateDTO dto) {
        return Result.success(userService.createUser(dto));
    }

    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable("id") Long id) {
        return Result.success(userService.getUserById(id));
    }

    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(PageQuery query) {
        return Result.success(userService.pageUsers(query));
    }
}
```

### 验证统一契约响应
```bash
curl -i -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","age":28,"email":"zhangsan@example.com"}'
```

**预期 JSON 响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "104851841289146368",
  "success": true,
  "traceId": "7f8b9c1d0e2f4a3b"
}
```

---

## 3. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-basic -am -T 1C
```
涵盖 10 个端到端集成测试套件（CRUD、租户隔离、数据权限、分批插入、API加签等），秒级全绿准出。
