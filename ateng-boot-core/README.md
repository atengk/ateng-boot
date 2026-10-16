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

## 6. 全场景敏感数据脱敏工具 (`DesensitizeUtil`)

### 场景痛点
涵盖国内常见的所有隐私合规字段，纯 JDK 正则/算法实现，毫秒级脱敏，严禁在业务层手工打星号引发脏数据写回。

### 核心代码范式
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

---

## 7. 轻量原生加解密与哈希摘要 (`CryptoUtil` & `DigestUtil`)

### 场景痛点
提供符合现代密码学安全规范的原生加解密封装，杜绝由于硬编码 IV 或 ECB 模式导致的重放攻击漏洞。

### 核心代码范式
```java
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.DigestUtil;

// ==================== 1. 加密与解密 (CryptoUtil) ====================
// AES-GCM (128/256-bit)：企业级推荐，动态附加随机 12-byte IV，认证加密防篡改
String secretKey = "MySecretKey123456"; // 16或32字符
String cipherText = CryptoUtil.aesEncrypt("明文密码123", secretKey);
String plainText = CryptoUtil.aesDecrypt(cipherText, secretKey);

// 国密 SM4-CBC 算法支持
String sm4Cipher = CryptoUtil.sm4Encrypt("机密凭证", "1234567812345678");
String sm4Plain = CryptoUtil.sm4Decrypt(sm4Cipher, "1234567812345678");

// ==================== 2. 哈希与摘要 (DigestUtil) ====================
String md5Hex = DigestUtil.md5Hex("raw_text");
String sha256Hex = DigestUtil.sha256Hex("raw_text");
String sm3Hex = DigestUtil.sm3Hex("raw_text"); // 国密 SM3
```

---

## 8. 通用线性树形组装工具 (`TreeUtil`)

### 场景痛点
菜单树、组织架构树、品类目录等递归组装是业务极高频操作，传统两层循环性能为 $O(N^2)$，容易发生栈溢出。`TreeUtil` 采用哈希辅助表实现 $O(N)$ 极速装配。

### 核心代码范式
```java
import io.github.atengk.boot.core.util.TreeUtil;

// 待组装的平铺扁平集合
List<MenuNode> flatList = menuService.listAll();

// 一行代码组装为树形嵌套结构
List<MenuNode> menuTree = TreeUtil.buildTree(
        flatList,
        0L,                        // 根节点父 ID 标识
        MenuNode::getId,           // 节点 ID 提取器
        MenuNode::getParentId,     // 父节点 ID 提取器
        MenuNode::getChildren,     // 子节点集合提取器
        MenuNode::setChildren      // 子节点集合设置器
);
```

---

## 9. 用户审计上下文与异步线程透传 (`UserContextHolder` & `AsyncContext`)

### 场景痛点
异步线程 `@Async` 或线程池执行任务时，主线程的登录用户上下文与链路追踪 MDC 会丢失。

### 核心代码范式
```java
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;

// 1. 设置当前线程的用户上下文
UserContextHolder.setUserId("10086");
String userId = UserContextHolder.getUserId();

// 2. 闭包式受控执行（执行完成后自动安全清理上下文）
UserContextHolder.runWithUser("10086", () -> {
    orderService.processOrder();
});

// 3. 跨线程池安全透传（由 ateng-boot-web 预装配线程池自动支持）
// 配合 ContextCopyingTaskDecorator 自动将 MDC traceId 与 UserContextHolder 复制到工作线程
```
