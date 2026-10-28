# ateng-boot-sample-core 核心底座原语场景自验工程

> 专一 `ateng-boot-core` 核心通用底座原语自验：四维上下文透传、敏感数据动态脱敏、原生加解密、树形装配、退避重试与分布式发号器。

---

## 1. 场景痛点与功能定位

`ateng-boot-core` 是整个框架的公共底座，坚守零重型外部依赖与纯原生标准库原则。`ateng-boot-sample-core` 专注于轻量、纯粹的底层算法与中间件原语自验：
- **纯内存零依赖（Zero Web & Zero DB Dependency）**：无需任何网络端口、Servlet 容器与关系数据库，`spring.main.web-application-type: none` 极速自洽运行；
- **四维异步上下文传播**：验证 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 跨受管线程池流转与工作现场安全还原；
- **全场景脱敏与“内存真明文”**：验证 12 大类敏感信息动态掩码，保证 JVM 内存中实体保持 100% 原始明文；
- **纯原生加解密与摘要**：AES/GCM 认证加解密、AES/CBC 对称加密与 SHA-256/MD5 摘要；
- **通用数据处理与工具标准件**：JsonUtil 门面、CollectionUtil 分批切片、TreeUtil 线性装配与剪枝、ValidatorUtil 校验；
- **弹性与因果体系**：RetryUtil 指数退避与平滑随机抖动、2026 纪元雪花发号器与 BusinessException 双模堆栈治理。

---

## 2. 场景服务架构与代码示例

工程内聚四大领域场景服务，与核心底座特性 100% 对齐：

| 场景服务 | 覆盖核心门面与特性 | 对应端到端测试 |
| :--- | :--- | :--- |
| `CoreContextScenarioService` | `UserContextHolder`, `TenantContextHolder`, `AsyncContext`, `ContextCopyingTaskDecorator`, `NamedThreadFactory`, `TransactionUtil` | `CoreContextScenarioServiceTest` |
| `CoreSecurityScenarioService` | `DesensitizeUtil`, `@FieldSensitive`, `SensitiveJsonSerializer`, `CryptoUtil`, `DigestUtil` | `CoreSecurityScenarioServiceTest` |
| `CoreDataProcessingScenarioService` | `JsonUtil`, `CollectionUtil`, `MoreCollectors`, `TreeUtil`, `ValidatorUtil`, `ValidList`, `BeanUtil`, `LambdaUtil`, `FileUtil`, `DateTimeUtil`, `RandomUtil` | `CoreDataProcessingScenarioServiceTest` |
| `CoreResilienceScenarioService` | `RetryUtil`, `IdUtil`, `BusinessAssert`, `BusinessException`, `Result<T>`, `PageQuery`, `PageResult<T>`, `OperateLogModel`, `SpringUtil` | `CoreResilienceScenarioServiceTest` |

### 核心代码片段示例

#### 1. 敏感数据脱敏与内存真明文校验
```java
// 实体字段标注动态脱敏注解
public class SampleUserDTO {
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone; // "13812345678"
}

// 序列化输出自动掩码，内存对象严守真明文
Pair<String, SampleUserDTO> pair = securityScenarioService.verifyInMemoryPlaintextAndJsonMasking();
// JSON 输出: {"phone":"138****5678", ...}
// 内存真明文: user.getPhone() -> "13812345678"
```

#### 2. 指数退避重试与 Fallback 降级
```java
RetryUtil.RetryConfig config = RetryUtil.RetryConfig.builder()
        .maxAttempts(3)
        .delay(Duration.ofMillis(20))
        .multiplier(1.5)
        .jitter(true)
        .retryPredicate(ex -> ex instanceof IllegalStateException)
        .build();

String result = RetryUtil.execute(() -> remoteRpcCall(), config, ex -> "FALLBACK_VALUE");
```

---

## 3. 定向集成测试准出

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-core -am -T 1C -nsu -B
```

测试全自洽、零外部依赖，毫秒级极速执行通过：
```
[INFO] Results:
[INFO] 
[INFO] Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
