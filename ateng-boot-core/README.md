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

## 2. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **API 响应契约** | 强类型通用统一响应模型 | `Result<T>` | - | `Result.success()`, `Result.success(data)`, `Result.fail(...)`, `getOrThrow()`, `map()`, `peek()`, `ifFail()`, `orElseGet()`, `orElseThrow()` | `ResultTest.java`, `SampleApiResponseTest.java` |
| **标准状态码** | 统一状态码契约与预置枚举 | `IResultCode`, `CommonResultCode` | - | `CommonResultCode.SUCCESS(200)`, `BAD_REQUEST(400)`, `UNAUTHORIZED(401)`, `FORBIDDEN(403)`, `NOT_FOUND(404)`, `INTERNAL_SERVER_ERROR(500)` | `CommonResultCodeTest.java` |
| **分页传输契约** | 防越界分页入参、强类型排序与纯净出参模型 | `PageQuery`, `SortItem`, `PageResult<T>` | - | `PageQuery.getCurrent()`, `PageQuery.getSize()`, `PageQuery.addAsc/Desc()`, `SortItem.asc/desc()`, `PageResult.of(list, total, current, size)`, `PageResult.map(mapper)` | `PageQueryTest.java`, `SortItemTest.java`, `PageResultTest.java` |
| **因果异常体系** | 业务受控异常与因果堆栈双模 | `BusinessException` | - | `BusinessException(resultCode)`, `BusinessException(code, msg, cause)`（有 cause 默认保留堆栈）, `BusinessException(..., writableStackTrace)` | `BusinessExceptionTest.java`, `SampleCoreIntegrationTest.java` |
| **业务语义断言** | 快速失败业务语义前置防卫门面 | `BusinessAssert` | - | `isTrue(...)`, `isFalse(...)`, `notNull(...)`, `isNull(...)`, `hasText(...)`, `notEmpty(...)`, `equals(...)` | `BusinessAssertTest.java` |
| **分布式发号器** | 2026 纪元雪花发号器与无锁短 ID | `IdUtil`, `Snowflake` | - | `IdUtil.nextId()`, `IdUtil.nextIdStr()`, `IdUtil.fastSimpleUUID()`, `IdUtil.fastUUID()`, `IdUtil.nanoId()` | `IdUtilTest.java`, `SnowflakeTest.java` |
| **四维异步传播** | 受管线程池上下文捕获与还原 | `AsyncContext`, `ContextCopyingTaskDecorator` | `ateng.boot.async.*` | `AsyncContext.wrap(runnable)`, `wrap(callable)`, `wrapSupplier(supplier)`, 传递 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ | `AsyncContextTest.java`, `ContextCopyingTaskDecoratorTest.java` |
| **异步受管配置** | 自适应平台与虚拟线程池自动装配 | `AsyncProperties`, `AtengAsyncAutoConfiguration` | `ateng.boot.async.enabled`, `core-pool-size`, `max-pool-size`, `queue-capacity`, `keep-alive`, `thread-name-prefix`, `virtual-thread-name-prefix`, `concurrency-limit`, `wait-for-tasks-to-complete-on-shutdown`, `await-termination` | `AtengAsyncAutoConfiguration` 初始化上下文感知线程池并提供优雅停机钩子 | `AtengAsyncAutoConfigurationTest.java` |
| **命名线程工厂** | 线程池命名与异常处理标准件 | `NamedThreadFactory` | - | `NamedThreadFactory(prefix)`, `newThread(runnable)`（支持自定义前缀、守护属性与 UncaughtExceptionHandler，消除匿名线程池） | `NamedThreadFactoryTest.java` |
| **上下文持有者** | 用户身份与多租户无状态隔离 | `UserContextHolder`, `TenantContextHolder` | - | `getUserId()`, `setUserId()`, `getTenantId()`, `setTenantId()`, `ignoreTenant(supplier)`, `clear()` | `UserContextHolderTest.java`, `TenantContextHolderTest.java` |
| **审计/租户 SPI** | 跨中间件解耦上下文扩展点 | `CurrentUserProvider`, `CurrentTenantProvider` | - | `getUserId()`, `getUserName()`, `getTenantId()`（供下游 Sa-Token / MyBatis-Plus 无缝对接） | `SampleTenantTest.java` |
| **安全序列化** | 零 `@class` 污染高性能 Jackson 门面 | `JsonUtil` | - | `toJsonString(obj)`, `toPrettyJsonString(obj)`, `toJsonStringWithLongToString(obj)`, `parseObject(...)`, `parseList(...)`, `parseMap(...)` | `JsonUtilTest.java` |
| **敏感数据脱敏** | 12 大类敏感信息动态掩码 | `@FieldSensitive`, `DesensitizeUtil`, `SensitiveJsonSerializer`, `SensitiveType` | - | `DesensitizeUtil.desensitize(str, type)`, `mobilePhone(str)`, `idCard(str)` 等，保证“内存真明文”防脏写 | `DesensitizeUtilTest.java`, `SensitiveJsonSerializerTest.java` |
| **分层防卫校验** | 批量列表防漏检与分组校验 | `ValidList<E>`, `ValidationGroups`, `ValidatorUtil` | - | `ValidList<E>` 解决 Spring 集合漏检；`ValidationGroups.Create/Update`；`ValidatorUtil.validate(obj)` | `ValidListTest.java`, `ValidatorUtilTest.java` |
| **退避指数重试** | 纯原生轻量无第三方重试工具 | `RetryUtil` | - | `execute(supplier, config, fallback)`, `executeAction(runnable, config, fallback)`，支持 Equal Jitter 平滑随机抖动、onRetry 监听器、指数退避与 Fallback 降级兜底 | `RetryUtilTest.java` |
| **加解密与摘要** | 原生安全加解密与摘要算法门面 | `CryptoUtil`, `DigestUtil` | - | `CryptoUtil.aesEncrypt/Decrypt`, `sm4Encrypt/Decrypt`, `DigestUtil.md5`, `sha256`, `sm3` | `CryptoUtilTest.java`, `DigestUtilTest.java` |
| **安全文件处理** | 路径穿越防御与扩展名容量格式化 | `FileUtil` | - | `FileUtil.cleanFilename(name)`（防御 `../` 路径穿越）, `getExtName(name)`, `getBaseName(name)`, `formatSize(bytes)` | `FileUtilTest.java` |
| **集合切片防空** | 零内存拷贝分批切片与判空防御 | `CollectionUtil` | - | `CollectionUtil.isEmpty/isNotEmpty(col/map)`, `CollectionUtil.partition(list, batchSize)`（基于 subList 视图切片，防 OOM 批量流转） | `CollectionUtilTest.java` |
| **类型安全引用** | SerializedLambda 强类型字段推导 | `LambdaUtil`, `PropertyFunc` | - | `LambdaUtil.getFieldName(func)`（解析 `User::getName` -> `"name"`，带本地并发缓存）, `LambdaUtil.resolve(func)` | `LambdaUtilTest.java` |
| **标准日期常量** | 标准日期格式串与预编译格式化器 | `DatePattern` | - | `NORM_DATETIME_PATTERN`, `NORM_DATE_PATTERN`, `NORM_TIME_PATTERN`, `NORM_DATETIME_FORMATTER` 等不可变格式化常量 | `DatePatternTest.java` |
| **通用辅助原语** | 对象拷贝、不可变元组、收集器与树 | `BeanUtil`, `Pair`, `Triple`, `MoreCollectors`, `TreeUtil`, `SpringUtil`, `AtengCoreAutoConfiguration` | - | `BeanUtil.copyProperties`, `Pair.of`, `Triple.of`, `MoreCollectors.toLinkedMap`, `TreeUtil.buildTree`, `SpringUtil.getBean`, `SpringUtil.isInitialized` | `BeanUtilTest.java`, `PairTripleTest.java`, `TreeUtilTest.java`, `SpringUtilTest.java` |
| **事务同步门面** | 事务生命周期门面与上下文穿透 | `TransactionUtil` | - | `executeAfterCommit(task)`, `executeAfterRollback(task)`, `executeAfterCommitAsync(task)`, `publishAfterCommit(event)`, `isActualTransactionActive()`, `isSynchronizationActive()` | `TransactionUtilTest.java` |
| **操作审计契约** | 操作日志模型与操作动作枚举 | `OperateLogModel`, `OperateTypeEnum` | - | `OperateLogModel.builder()`, `model.isSuccess()`, `model.addExtra(k, v)`, `OperateTypeEnum.of(code)`, `OperateTypeEnum.fromValue(val)` | `OperateLogModelTest.java` |
| **日期时间门面** | 原生日期时间互转与跨度计算 | `DateTimeUtil` | - | `DateTimeUtil.toLocalDateTime(date)`, `toDate(ldt)`, `toEpochMilli(ldt)`, `beginOfDay(ldt)`, `endOfDay(ldt)`, `betweenDays(t1, t2)`, `format(ldt)`, `parseDateTime(str)` | `DateTimeUtilTest.java` |
| **高性能随机件** | 无锁安全随机与列表抽样洗牌 | `RandomUtil` | - | `RandomUtil.randomNumbers(len)`, `randomString(len)`, `randomInt(min, max)`, `randomEle(list)`, `randomEles(list, count)`, `shuffle(list)`, `shuffleNew(list)` | `RandomUtilTest.java` |

---

## 3. 统一 API 响应契约 (`Result<T>`)

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

## 4. 通用传输层分页契约 (`PageQuery` & `PageResult<T>`)

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

## 5. 统一业务异常体系与语义化断言 (`BusinessException` & `BusinessAssert`)

### 场景痛点
1. 传统业务开发充斥着满屏脆弱的 `if (...) throw new RuntimeException(...)`，缺乏统一状态码与规范契约；
2. 异常要么无脑抓取完整堆栈导致轻量校验性能暴跌，要么盲目抹空全部堆栈导致捕获底层系统/驱动异常时因果链丢失，线上排障如同盲盒。

### 双模堆栈治理机制 (Stack Trace Duality Invariant)
- **轻量无堆栈模式（默认业务校验）**：当单纯传递状态码或提示信息时（`cause == null`），`BusinessException` 默认重写 `fillInStackTrace() { return this; }` 并清空堆栈，零抓栈 CPU 开销，赋予前置校验最高吞吐；
- **全真因果链路保留模式（系统/驱动转译）**：当构造器传入了根因异常（`cause != null`）或显式声明 `writableStackTrace = true` 时，**自动采集并保留当前调用栈与原始 cause 因果链**，确保日志精确捕获业务转译代码行；
- **显式可控开关**：提供 `BusinessException(code, msg, cause, writableStackTrace)` 全量构造重载，赋予复杂中间件极端场景的绝对掌控力。

### 核心代码范式
```java
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.code.CommonResultCode;

// 1. 轻量业务中断（默认无堆栈采集开销，极速失败）
throw new BusinessException(CommonResultCode.UNAUTHORIZED);
throw new BusinessException(40001, "验证码已失效");

// 2. 携带底层驱动 Cause 根因异常（默认自动保留完整调用栈，绝不丢失因果链）
try {
    thirdPartyClient.invoke();
} catch (IOException e) {
    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "远程服务通信失败", e);
}

// 3. 语义化前置业务断言（校验未通过时自动抛出轻量 BusinessException）
BusinessAssert.isTrue(order.getAmount().compareTo(BigDecimal.ZERO) > 0, "订单金额必须大于0");
BusinessAssert.notNull(user, CommonResultCode.NOT_FOUND);
BusinessAssert.hasText(dto.getUsername(), "用户名不能为空");
BusinessAssert.notEmpty(dto.getRoleIds(), "必须至少分配一个角色");
```

---

## 6. 纯原生分布式雪花发号器 (`Snowflake`) 与 ID 门面 (`IdUtil`)

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

## 7. 全场景敏感数据脱敏体系 (`DesensitizeUtil` & `@FieldSensitive`)

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

## 8. 轻量原生加解密与哈希摘要 (`CryptoUtil` & `DigestUtil`)

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

## 9. 通用线性树形与森林装配工具 (`TreeUtil`)

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

## 10. 原生不可变元组标准件 (`Pair<L, R>` & `Triple<L, M, R>`)

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

## 11. 用户与多租户上下文及四维异步透传 (`UserContextHolder`, `TenantContextHolder` & `AsyncContext`)

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
//       keep-alive: 60s                               # 强类型 Duration 格式（默认 60s）
//       thread-name-prefix: "ateng-async-"
//       virtual-thread-name-prefix: "ateng-vt-async-" # 虚拟线程专属前缀
//       concurrency-limit: 64                         # 虚拟线程并发限制（防下游连接池击穿，默认 -1 无界）
//       await-termination: 60s                        # 优雅停机等待超时（默认 60s）
```

---

## 12. 未来中间件孵化通用原语套件

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
100% 原生 JDK 实现，支持动态异常谓词断言、指数退避因子、Equal Jitter 随机抖动打散、onRetry 回调监听与 Fallback 降级兜底，无需依赖 Spring Retry 或 Resilience4j 等重型 AOP 框架：
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

// 3. 启用 Equal Jitter 平滑随机抖动打散并发洪峰，并挂载 onRetry 度量监听器
RetryUtil.RetryConfig jitterConfig = RetryUtil.RetryConfig.builder()
        .maxAttempts(3)
        .delay(Duration.ofMillis(100))
        .multiplier(2.0)
        .jitter(true) // 计算公式：delay/2 + random(0, delay/2)
        .onRetry((attempt, ex) -> metricsCollector.recordRetry("remoteRpc", attempt, ex))
        .build();

// 4. 重试耗尽时触发 Fallback 降级回调返回兜底值（未配置 Fallback 则默认抛出根异常）
String fallbackResult = RetryUtil.execute(
        () -> thirdPartyApiClient.queryRate(),
        jitterConfig,
        ex -> "DEFAULT_RATE_1.0"
);

// 5. 无返回值任务的重试与降级回调
RetryUtil.executeAction(
        () -> notificationClient.pushMessage(msg),
        jitterConfig,
        ex -> deadLetterQueue.send(msg)
);
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

### 11. 事务生命周期同步门面 (`TransactionUtil`)

#### 场景痛点与特性
在业务开发中，持久化事务提交后常常需要触发外部副作用（如发布 MQ 消息、发送通知、写入审计日志或异步刷新缓存），而在事务回滚后需执行清理或补偿逻辑。
如果直接在事务代码块内执行异步调度或事件发布，极易引发**“事务尚未提交，外部消费者已读取到旧数据或脏读”**的严重并发时序缺陷；同时若回调任务抛出次生异常，可能逆向导致主事务异常回滚。
`TransactionUtil` 基于 Spring 事务同步器（`TransactionSynchronization`）提供显式门面：
- **双向生命周期完备性**：显式提供 `executeAfterCommit` 与 `executeAfterRollback` 对称钩子；
- **四维上下文无损穿透**：`executeAfterCommitAsync` 自动抓取当前线程四维上下文快照（MDC 链路、User 身份、Tenant 租户与 IgnoreTenant 标志位），在异步工作线程中还原并在任务完成后严格恢复，杜绝线程池污染与跨租户越权；
- **异常受控隔离**：回调任务发生的任何异常均被受控捕获并记录 WARN 日志，绝对不会逆向阻断外部事务提交或 HTTP 响应；
- **平滑自适应降级**：具备运行时类探测与非事务自适应能力，当处于非事务上下文或未引入 `spring-tx` 依赖时，`executeAfterCommit` 自动降级为就地同步执行，`executeAfterRollback` 安全跳过，`executeAfterCommitAsync` 就地提交线程池，实现 100% 宿主中立与环境弹性。

#### 核心代码范式
```java
import io.github.atengk.boot.core.util.TransactionUtil;

// 1. 事务提交后同步执行（例如清理本地二级缓存）
TransactionUtil.executeAfterCommit(() -> localCache.invalidate(userId));

// 2. 事务回滚后补偿清理（例如删除本地预生成的临时导出文件）
TransactionUtil.executeAfterRollback(() -> tempFile.delete());

// 3. 事务提交后异步调度（自动调度受管 taskExecutor 线程池，无损透传 MDC、User 与租户快照）
TransactionUtil.executeAfterCommitAsync(() -> mqProducer.sendOrderCreatedMessage(orderId));

// 4. 事务提交后异步调度（允许显式指定特定线程池）
TransactionUtil.executeAfterCommitAsync(() -> syncTask.run(), customExecutor);

// 5. 事务提交后通过 Spring 容器发布领域事件（支持 POJO 载荷事件）
TransactionUtil.publishAfterCommit(new OrderPaidEvent(orderId));

// 6. 事务与同步器状态判定
boolean hasActualTx = TransactionUtil.isActualTransactionActive();
boolean hasSync = TransactionUtil.isSynchronizationActive();
```

### 12. 操作审计标准契约 (`OperateLogModel` & `OperateTypeEnum`)

#### 场景痛点与特性
在全栈开发与分布式审计场景中，操作日志流转通常涉及 Web 切面拦截、MQ 消息异步削峰、领域事件分发以及持久层（MySQL / Elasticsearch / ClickHouse）消费落库。
若各子系统或分层自行定义散落的 Map 或不规范 POJO，极易导致字段命名割裂、操作动作歧义、Jackson 序列化 `@class` 漏洞污染以及缺失租户和全链路 TraceId 透传。
`ateng-boot-core` 下沉通用审计元数据标准契约：
- **标准枚举与多源安全解析**：`OperateTypeEnum` 集中收敛 11 种通用操作动作（`OTHER(0)`、`INSERT(1)`、`UPDATE(2)`、`DELETE(3)`、`QUERY(4)`、`EXPORT(5)`、`IMPORT(6)`、`LOGIN(7)`、`LOGOUT(8)`、`GRANT(9)`、`CLEAN(10)`），支持按 code 数值或名称大小写安全匹配，提供 `@JsonCreator` 兼容解析；
- **18 项全要素结构化元数据**：`OperateLogModel` 覆盖全链路追踪（`traceId`）、多租户（`tenantId`）、操作人（`userId`, `userName`）、功能模块与描述（`module`, `description`）、网络端点（`requestMethod`, `requestUrl`, `clientIp`, `operateLocation`）、出入参摘要（`requestParams`, `responseResult`）、执行度量（`status`, `costTime`, `errorMsg`）、扩展字典（`extra`）与时间戳（`operateTime`）；
- **语法糖与无失真序列化**：提供 `isSuccess()` 语法糖判定（`status == 0`，标配 `@JsonIgnore` 防派生字段污染），支持 `addExtra(k, v)` 便捷链式扩展，100% 适配 Jackson 规范序列化与未知属性容错。

#### 核心代码范式
```java
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.util.JsonUtil;

import java.time.LocalDateTime;

// 1. 构建结构化操作审计日志模型
OperateLogModel logModel = OperateLogModel.builder()
        .traceId("c8a14b0365734fb4864f7bdf753b890a")
        .tenantId("tenant-001")
        .userId("user-10001")
        .userName("张三")
        .module("用户管理")
        .operateType(OperateTypeEnum.INSERT)
        .description("新增系统用户")
        .requestMethod("POST")
        .requestUrl("/api/users")
        .clientIp("192.168.1.100")
        .operateLocation("中国北京北京")
        .requestParams("{\"username\":\"zhangsan\"}")
        .responseResult("{\"code\":200,\"msg\":\"操作成功\"}")
        .status(0)
        .costTime(28L)
        .operateTime(LocalDateTime.now())
        .build();

// 2. 便捷链式追加自定义扩展业务要素
logModel.addExtra("browser", "Chrome")
        .addExtra("os", "Windows 11");

// 3. 语法糖判定
if (logModel.isSuccess()) {
    log.info("操作成功执行");
}

// 4. 纯净序列化与反序列化（零 @class 污染，时间格式自动对齐）
String json = JsonUtil.toJsonString(logModel);
OperateLogModel parsed = JsonUtil.parseObject(json, OperateLogModel.class);
```

### 13. 原生日期时间互转与跨度计算门面 (`DateTimeUtil`)

#### 场景痛点与特性
Java 8 引入 JSR-310（`java.time`）后，企业老代码库或外部驱动（如 JDBC、旧版 SDK）仍大量充斥 `java.util.Date` 与毫秒时间戳。
开发者在 `LocalDateTime`、`LocalDate`、`Date` 与时间戳之间互转时，常因忽略时区（`ZoneId`）、纳秒与毫秒换算失真或未防 null 产生 NPE。
`DateTimeUtil` 坚持 100% 纯 JDK 17 原生封装，坚决杜绝 `joda-time` 或第三方全量包：
- **三向安全无损互转**：支持 `LocalDateTime`、`LocalDate`、`Date` 与毫秒时间戳多向自由互转，默认绑定系统时区，严格防范 null 崩溃；
- **天首天尾起止计算**：提供 `beginOfDay`（00:00:00.000000000）与 `endOfDay`（23:59:59.999999999），支持 `LocalDateTime`、`LocalDate` 与 `Date`，涵盖今日开箱即用快捷语法糖；
- **标准化格式化与解析**：内置 `DatePattern` 标准格式串（`yyyy-MM-dd HH:mm:ss` 与 `yyyy-MM-dd`），支持自定义 Pattern 与预编译 `DateTimeFormatter`；
- **多维度跨度计算**：提供 `betweenDays`、`betweenHours`、`betweenMinutes`、`betweenSeconds`、`betweenMillis`、`betweenDuration` 与 `betweenPeriod`，缺省入参防御保底返回 0。

#### 核心代码范式
```java
import io.github.atengk.boot.core.util.DateTimeUtil;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;

// 1. 无损互转（自动绑定系统默认时区）
Date date = DateTimeUtil.toDate(LocalDateTime.now());
LocalDateTime ldt = DateTimeUtil.toLocalDateTime(date);
Long epochMilli = DateTimeUtil.toEpochMilli(ldt);
LocalDate localDate = DateTimeUtil.toLocalDate(epochMilli);

// 2. 天首天尾起止
LocalDateTime dayStart = DateTimeUtil.beginOfDay(ldt); // 2026-10-18T00:00:00
LocalDateTime dayEnd = DateTimeUtil.endOfDay(ldt);     // 2026-10-18T23:59:59.999999999
Date todayStart = DateTimeUtil.beginOfTodayDate();

// 3. 标准格式化与解析
String str = DateTimeUtil.format(ldt); // "2026-10-18 20:15:30"
LocalDateTime parsed = DateTimeUtil.parseDateTime(str);

// 4. 时间跨度计算
long days = DateTimeUtil.betweenDays(ldt, ldt.plusDays(3));       // 3
long hours = DateTimeUtil.betweenHours(ldt, ldt.plusHours(12));   // 12
Duration duration = DateTimeUtil.betweenDuration(ldt, ldt.plusMinutes(90)); // PT1H30M
```

### 14. 高性能无锁安全随机标准件 (`RandomUtil`)

#### 场景痛点与特性
传统业务代码中频繁使用 `new Random()` 或 `Math.random()`，在高并发多线程争用下底层 CAS 种子自旋竞争导致吞吐断崖式下跌；而在需要定长短信验证码、指定区间闭合随机整数、随机抽样与洗牌时，容易写出边界越界、符号位溢出（如 `max == Integer.MAX_VALUE` 时加 1 溢出）或破坏原列表的脆弱代码。
`RandomUtil` 依托 `ThreadLocalRandom` 提供无锁并发高性能实现：
- **无锁验证码与随机串**：`randomNumbers(len)` 生成定长纯数字验证码（支持包含前导 0）；`randomString(len)` 生成数字大小写混合串；`randomStringLower(len)` / `randomStringUpper(len)` 生成指定格式随机串；
- **双向闭区间数值防溢出**：`randomInt(min, max)` 与 `randomLong(min, max)` 生成严格闭区间 `[min, max]` 数值，具备大小边界自动对调与 `Integer.MAX_VALUE` 防溢出保护；
- **防空抽样与无损洗牌**：`randomEle(list/array)` 单元素安全随机提取；`randomEles(list, count)` 无放回安全抽样（不破坏原列表）；`shuffle(list)` 就地打乱与 `shuffleNew(list)` 防变副本书写。

#### 核心代码范式
```java
import io.github.atengk.boot.core.util.RandomUtil;

import java.util.Arrays;
import java.util.List;

// 1. 生成 6 位纯数字短信验证码
String smsCode = RandomUtil.randomNumbers(6); // 如 "391845"

// 2. 生成 16 位混合随机密钥串
String secretKey = RandomUtil.randomString(16);

// 3. 闭区间 [10, 50] 随机整数（自动防溢出与边界对调）
int randomInt = RandomUtil.randomInt(10, 50);

// 4. 列表随机抽样（防 NPE 与空列表，无放回抽样）
List<String> users = Arrays.asList("Alice", "Bob", "Charlie", "David");
String winner = RandomUtil.randomEle(users);            // 随机抽取 1 人
List<String> luckyTwo = RandomUtil.randomEles(users, 2); // 随机抽取 2 人（不重复）

// 5. 列表无损洗牌打乱（返回新副本，不破坏原列表顺序）
List<String> shuffled = RandomUtil.shuffleNew(users);
```

---

## 13. 模块配置属性参考 (`application.yml`)

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
      # 空闲线程存活时间（强类型 Duration 格式，支持 60s, 1m 等，默认 60s；向后兼容 keep-alive-seconds: 60）
      keep-alive: 60s
      # 平台线程工作线程名前缀（默认 ateng-async-）
      thread-name-prefix: "ateng-async-"
      # 虚拟线程工作线程名前缀（默认 ateng-vt-async-，虚拟线程模式下生效）
      virtual-thread-name-prefix: "ateng-vt-async-"
      # 虚拟线程并发任务限制数（默认 -1 即无界；可按需配置限制防下游 HikariCP 连接池击穿）
      concurrency-limit: -1
      # 容器关闭时是否等待任务执行完成（优雅停机，默认 true）
      wait-for-tasks-to-complete-on-shutdown: true
      # 优雅停机等待超时时间（强类型 Duration 格式，支持 60s, 1m 等，默认 60s；向后兼容 await-termination-seconds: 60）
      await-termination: 60s
```
