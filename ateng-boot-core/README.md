# ateng-boot-core 核心通用底座

> 提供全局统一 API 响应契约、异常体系、业务断言、用户审计 SPI、异步上下文传递工具、全场景敏感数据脱敏与纯原生分布式 ID 发号引擎。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-core` 是整个框架最轻量、最高内聚的底层核心依赖库。**坚守零重型外部依赖不变量**，100% 依托 JDK 17 与 Spring 6 原生能力，无任何全量工具包污染，可独立于 Web 环境使用（支持 CLI、批处理、微服务或后台工作流）。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-core</artifactId>
</dependency>
```

---

## 2. 统一 API 响应契约 (`Result<T>`)

### 场景痛点
避免使用 `@ResponseBodyAdvice` 全局强转黑魔法（破坏 Swagger、字符串强转异常、文件下载失效等），坚持显式声明返回类型，保障强类型自洽。

### 核心代码范式
```java
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.code.CommonResultCode;

// 1. 成功响应（无载荷）
Result<Void> res1 = Result.success();

// 2. 成功响应（携带载荷）
Result<UserVO> res2 = Result.success(userVO);

// 3. 失败响应（标准错误码枚举）
Result<Void> res3 = Result.fail(CommonResultCode.NOT_FOUND);

// 4. 失败响应（自定义状态码与提示信息）
Result<Void> res4 = Result.fail(403, "账户已被冻结，请联系管理员");

// 5. 链式安全取值（失败时直接抛出业务异常 BusinessException）
UserVO user = res2.getOrThrow();

// 6. 函数式映射（成功时变换载荷类型，失败时自动透传错误码与 traceId）
Result<UserDTO> dtoResult = userRpcService.queryUser();
Result<UserVO> voResult = dtoResult.map(UserVO::from);

// 7. 函数式观察与条件消费（支持流畅链式编排）
dtoResult.peek(u -> log.info("成功获取用户: {}", u.getName()))
         .ifFail(err -> log.error("调用失败: [{}] {}", err.getCode(), err.getMsg()));

// 8. 优雅兜底与定制异常抛出（orElse / orElseGet / orElseThrow）
UserDTO safeUser = dtoResult.orElseGet(UserDTO::guestUser);
UserDTO requiredUser = dtoResult.orElseThrow(CustomException::new);
```

### 预期响应 JSON 契约
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "id": "104485532445560832",
    "name": "张三"
  },
  "timestamp": 1776326400000,
  "success": true,
  "traceId": "c8a14b0365734fb4864f7bdf753b890a"
}
```

---

## 3. 通用传输层分页契约 (`PageQuery` & `PageResult<T>`)

### 场景痛点
传输层与持久层严格解耦，禁止将 MyBatis-Plus 的 `Page<T>` 泄露至 Controller 外部。

### 核心代码范式
```java
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;

// 1. 接收分页入参（自动进行边界防护：pageNum < 1 转为 1，pageSize > 500 转为 500）
@GetMapping("/page")
public Result<PageResult<UserVO>> queryPage(PageQuery pageQuery) {
    // 2. 构造或转换分页结果
    List<UserVO> list = List.of(new UserVO("张三"), new UserVO("李四"));
    PageResult<UserVO> pageResult = PageResult.of(list, 100L, pageQuery.getPageNum(), pageQuery.getPageSize());
    return Result.success(pageResult);
}
```

---

## 4. 统一业务异常与语义化断言 (`BusinessException` & `BusinessAssert`)

### 场景痛点
告别满屏冗余的 `if (...) throw new RuntimeException(...)`，提供类似 Spring `Assert` 但语义化更强的业务断言门面。

### 核心代码范式
```java
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.code.CommonResultCode;

// 1. 直接抛出业务异常
throw new BusinessException(CommonResultCode.UNAUTHORIZED);
throw new BusinessException(40001, "验证码已失效");

// 2. 语义化前置断言
BusinessAssert.isTrue(order.getAmount().compareTo(BigDecimal.ZERO) > 0, "订单金额必须大于0");
BusinessAssert.notNull(user, CommonResultCode.NOT_FOUND);
BusinessAssert.hasText(dto.getUsername(), "用户名不能为空");
BusinessAssert.notEmpty(dto.getRoleIds(), "必须至少分配一个角色");
```

---

## 5. 纯原生分布式雪花发号器 (`Snowflake`) 与 ID 门面 (`IdUtil`)

### 场景痛点
摆脱大型第三方依赖与集中式协调中间件，在纯 JDK 17 环境下实现高并发、递增、趋势有序的 64 位分布式主键发号与轻量短 ID。

### 特性矩阵
- **2026 自定义纪元**：`1767225600000L`，ID 紧凑优雅，提供长达 69 年生命周期；
- **时钟回拨容错防护**：$\le 5\text{ms}$ 自旋容错等待追平，$> 5\text{ms}$ 快速熔断抛出异常，绝不发重复号；
- **四级自适应节点推导**：按序探测 JVM 系统属性 -> 本地环境变量 -> 网卡 MAC / IP 哈希 -> 容器 Pod 命名序号，零配置开箱即用；
- **高性能无锁短 ID**：基于 `ThreadLocalRandom` 的无横线 32 位 UUID 与 21 位 URL 安全 NanoId。

### 核心代码范式
```java
import io.github.atengk.boot.core.util.IdUtil;

// 1. 生成 64 位全局唯一递增雪花 Long ID
long id = IdUtil.nextId(); // 如 104485532445560832L

// 2. 生成雪花 ID 字符串（前端防精度截断）
String idStr = IdUtil.nextIdStr(); // "104485532445560832"

// 3. 高性能 32 位无横线 UUID（比标准 UUID.randomUUID() 快 2~3 倍）
String simpleUuid = IdUtil.fastSimpleUUID(); // "4a6df7a0bc1249b8918231012ab987de"

// 4. 高性能标准 36 位 UUID
String uuid = IdUtil.fastUUID(); // "4a6df7a0-bc12-49b8-9182-31012ab987de"

// 5. 21 位 URL 安全 NanoId（随机无碰撞短链接/文件代号）
String nanoId = IdUtil.nanoId(); // "V1StGXR8_Z5jdHi6B-myT"
```

### 部署与节点自定义覆盖
在集群部署中，可通过 JVM 参数或环境变量显式指定节点编号（0~31）：
```bash
java -Dateng.boot.snowflake.worker-id=2 -Dateng.boot.snowflake.datacenter-id=1 -jar app.jar
```

---

## 6. 全场景敏感数据脱敏体系 (`DesensitizeUtil` & `@FieldSensitive`)

### 场景痛点
涵盖国内常见的所有隐私合规字段，纯 JDK 算法实现，毫秒级脱敏。支持静态工具调用与视图层出界动态打码（配合 Jackson），源数据在 JVM 内存中 100% 保持未脱敏真明文，严格防范打星号脏数据写回持久层引发数据损毁（[ADR-0012](./docs/adr/0012-lifecycle-separated-data-security.md)）。

### 核心代码范式
#### 1. 视图层 / DTO 字段动态脱敏（自动适配 Controller JSON 响应）
```java
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;

public class UserVO {
    private Long id;

    // 预设脱敏策略：手机号（138****5678）
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    // 预设脱敏策略：身份证号（110101********2345）
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    // 预设脱敏策略：中文姓名（两字变 张*，多字变 诸**明）
    @FieldSensitive(SensitiveType.CHINESE_NAME)
    private String name;

    // 自定义区间脱敏：索引 [2, 5) 替换为 '#'
    @FieldSensitive(startInclude = 2, endExclude = 5, maskChar = '#')
    private String customCode;

    // 首尾保留字符数脱敏：保留前2位与后2位
    @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 2, keepTail = 2, maskChar = '#')
    private String token;
}
```

#### 2. 底层通用脱敏原语 (`DesensitizeUtil`)
```java
import io.github.atengk.boot.core.util.DesensitizeUtil;

// 1. 手机号码：保留前3后4，中间4位掩码
DesensitizeUtil.mobilePhone("13812345678"); // 138****5678

// 2. 居民身份证号：18位保留前6后4
DesensitizeUtil.idCard("110101199003072345"); // 110101********2345

// 3. 中文姓名：两字隐藏后一位，三字以上仅保留首尾
DesensitizeUtil.chineseName("张三");   // 张*
DesensitizeUtil.chineseName("诸葛孔明"); // 诸**明

// 4. 电子邮箱：保留前缀首尾及域名
DesensitizeUtil.email("atengk@example.com"); // a****k@example.com

// 5. 固定电话：区号与后两位保留
DesensitizeUtil.fixedPhone("010-88886666"); // 010-****6666

// 6. 国内地址：隐藏详细门牌门牌号
DesensitizeUtil.address("北京市海淀区中关村南大街1号院8号楼502"); // 北京市海淀区中关村南大街********

// 7. 车牌号（普通7位燃油车与8位新能源绿牌）
DesensitizeUtil.carLicense("京A88888");  // 京A***88
DesensitizeUtil.carLicense("粤B12345D"); // 粤B****5D

// 8. IP 地址（IPv4 / IPv6）
DesensitizeUtil.ipv4("192.168.1.100"); // 192.168.*.*
DesensitizeUtil.ipv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334"); // 2001:db8:****:7334

// 9. 统一社会信用代码（18位法人证照）
DesensitizeUtil.socialCreditCode("91110000000000001X"); // 91110000********1X

// 10. 自定义首尾保留位数（例如保留前2位、后2位）
DesensitizeUtil.keepHeadAndTail("TOPSECRETTOKEN123456", 2, 2); // TO****************56
```

### 预期响应 JSON 呈现
```json
{
  "id": "104485532445560832",
  "phone": "138****5678",
  "idCard": "110101********2345",
  "name": "张*",
  "customCode": "12###67890",
  "token": "TO################56"
}
```

---

## 7. 轻量原生加解密与哈希摘要 (`CryptoUtil` & `DigestUtil`)

### 场景痛点
提供符合现代密码学安全规范的纯 JDK 原生加解密封装，杜绝由于硬编码 IV 或 ECB 模式导致的重放攻击漏洞，100% 零第三方加密依赖。

### 核心代码范式
```java
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DigestUtil;

// ==================== 1. AES/GCM 认证加解密 (推荐，认证防篡改) ====================
// 生成 256 位安全密钥与 12 字节 GCM 推荐 IV
byte[] gcmKey = CryptoUtil.generateKey(256);
byte[] gcmIv = CryptoUtil.generateGcmIv();

// 字符串 Base64 契约（带可选 AAD 附加认证数据防篡改）
String cipherText = CryptoUtil.encryptGcm("机密密码123", gcmKey, gcmIv);
String plainText = CryptoUtil.decryptGcm(cipherText, gcmKey, gcmIv);

// ==================== 2. AES/CBC 对称加解密 ====================
byte[] cbcKey = CryptoUtil.generateKey(128);
byte[] cbcIv = CryptoUtil.generateIv(); // 16 字节 IV
String cbcCipher = CryptoUtil.encryptCbc("明文凭证", cbcKey, cbcIv);
String cbcPlain = CryptoUtil.decryptCbc(cbcCipher, cbcKey, cbcIv);

// ==================== 3. 消息哈希与摘要 (DigestUtil) ====================
String sha256Hex = DigestUtil.sha256Hex("raw_text");
String md5Hex = DigestUtil.md5Hex("raw_text");
```

---

## 8. 通用线性树形与森林装配工具 (`TreeUtil`)

### 场景痛点
菜单树、组织架构树、品类目录等递归组装是业务极高频操作，传统两层循环性能为 $O(N^2)$，容易发生栈溢出。`TreeUtil` 采用哈希辅助表实现 $O(N)$ 极速装配，并支持多根谓词成林与递归剪枝过滤。

### 核心代码范式
```java
import io.github.atengk.boot.core.util.TreeUtil;

// 待组装的平铺扁平集合
List<MenuNode> flatList = menuService.listAll();

// 1. 指定根节点父 ID 组装单根/多根树（O(N) 线性装配）
List<MenuNode> menuTree = TreeUtil.build(
        flatList,
        0L,                        // 根节点父 ID 标识
        MenuNode::getId,           // 节点 ID 提取器
        MenuNode::getParentId,     // 父节点 ID 提取器
        MenuNode::setChildren      // 子节点集合设置器
);

// 2. 基于自定义谓词成林（适用于非固定 parentId、孤儿节点或复杂根规则森林）
List<MenuNode> forest = TreeUtil.buildForest(
        flatList,
        node -> node.getParentId() == null || node.getParentId() <= 0,
        MenuNode::getId,
        MenuNode::getParentId,
        MenuNode::setChildren
);

// 3. 递归条件剪枝过滤（保留命中文本的节点及其祖先链路，剔除无关分支）
List<MenuNode> filteredTree = TreeUtil.filter(
        menuTree,
        node -> node.getName().contains("导出"),
        MenuNode::getChildren,
        MenuNode::setChildren
);
```

---

## 9. 原生不可变元组标准件 (`Pair<L, R>` & `Triple<L, M, R>`)

### 场景痛点
业务方法常需要返回多个关联属性（如 `count` 与 `totalAmount`，或 `min/avg/max` 区间三要素），创建专用 DTO 过重，使用 `Map<String, Object>` 丧失强类型安全。

### 核心特性
- **JDK 17 Record 原生实现**：100% 具备值对象语义（不可变、自动实现 `equals`、`hashCode` 与 `toString`）；
- **全生态别名兼容**：支持 `left()/right()`、`getLeft()/getRight()` 以及 `getKey()/getValue()` 别名，平滑对接 Map/Stream 流；
- **原生可序列化**：标配实现 `Serializable`，支持 RPC、缓存与文件序列化。

### 核心代码范式
```java
import io.github.atengk.boot.core.model.Pair;
import io.github.atengk.boot.core.model.Triple;

// 1. 构造与解构双元组 Pair
Pair<String, Long> pair = Pair.of("用户总数", 1024L);
String key = pair.left();     // 或 pair.getKey()
Long value = pair.right();    // 或 pair.getValue()

// 2. 构造与解构三元组 Triple
Triple<String, BigDecimal, Boolean> triple = Triple.of("VIP-01", new BigDecimal("99.9"), true);
String grade = triple.left();
BigDecimal price = triple.middle();
Boolean enabled = triple.right();
```

---

## 10. 用户与多租户上下文及四维异步透传 (`UserContextHolder`, `TenantContextHolder` & `AsyncContext`)

### 场景痛点
在分布式与 SaaS 架构下，异步线程池（如 `@Async`、`CompletableFuture`、自定义线程池）执行任务时，主线程的链路追踪 `traceId`、当前登录操作人、多租户上下文与忽略租户状态极易发生上下文丢失或线程池污染，导致跨租户越权漏洞。

### 核心特性
- **四维上下文完整快照**：$\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$；
- **双重 SPI 可插拔挂载**：`CurrentUserProvider` 与 `CurrentTenantProvider`，无缝桥接 Sa-Token 等安全框架；
- **栈式租户嵌套与忽略深度**：支持在单次请求中临时切换租户或嵌套忽略租户过滤，方法执行完毕严格恢复上下文；
- **全生命周期现场克隆与恢复**：执行任务前克隆工作线程既有状态，任务执行后严格恢复工作线程现场，杜绝线程污染与内存泄漏。

### 核心代码范式
```java
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;

// ==================== 1. 用户上下文 (UserContextHolder) ====================
UserContextHolder.setUserId("10086");
String userId = UserContextHolder.getUserId();

// 挂载全局 CurrentUserProvider SPI 外部动态降级兜底（例如无缝桥接 Sa-Token 会话）
UserContextHolder.setUserProvider(LoginHelper::getUserId);

// 闭包式执行（执行完成后自动清理上下文）
UserContextHolder.runWithUser("10086", () -> {
    orderService.processOrder();
});

// ==================== 2. 多租户上下文 (TenantContextHolder) ====================
// 设置与获取租户标识（支持 String 与 Long）
TenantContextHolder.setTenantId("tenant_001");
String tenantId = TenantContextHolder.getTenantId();

// 挂载全局 CurrentTenantProvider SPI 外部动态降级兜底
TenantContextHolder.setTenantProvider(LoginHelper::getTenantId);

// 闭包式在临时租户环境中执行
TenantContextHolder.runWithTenant("tenant_002", () -> {
    tenantDataSyncService.syncData();
});

// 闭包式受控忽略租户拦截（如跨租户全局报表统计）
TenantContextHolder.runWithoutTenant(() -> {
    sysDictService.initSystemCache();
});

// ==================== 3. 四维上下文跨线程透传 (AsyncContext) ====================
// 包装 Runnable / Callable 跨线程安全执行
CompletableFuture.runAsync(AsyncContext.wrap(() -> {
    // 自动无缝继承主线程的 traceId、userId、tenantId 及 ignoreTenant 状态
    reportService.generateReport();
}), taskExecutor);

// 包装 Supplier<T>（避免 lambda 歧义）
CompletableFuture.supplyAsync(AsyncContext.wrapSupplier(() -> {
    return billingService.calculateInvoice();
}), taskExecutor);

// ==================== 4. 开箱即用自适应双模异步执行器 (AtengAsyncAutoConfiguration) ====================
// 位于 core 底座，自动注入 ContextCopyingTaskDecorator，支持 Spring @Async 零配置开箱即用
// 1. 默认平台线程池模式（前缀 ateng-async-）
// 2. JDK 21+ 虚拟线程模式：配置 spring.threads.virtual.enabled=true 自动自适应切换为 SimpleAsyncTaskExecutor（前缀 ateng-vt-async-）
// application.yml 配置项：
// spring:
//   threads:
//     virtual:
//       enabled: true # 开启虚拟线程（需 JDK 21+ 运行时，默认 false）
// ateng:
//   boot:
//     async:
//       core-pool-size: 8
//       max-pool-size: 16
//       queue-capacity: 1000
//       thread-name-prefix: "ateng-async-"
//       virtual-thread-name-prefix: "ateng-vt-async-" # 虚拟线程专属前缀
//       concurrency-limit: 64                         # 虚拟线程并发限制（防下游连接池击穿，默认 -1 无界）
//       await-termination-seconds: 60
```

---

## 11. 未来中间件孵化通用原语套件

> 为未来扩展存储（Storage）、消息队列（MQ）、分布式任务调度（Job）、邮件短信及 AI 等功能模块提供统一的纯原生零外部依赖基础设施标准件。

### 1. Spring 容器静态门面 (`SpringUtil`)
在非受管 POJO、静态工具类或异步闭包中安全获取 Spring Bean、环境变量与发布事件：
```java
import io.github.atengk.boot.core.util.SpringUtil;

// 1. 获取已注册的 Bean
UserService userService = SpringUtil.getBean(UserService.class);
DataSource dataSource = SpringUtil.getBean("dataSource", DataSource.class);

// 2. 读取配置属性与环境环境
String port = SpringUtil.getProperty("server.port", "8080");
Integer timeout = SpringUtil.getProperty("rpc.timeout", Integer.class, 3000);
String activeProfile = SpringUtil.getActiveProfile();

// 3. 发布应用事件
SpringUtil.publishEvent(new UserRegisteredEvent(userId));
```

### 2. 强类型方法引用解析 (`LambdaUtil`)
纯 JDK 17 原生通过方法引用推导 JavaBean 字段名，告别易错的硬编码字符串属性名：
```java
import io.github.atengk.boot.core.util.LambdaUtil;

// 1. 标准 Getter 方法引用推导
String nameField = LambdaUtil.getFieldName(User::getName); // "name"
String ageField = LambdaUtil.getFieldName(User::getAge);   // "age"

// 2. boolean is 方法推导
String deletedField = LambdaUtil.getFieldName(User::isDeleted); // "deleted"

// 3. 连续大写缩写保护
String urlField = LambdaUtil.getFieldName(User::getURL); // "URL"
```

### 3. 内存零拷贝分批与安全收集器 (`CollectionUtil` & `MoreCollectors`)
```java
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.core.util.MoreCollectors;

// 1. 基于 subList 原生视图的零拷贝安全分批（内存防 OOM 批量流转）
List<Order> orders = orderMapper.selectHugeList();
List<List<Order>> batches = CollectionUtil.partition(orders, 500);
for (List<Order> batch : batches) {
    orderService.processBatch(batch);
}

// 2. 防 NPE 与防 DuplicateKey 的流收集器（原生 Collectors.toMap 当 Value 为 null 时会抛出 NPE）
Map<Long, String> userMap = userList.stream()
        .collect(MoreCollectors.toSafeMap(User::getId, User::getRemark));
```

### 4. 自动实例化反射拷贝与非空合并 (`BeanUtil`)
基于 Spring `BeanUtils` 提供强类型自动实例化与 Patch 增量覆盖：
```java
import io.github.atengk.boot.core.util.BeanUtil;

// 1. 单实体自动实例化拷贝
UserVO vo = BeanUtil.copy(userEntity, UserVO.class);

// 2. 列表批量拷贝（空安全返回空列表）
List<UserVO> voList = BeanUtil.copyList(userEntityList, UserVO.class);

// 3. 局部非空覆盖（仅用 source 的非 null 字段覆盖 target，保留 target 原有字段）
BeanUtil.copyIgnoreNull(patchDTO, existEntity);
```

### 5. 纯函数式指数退避重试执行器 (`RetryUtil`)
100% 原生 JDK 实现，无需依赖 Spring Retry 或 Resilience4j 等重型 AOP 框架：
```java
import io.github.atengk.boot.core.util.RetryUtil;
import java.time.Duration;

// 1. 默认重试（最多 3 次，初始间隔 100ms，指数倍数 2.0）
String result = RetryUtil.execute(() -> remoteRpcService.call());

// 2. 定制化指数退避与异常谓词过滤
RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
        .maxAttempts(5)
        .delay(Duration.ofMillis(200))
        .multiplier(1.5)
        .maxDelay(Duration.ofSeconds(2))
        .retryPredicate(ex -> ex instanceof SocketTimeoutException) // 仅网络超时才重试
        .build();

RetryUtil.execute(() -> thirdPartyApiClient.sendRequest(), config);
```

### 6. 路径穿越安全清洗与容量格式化 (`FileUtil`)
```java
import io.github.atengk.boot.core.util.FileUtil;

// 1. 防御路径穿越攻击（彻底消除 ../ 与空字符路径穿越漏洞）
String cleanName = FileUtil.cleanFilename("../../etc/passwd"); // "passwd"
String safeDoc = FileUtil.cleanFilename("report\0.pdf");        // "report.pdf"

// 2. 扩展名与主文件名提取（统一转小写）
String ext = FileUtil.getExtName("archive.tar.gz"); // "gz"
String base = FileUtil.getBaseName("avatar.png");   // "avatar"

// 3. 字节容量人类可读格式化
String size1 = FileUtil.formatSize(1536);          // "1.50 KB"
String size2 = FileUtil.formatSize(10485760);       // "10.00 MB"
String size3 = FileUtil.formatSize(2684354560L);    // "2.50 GB"
```

### 7. 命名线程工厂标准件 (`NamedThreadFactory`)
纯 JDK 17 原生实现，为自建线程池提供统一前缀、自增序号、守护线程属性与未捕获异常防护，彻底消除线上线程转储 (`jstack`) 中无意义的 `pool-N-thread-M` 命名：
```java
import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 1. 创建非守护线程工厂（业务工作线程）
ExecutorService workerPool = Executors.newFixedThreadPool(8, new NamedThreadFactory("order-worker"));
// 线程名依次为: order-worker-1, order-worker-2, ...

// 2. 创建守护线程工厂（后台监控/轮询）
ExecutorService daemonPool = Executors.newCachedThreadPool(new NamedThreadFactory("mq-pull", true));

// 3. 带有未捕获异常处理器的线程工厂
NamedThreadFactory factory = new NamedThreadFactory("sync-job", false, (thread, ex) -> {
    log.error("线程 [{}] 发生未捕获致命异常: ", thread.getName(), ex);
});
```

### 8. 常用业务数据格式编程式校验 (`ValidatorUtil`)
100% 依托原生能力，提供手机号、中国居民身份证（带模 11 加权校验码与出生日期真伪验证）、邮箱、IP、车牌号及统一社会信用代码的高精度编程式布尔断言：
```java
import io.github.atengk.boot.core.util.ValidatorUtil;

// 1. 手机号码与电话校验
boolean v1 = ValidatorUtil.isMobile("13812345678"); // true
boolean v2 = ValidatorUtil.isPhone("010-88889999"); // true

// 2. 18 位身份证权威加权模 11 校验
boolean v3 = ValidatorUtil.isIdCard("11010519491231002X"); // true (包含生日真实性与校验位校验)

// 3. 电子邮箱校验
boolean v4 = ValidatorUtil.isEmail("admin@company.com"); // true

// 4. IP 地址校验 (IPv4 / IPv6)
boolean v5 = ValidatorUtil.isIpv4("192.168.1.1"); // true
boolean v6 = ValidatorUtil.isIp("::1");           // true

// 5. 中国车牌号 (含新能源绿牌)
boolean v7 = ValidatorUtil.isCarLicense("京A88888");  // true
boolean v8 = ValidatorUtil.isCarLicense("粤B12345D"); // true

// 6. 统一社会信用代码 (18位法人证照)
boolean v9 = ValidatorUtil.isSocialCreditCode("91110000000000001X"); // true

// 7. POJO 编程式整对象快速失败校验（不通过直接抛出 BusinessException 400）
UserDTO user = new UserDTO(null, "", "13800000000");
ValidatorUtil.validate(user); // 抛出 BusinessException: "用户名不能为空"

// 8. 支持指定校验分组
ValidatorUtil.validate(user, ValidationGroups.Update.class);

// 9. 单属性校验断言
ValidatorUtil.validateProperty(user, "username");
```

### 9. 校验分组标准契约 (`ValidationGroups`)
解决全系统分组 Marker 接口随意定义、语义割裂的痛点，提供高频统一标记接口：
```java
import io.github.atengk.boot.core.validation.ValidationGroups;
import jakarta.validation.constraints.*;

public class UserDTO {
    // 仅在 Update 分组下要求必填
    @NotNull(groups = ValidationGroups.Update.class, message = "修改时用户ID不能为空")
    private Long id;

    // 默认全分组必填
    @NotBlank(message = "用户名不能为空")
    private String username;

    // 仅在 Status 状态流转分组下校验
    @NotNull(groups = ValidationGroups.Status.class, message = "变更状态不能为空")
    private Integer status;
}
```

### 10. 批量级联校验 List 包装器 (`ValidList`)
Spring MVC 默认接收 `@RequestBody @Valid List<DTO>` 时会忽略元素内部字段校验，导致非法数据穿透。`ValidList<E>` 原生透传集合方法并支持完整级联校验：
```java
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    /**
     * 批量创建用户：传入 ValidList 自动级联触发每个 UserCreateDTO 内部字段校验
     */
    @PostMapping("/batch")
    public Result<Void> batchCreate(@RequestBody @Valid ValidList<UserCreateDTO> userList) {
        userService.saveBatch(userList);
        return Result.success();
    }
}
```

---

## 12. 模块配置属性参考 (`application.yml`)

`ateng-boot-core` 内置自适应双模受管异步任务执行器与四维上下文透传引擎（`AtengAsyncAutoConfiguration`），支持平台线程池与 JDK 21+ 虚拟线程无缝自适应，可在 `application.yml` 中按需定制配置：

```yaml
# 开启 Spring Boot 官方虚拟线程（仅在 JDK 21+ 运行时生效，默认 false）
spring:
  threads:
    virtual:
      enabled: true

ateng:
  boot:
    async:
      # 是否启用受管异步执行器装配（默认 true）
      enabled: true
      # 核心线程数（平台线程模式，默认可用 CPU 核心数）
      core-pool-size: 8
      # 最大线程数（平台线程模式，默认可用 CPU 核心数 * 2）
      max-pool-size: 16
      # 阻塞队列容量（平台线程模式，默认 1000）
      queue-capacity: 1000
      # 空闲线程存活时间（平台线程模式，秒，默认 60）
      keep-alive-seconds: 60
      # 平台线程工作线程名前缀（默认 ateng-async-）
      thread-name-prefix: "ateng-async-"
      # 虚拟线程工作线程名前缀（默认 ateng-vt-async-，虚拟线程模式下生效）
      virtual-thread-name-prefix: "ateng-vt-async-"
      # 虚拟线程并发任务限制数（默认 -1 即无界；可按需配置限制防下游 HikariCP 连接池击穿）
      concurrency-limit: -1
      # 容器关闭时是否等待任务执行完成（优雅停机，默认 true）
      wait-for-tasks-to-complete-on-shutdown: true
      # 优雅停机等待超时时间（秒，默认 60）
      await-termination-seconds: 60
```
