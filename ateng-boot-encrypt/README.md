# ateng-boot-encrypt 配置安全加解密套件

> 零臃肿纯原生 Spring Boot 3 生产级配置加解密底座：支持现代高强 AES-GCM / 纯原生国密 SM2/SM4 / 非对称 RSA、动态多 Key 轮换路由、Actuator 物理脱敏防泄露、生产环境三道防误伤门禁过滤、Spring Cloud `{cipher}` 受控兼容、配置中心两阶段预解密 Hook、整文件就地无损智能回写与脱机安全运维 CLI 工具箱。

---

## 1. 模块定位与技术基线

`ateng-boot-encrypt` 专职解决企业级应用中数据库连接密码、缓存凭据、第三方 API 令牌、证书私钥等敏感配置在代码库、YAML/Properties 配置文件及远端配置中心中的明文暴露风险。

### 核心设计原则
- **零臃肿与底座收敛 (Zero-Bloat & Core-First)**：100% 依托 JDK 17+ 标准库与 Spring 6 原生能力，国密 SM2/SM3/SM4 纯算法内聚实现，**坚决不引入 BouncyCastle、Jasypt 等重型第三方传递依赖**；
- **透明无感 (Transparent & Ergonomic)**：基于 Spring Boot `EnvironmentPostProcessor` 与 `ConfigData` 机制，在配置加载阶段透明懒解密，业务代码、`@ConfigurationProperties` 与 `@Value` 完全无感；
- **现代安全标配 (Modern Cryptography Defaults)**：默认采用具备 AEAD（Authenticated Encryption with Associated Data）完整性认证防篡改的 **AES-256-GCM** 算法，自动附带 12 字节安全随机 IV，彻底抛弃过时且不安全的 ECB/DES 算法；
- **多 Key 轮换与隔离 (Multi-Key Routing & Isolation)**：独创 `ENC[keyId](...)` 语义契约，天然支持多中间件独立密钥隔离、`KeyRotatedEvent` 领域事件通知与无停机密钥平滑轮换；
- **三道防误伤门禁 (Defense-in-Depth Ingress Guard)**：生产环境开启 `enforce-encrypted: true` 时，自动启动协议路径免疫（`http://`, `https://`, `classpath:`, `/`）、日志级别免疫（`DEBUG`, `INFO` 等）与显式白名单机制（`ignored-keys`），在坚决阻断裸明文密码的同时杜绝误报；
- **全生命周期安全闭环 (Full Lifecycle Governance)**：涵盖开发期脱机加解密 CLI、CI/CD 阶段整文件就地回写、配置中心拉取前预解密 Hook、以及运行期 Actuator 监控物理脱敏。

### Maven 坐标
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-encrypt</artifactId>
</dependency>
```

---

## 2. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.encrypt.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **透明配置解密** | 运行时环境配置属性透明懒解密 | `EncryptPropertySourceWrapper`, `EncryptEnvironmentPostProcessor` | `enabled`, `password`, `algorithm`, `prefix`, `suffix`, `fail-on-error` | 拦截全量 `PropertySource`，识别 `ENC(...)` 密文并原子缓存解密结果；支持快速失败与容错逃生舱 | `ConfigDecryptionScenarioService.java`, `EncryptPropertySourceWrapperTest.java` |
| **受管编程式门面** | Spring 单例受管加解密综合门面 | `EncryptTemplate` | `algorithm`, `prefix`, `suffix` | `encrypt(plain)`, `decrypt(token)`, `encryptRaw(plain)`, `decryptOrOriginal(cipher)`, `decryptOrDefault(cipher, def)` | `ProgrammaticCryptoScenarioService.java`, `EncryptTemplateTest.java` |
| **底层对称门面** | 纯原生静态加解密执行门面 | `ConfigEncryptor` | - | `encrypt(plain, pass, algo)`, `decrypt(cipher, pass, algo)`，对称支持 AES-GCM, AES-CBC, SM4-CBC, SM4-ECB, RSA, SM2 | `ProgrammaticCryptoScenarioService.java`, `ConfigEncryptorTest.java` |
| **多 Key 动态路由** | 多命名密钥隔离与平滑轮换机制 | `MultiKeyRegistry`, `KeyRotatedEvent` | `keys.<key-id>` | `registerKey(keyId, pass)`, `getKeyOrDefault(keyId)`, `addChangeListener(listener)`；支持 `ENC[keyId](...)` 标签定向路由 | `MultiKeyRoutingScenarioService.java`, `MultiKeyRegistryTest.java` |
| **纯原生国密 SM4** | 零外部依赖纯原生国密对称加解密 | `Sm4ConfigDecryptor`, `ConfigDecryptor` | `algorithm=SM4-CBC` 或 `SM4-ECB` | `encryptCbc(plain, pass)`, `decrypt(cipher, pass)`，标配 16 字节随机 IV 与 PKCS5Padding | `Sm4ConfigDecryptorTest.java`, `EncryptSampleApplicationTest.java` |
| **纯原生国密 SM2** | 国标 GM/T 0003-2012 椭圆曲线非对称 | `Sm2ConfigDecryptor`, `Sm2Engine` | `algorithm=SM2` | `generateKeyPair()`, `encrypt(plain, pubHex)`, `decrypt(cipher, priHex)`, `c1c3c2ToDer(c1c3c2)`, `derToC1c3c2(der)` | `Sm2ConfigDecryptorTest.java`, `Sm2EngineTest.java` |
| **标准 RSA 非对称** | 2048/4096 位 RSA 密钥分域与 PEM 互通 | `RsaConfigDecryptor`, `ConfigDecryptor` | `algorithm=RSA` | `generateKeyPair(size)`, `encodePublicKeyToPem(pub)`, `encodePrivateKeyToPem(pri)`, `encrypt(plain, pemPub)`, `decrypt(cipher, pemPri)` | `RsaConfigDecryptorTest.java`, `EncryptCliTest.java` |
| **密文语法解析** | 令牌正则提取与 Spring Cloud 兼容 | `CiphertextParser`, `ParsedToken` | `prefix`, `suffix`, `compatible-cipher-prefix` | `hasEncrypted(text)`, `replaceTokens(text, fn)`, `formatToken(keyId, algo, cipher)`；受控兼容 `{cipher}...` 历史前缀 | `CiphertextParserTest.java`, `EncryptPropertySourceWrapperTest.java` |
| **生产防误伤门禁** | 生产强制加密与三道防误伤过滤链 | `EnforceEncryptionPolicy`, `PlaintextIngressGuard` | `enforce-encrypted`, `sensitive-key-pattern`, `ignored-keys` | 协议路径特征放行（`http://`, `https://`, `classpath:`, `/`）、日志级别放行（`DEBUG` 等）、白名单硬放行；裸明文敏感属性刚性拦截 | `EnforceEncryptionPolicyTest.java`, `EncryptSampleApplicationTest.java` |
| **配置热更新防御** | 配置中心热推送防倒灌与缓存置换 | `PlaintextIngressGuard` | `enforce-encrypted` | 监听 `EnvironmentChangeEvent` / `RefreshEvent`，原子置换清空解密缓存，拦截动态推送的裸明文敏感配置 | `IngressGuardScenarioService.java`, `PlaintextIngressGuardTest.java` |
| **外部 KMS SPI** | 外部云 KMS / HSM 抽象解密扩展基类 | `AbstractKmsConfigDecryptor`, `ConfigDecryptor` | `algorithm=KMS` | 继承并实现 `doDecrypt(ciphertext, keyIdOrPassword)`，无缝对接 AWS、阿里云、腾讯云 KMS 及硬件加密机 | `AbstractKmsConfigDecryptorTest.java` |
| **强类型弹性重试** | 远程云端解密指数退避重试装饰器 | `RetryingConfigDecryptor`, `EncryptRetryProperties` | `retry.delay`, `retry.max-delay`, `retry.multiplier`, `retry.jitter` | 装饰 `ConfigDecryptor`，基于 `Duration` 强类型参数进行退避重试，自动排除密码学错误快速失败 | `RetryingConfigDecryptorTest.java` |
| **Actuator 物理脱敏** | 监控端点物理掩码脱敏防泄露机制 | `SanitizingGuard` | - | 实现 Actuator `SanitizingFunction`，对 `/actuator/env` 等端点中包含密文或匹配敏感特征的属性物理掩码为 `******` | `EncryptSampleApplicationTest.java`, `SanitizingGuardTest.java` |
| **就地双模智能回写** | YAML/Properties 注释与格式保真重写 | `InPlaceRewriter`, `# @encrypt` 指令 | - | `rewriteEncrypt(file)`, `rewriteDecrypt(file)`，支持多文档 `---`、块标量 `\|`、占位符 `${...}` 免疫，自动生成 `.bak` 备份 | `InPlaceRewriteScenarioService.java`, `InPlaceRewriterTest.java` |
| **脱机安全运维 CLI** | 终端脱机免 Spring 安全工具箱 | `EncryptCli` | - | `EncryptCli.main(args)`，支持命令行批处理（`--encrypt`, `--decrypt`, `--keygen`）与交互式控制台菜单 | `EncryptCliTest.java` |
| **多行大文本规范** | PEM 证书与多行私钥单行化互转 | `MultilineSecretFormatter` | - | `toSingleLine(pem)`, `toMultiLine(singleLinePem)`, `cleanCiphertext(text)`，清洗多行折行空白，保证 Base64 连续性 | `MultilineSecretFormatterTest.java` |
| **配置中心预解密 Hook** | 远端拉取前凭据预解密以防止 403 死锁 | `EncryptConfigDataLocationResolver` | - | 在 Spring Boot 3 `ConfigDataLocationResolver` 极早期穿透已加载配置，优先解密 Nacos/Apollo 用户名密码 | `EncryptConfigDataLocationResolverTest.java` |
| **密钥探测与防护** | 本地主密钥探测与自加密风险拦截 | `KeyDetector` | `password`, `keys.*` | 级联探测环境变量与系统属性；刚性拦截在本地配置源直接暴露主密钥的自加密高危行为 | `KeyDetectorTest.java` |

---

## 3. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 3.1 一句话痛点
配置文件中的明文密码（数据库密码、Redis Token 等）一旦提交至 Git 仓库或明文推送到配置中心，将造成严重的审计不合规与越权风险；而传统加密插件依赖重、启动慢、且容易因配置中心握手时序问题引发 403 认证死锁。`ateng-boot-encrypt` 纯原生零外部依赖，引入即生效，透明完成加解密治理。

### 3.2 最小可用配置 (`application.yml`)
提取自样例工程 `ateng-boot-samples/ateng-boot-sample-encrypt/src/main/resources/application.yml`：
```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-encrypt

# ===================================================================
# ateng-boot 配置加解密底座核心配置
# ===================================================================
ateng:
  boot:
    encrypt:
      enabled: true
      algorithm: AES-GCM # 全局默认高强 AEAD 对称加密算法
      # 命名密钥映射表（KeyId -> Password/PrivateKey），用于多 Key 轮换与分域解密
      keys:
        redis-cluster: RedisSpecialClusterKey2026!

# ===================================================================
# 业务机密属性（配置已加密的密文，由 Starter 运行时透明懒解密还原）
# 注意：全局主解密密钥严禁硬编码在本地 application.yml 中，统一通过环境变量或启动参数注入！
# ===================================================================
sample:
  secret:
    # 数据库密码：采用默认 AES-GCM 算法加密
    database-password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==) # 数据库生产连接密码
    # 缓存密码：指定多 Key 路由 [redis-cluster] 执行解密
    redis-password: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==) # Redis集群访问密码
    # 第三方API令牌：显式指定国密 SM4-CBC 算法加解密
    third-party-token: ENC(SM4-CBC@ODPVE+KjssNqGN6yrBqMlXcYvDKQgNYADkGV+HV6vL+/H/Juo4KFpgIuVeRMmIT+) # 支付网关安全令牌
```

> [!IMPORTANT]
> **主密钥注入规范**：严禁将解密主密钥（`ateng.boot.encrypt.password`）写在本地 `application.yml` 中（Starter 将直接抛出 `ConfigSecurityException` 拦截自加密）。请统一通过环境变量或启动参数动态注入：
> ```bash
> # 生产标准推荐：环境变量
> export ATENG_BOOT_ENCRYPT_PASSWORD="SampleMasterSecretKey2026!"
> 
> # 或 JVM 启动参数
> java -Dateng.boot.encrypt.password=SampleMasterSecretKey2026! -jar app.jar
> ```

### 3.3 业务代码透明无感使用
业务代码完全无需感知密文，直接通过 `@ConfigurationProperties` 或 `@Value` 注入真实明文（提取自 `ateng-boot-sample-encrypt`）：

```java
package io.github.atengk.boot.sample.encrypt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "sample.secret")
public class SampleSecretProperties {

    /**
     * 生产数据库连接密码（AES-GCM 默认算法加密）
     */
    private String databasePassword;

    /**
     * 生产 Redis 缓存密码（多 Key 别名 ENC[redis-cluster](...) 加密）
     */
    private String redisPassword;

    /**
     * 第三方支付 API 令牌（国密 SM4-CBC 模式加密）
     */
    private String thirdPartyToken;
}
```

专职场景服务获取解密后的真实明文：
```java
package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Getter
public class ConfigDecryptionScenarioService {

    private final SampleSecretProperties secretProperties;

    @Value("${sample.secret.database-password}")
    private String databasePasswordFromValue;

    public ConfigDecryptionScenarioService(SampleSecretProperties secretProperties) {
        this.secretProperties = secretProperties;
    }

    public String getDatabasePasswordFromProperties() {
        return secretProperties.getDatabasePassword();
    }
}
```

端到端集成实测强断言（提取自 `EncryptSampleApplicationTest.java`）：
```java
@Test
@DisplayName("场景 1 实测: ConfigDecryptionScenarioService 配置属性与 @Value 透明懒解密")
void testScenario1_ConfigDecryption() {
    // 1. 验证默认 AES-GCM 算法透明解密
    assertEquals("mysqlRootPass2026!", configDecryptionScenarioService.getDatabasePasswordFromProperties());

    // 2. 验证多 Key 别名路由 [redis-cluster] 透明解密
    assertEquals("redisAuthPass2026!", configDecryptionScenarioService.getRedisPasswordFromProperties());

    // 3. 验证显式国密 SM4-CBC 算法透明解密
    assertEquals("sm4ApiSecretToken888", configDecryptionScenarioService.getThirdPartyTokenFromProperties());

    // 4. 验证 @Value 与 @ConfigurationProperties 注入值完全自洽
    assertTrue(configDecryptionScenarioService.verifyDecryptionConsistency());
}
```

---

## 4. 维度二：生产扩展接管 (Customization & Extensibility)

### 4.1 扩展外部云 KMS / 硬件加密机 SPI
当需要对接阿里云 KMS、腾讯云 KMS、AWS KMS 或专用加密机时，直接继承 `AbstractKmsConfigDecryptor` 或实现 `ConfigDecryptor` SPI：

```java
package io.github.atengk.boot.sample.encrypt.extension;

import io.github.atengk.boot.encrypt.decryptor.AbstractKmsConfigDecryptor;
import org.springframework.stereotype.Component;

/**
 * 企业外部云 KMS 解密器扩展实现
 */
@Component
public class CustomAliyunKmsConfigDecryptor extends AbstractKmsConfigDecryptor {

    @Override
    protected String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception {
        // 调用企业云 KMS 客户端解密接口
        return aliyunKmsClient.decrypt(ciphertext, keyIdOrPassword);
    }
}
```

### 4.2 强类型退避重试配置与云端容灾解耦 (`RetryingConfigDecryptor`)
针对外部 KMS 或网络解密服务的瞬态抖动，Starter 提供了基于 Core `RetryUtil` 的指数退避重试装饰器，支持强类型 `Duration` 配置：

```yaml
ateng:
  boot:
    encrypt:
      retry:
        delay: 100ms         # 初始退避延迟 (java.time.Duration)
        max-delay: 5s        # 最大退避延迟 (java.time.Duration)
        multiplier: 2.0      # 指数退避乘数
        jitter: true         # 启用 Full Jitter 打散重试洪峰
```

编程式挂载弹性装饰器：
```java
ConfigDecryptor kmsDecryptor = new CustomAliyunKmsConfigDecryptor();

ConfigDecryptor resilientDecryptor = new RetryingConfigDecryptor(
        kmsDecryptor,
        RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(100))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(true)
                .retryPredicate(RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE)
                .build()
);
```

> [!TIP]
> `RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE` 具备密码学异常短路机制：遇 `BadPaddingException`、`AEADBadTagException`、`InvalidKeyException` 等密文/密钥损坏错误时，立即快速失败并抛出异常，绝不盲目进行无意义重试。

### 4.3 动态密钥轮换与事件总线 (`MultiKeyRegistry`)
通过 `MultiKeyRegistry` 注册或更新命名密钥，系统自动清空存量解密缓存并发布 `KeyRotatedEvent` 领域事件：

```java
@Autowired
private MultiKeyRoutingScenarioService multiKeyService;

// 动态轮换并生效新密钥
multiKeyService.rotateKey("payment-shard-key", "NewPaymentShardPassword2026!");

// 监听密钥轮换事件
@EventListener
public void onKeyRotated(KeyRotatedEvent event) {
    log.info("检测到密钥轮换事件: keyId={}, timestamp={}", event.getKeyId(), event.getTimestamp());
}
```

### 4.4 Spring 受管 `EncryptTemplate` 编程式门面
注入 `EncryptTemplate` 即可进行多算法编程式加密、解密、裸密文生成与容错降级（提取自 `ProgrammaticCryptoScenarioService.java`）：

```java
@Autowired
private ProgrammaticCryptoScenarioService cryptoService;

// 1. 使用环境默认算法加密，生成 ENC(...) 标记
String token = cryptoService.encryptDefault("MySuperSecretDatabasePassword999");

// 2. 解密还原
String plain = cryptoService.decrypt(token);

// 3. 指定算法与命名 Key 生成 ENC[redis-cluster](SM4-CBC@...) 标记
String sm4Token = cryptoService.encryptWithKeyAndAlgorithm("MySecret", "redis-cluster", EncryptAlgorithm.SM4_CBC);

// 4. 容错逃生舱解密（解密失败返回保底默认值，不中断业务流程）
String safeValue = cryptoService.decryptOrDefault("corrupted_token", "defaultFallback");
```

---

## 5. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 5.1 并发模型与缓存原子置换
- **懒解密与线程安全**：`EncryptPropertySourceWrapper` 采用 `AtomicReference<ConcurrentHashMap<String, Object>>` 维护解密缓存，多线程并发读取配置时仅在首次访问按需触发解密计算并存入缓存；
- **配置刷新原子清空**：当配置中心推送变更（`EnvironmentChangeEvent` / `RefreshEvent`）或调用 `MultiKeyRegistry.registerKey` 轮换密钥时，自动原子置换全新的缓存实例，确保并发读线程既无锁竞争又绝对不会脏读已失效的旧明文。

### 5.2 生产强制门禁与三道防误伤过滤链 (`EnforceEncryptionPolicy`)
当开启 `ateng.boot.encrypt.enforce-encrypted: true` 时，系统在启动前置扫描外部属性源；为防止正常配置误判，内置三道过滤防线：
1. **协议与路径特征豁免**：属性值以 `http://`、`https://`、`classpath:`、`/` 开头（如 OAuth2 Token URI、SSL 私钥证书路径）自动豁免；
2. **日志级别特征豁免**：属性值为标准日志级别（`DEBUG`、`INFO`、`WARN`、`ERROR`、`TRACE`、`FATAL`、`OFF`）时自动放行（即使键名包含 `.secret.` 或 `.token.`）；
3. **显式与框架硬白名单**：`ateng.boot.encrypt.*` 属性硬编码白名单放行；配置项 `ateng.boot.encrypt.ignored-keys` 显式声明的属性键名跳过强制校验；
4. **刚性拦截**：真实的未加密敏感属性（如 `spring.datasource.password=123456`）依然触发 `ConfigSecurityException` 刚性熔断，阻止应用启动。

### 5.3 Spring Cloud Config `{cipher}` 受控兼容
为方便由 Spring Cloud Config 历史工程平滑迁移：
- 配置项 `ateng.boot.encrypt.compatible-cipher-prefix`（默认 `false`）；
- 开启后，`CiphertextParser` 自动识别 `{cipher}...` 前缀并剥离后委托解密；关闭时 `{cipher}...` 视作普通文本原样保真输出。

### 5.4 配置中心极早期穿透守卫 (`EncryptConfigDataLocationResolver`)
在 Spring Boot 3 的 `ConfigDataLocationResolver` 极早期阶段（`ORDER = HIGHEST_PRECEDENCE`），Nacos / Apollo 客户端发起网络连接前，守卫通过轻量反射穿透已加载的贡献源（Contributors），优先解密 `spring.cloud.nacos.config.password` 等连接凭据，彻底根除 403 认证死锁。

### 5.5 就地回写双模引擎格式保护 (`InPlaceRewriter`)
- **双模扩展**：同时支持 `.yml` / `.yaml` 与 `.properties` 文件的就地无损重写；
- **排版绝对保真**：保留源文件的原始注释、首尾空行、行内缩进及宿主换行符（CRLF/LF）；
- **高级语法保护**：自动免疫多文档分割线 `---`、块标量 `|` / `>`、以及 Spring 占位符 `${...}`，杜绝占位符被误加密；
- **回滚保障**：重写前强制在同级目录生成 `.bak` 备份文件，确保运维操作 100% 可逆。

### 5.6 Actuator 监控端点物理脱敏 (`SanitizingGuard`)
基于 Spring Boot Actuator 的 `SanitizingFunction` 规范：
- 对曾带有密文标记或命中敏感模式的属性在 `/actuator/env` 等监控端点统一物理脱敏为 `******`；
- **内存真明文原则**：业务代码在内存中始终保持 100% 原始真实明文，只在向外暴露端点处执行掩码脱敏，杜绝脏数据写回。

---

## 6. 维度四：脱机安全运维 CLI 与实测验证 (Evergreen & Zero-Glitch)

### 6.1 脱机安全运维终端 CLI 工具箱 (`EncryptCli`)
纯原生 JDK 实现，内置脱机终端控制台与批处理命令，无需依赖 Spring 容器即可完成生产密钥生成与密文运算：

#### 交互式菜单模式
```bash
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli
```
控制台菜单：
```text
=================================================================
*              ateng-boot-encrypt 终端脱机安全工具箱             *
=================================================================
[1] 单值快速加密 (AES-GCM / SM4 / RSA / SM2)
[2] 单值快速解密
[3] 一键生成非对称 RSA 密钥对 (2048 / 4096 位)
[4] 一键生成纯原生国密 SM2 密钥对
[5] 退出工具箱
-----------------------------------------------------------------
请选择操作编号 [1-5]: 
```

#### 命令行批处理模式（适配 CI/CD 自动化流水线）
```bash
# 1. 快速单值加密（AES-GCM）
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
  --encrypt -v "mysqlRootPass2026!" -p "SampleMasterSecretKey2026!"
# 输出: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)

# 2. 指定多 Key 别名与算法加密
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
  --encrypt -v "redisAuthPass2026!" -p "RedisSpecialClusterKey2026!" -a AES-GCM --key-id redis-cluster
# 输出: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)

# 3. 快速解密还原
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
  --decrypt -v "ENC(...)" -p "SampleMasterSecretKey2026!"

# 4. 一键生成非对称密钥对
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli --keygen -a RSA --key-size 2048
java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli --keygen -a SM2
```

### 6.2 多行大文本与 PEM 证书单行化编解码 (`MultilineSecretFormatter`)
```java
// 多行 PEM 证书转单行（安全转义换行符并保留头尾）
String singleLine = MultilineSecretFormatter.toSingleLine(pemPrivateKey);

// 单行字符串还原为标准 RFC 规范的多行 PEM 证书
String standardPem = MultilineSecretFormatter.toMultiLine(singleLine);
```

### 6.3 样例工程场景实测真实执行结果
真实端到端集成测试输出（截取自 `mvn test -pl ateng-boot-encrypt,ateng-boot-samples/ateng-boot-sample-encrypt`）：
```text
[INFO] Running io.github.atengk.boot.sample.encrypt.EncryptSampleApplicationTest
2026-11-01 10:00:31.429 INFO  [           main] [NO-TRACE] i.g.a.b.s.e.EncryptSampleApplicationTest : Starting EncryptSampleApplicationTest using Java 21.0.11 with PID 29176
2026-11-01 10:00:33.360 INFO  [           main] [NO-TRACE] i.g.a.b.s.e.EncryptSampleApplicationTest : Started EncryptSampleApplicationTest in 2.641 seconds
2026-11-01 10:00:34.508 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 正在执行就地加密，已生成源文件备份: sample-app.yml.bak
2026-11-01 10:00:34.520 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 就地加密回写完成，共加密 1 个敏感字段
2026-11-01 10:00:34.523 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 正在执行就地解密还原，已生成源文件备份: sample-app.yml.bak
2026-11-01 10:00:34.525 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 就地解密还原完成，共还原 1 行密文字段
2026-11-01 10:00:34.529 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 正在执行就地加密，已生成源文件备份: sample-app.properties.bak
2026-11-01 10:00:34.530 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 就地加密回写完成，共加密 1 个敏感字段
2026-11-01 10:00:34.532 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 正在执行就地解密还原，已生成源文件备份: sample-app.properties.bak
2026-11-01 10:00:34.535 INFO  [           main] [NO-TRACE] i.g.a.boot.encrypt.tool.InPlaceRewriter  : [ateng-boot-encrypt] 就地解密还原完成，共还原 1 行密文字段
2026-11-01 10:00:34.557 INFO  [           main] [NO-TRACE] i.g.a.b.e.guard.PlaintextIngressGuard    : [ateng-boot-encrypt] 已原子置换与更新 8 个属性源解密缓存
2026-11-01 10:00:34.635 ERROR [           main] [NO-TRACE] o.s.boot.SpringApplication               : Application run failed
io.github.atengk.boot.encrypt.exception.ConfigSecurityException: [ateng-boot-encrypt] 安全阻断：检测到在本地配置源 'defaultProperties' 中直接声明了主密钥 'ateng.boot.encrypt.password'！主密钥与密文共存于同一配置文件将导致加密机制形同虚设。请立即从配置文件中移除该密钥，并统一改为通过环境变量 (ATENG_BOOT_ENCRYPT_PASSWORD)、JVM 启动参数 (-Dateng.boot.encrypt.password) 或容器 Secret 注入！
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.727 s -- in io.github.atengk.boot.sample.encrypt.EncryptSampleApplicationTest
[INFO] Results:
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
