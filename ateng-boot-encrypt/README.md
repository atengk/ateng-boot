# ateng-boot-encrypt 配置安全加解密套件

> 零臃肿纯原生 Spring Boot 3 生产级配置加解密底座：支持现代高强 AES-GCM / 纯原生国密 SM2/SM4 / 非对称 RSA、动态多 Key 轮换路由、Actuator 物理脱敏防泄露、生产环境三道防误伤门禁过滤、Spring Cloud `{cipher}` 受控兼容、配置中心两阶段预解密 Hook、整文件就地无损智能回写与脱机安全运维 CLI 工具箱。

---

## 0. 特性与公共 API 全景速查矩阵 (维度零：100% 全覆盖)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.encrypt.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **透明配置解密** | 运行时环境配置属性透明懒解密与两阶段同步 | `EncryptPropertySourceWrapper`, `EncryptEnvironmentPostProcessor`, `EncryptEnvironmentSupport` | `enabled`, `password`, `algorithm`, `prefix`, `suffix`, `fail-on-error` | `getProperty(name)`, `updateRuntimeConfig(...)`, `clearCache()`, `isEncryptedProperty(name)`；支持 `enabled=false` 早阶短路、`fail-on-error` 全链路贯通及 `ReentrantLock` 无 Pinning 并发缓存 | `EncryptDecryptionController.java`, `EncryptPropertySourceWrapperTest.java`, `EncryptEnvironmentPostProcessorTest.java` |
| **配置中心预解密 Hook** | ConfigData 极早期瞬态预解析与属性源包装 | `EncryptConfigDataLocationResolver`, `EncryptConfigDataEnvironmentUpdateListener` | `enabled`, `algorithm`, `fail-on-error`, `prefix`, `suffix`, `compatible-cipher-prefix` | `penetrateAndDecorate(context)`, `onPropertySourceAdded(...)`, `wrapPropertySource(source)`；在 Nacos/Apollo 拉取前优先解密连接凭据并防 `enabled=false` 短路 | `EncryptConfigDataLocationResolverTest.java` |
| **容器期自动装配桥接** | 容器组件反向同步与核心 Bean 装配 | `AtengEncryptAutoConfiguration` | `enabled`, `retry.*` | 注册 `EncryptTemplate` / `MultiKeyRegistry` / `CiphertextParser` / `PlaintextIngressGuard` / `SanitizingGuard`，并在容器初始化期同步 `ConfigDecryptor` 与 `EncryptRetryProperties` 至属性源包装器 | `AtengEncryptAutoConfigurationTest.java`, `EncryptSampleApplicationTest.java` |
| **受管编程式门面** | Spring 单例受管加解密综合门面 | `EncryptTemplate` | `algorithm`, `prefix`, `suffix`, `keys.*` | `encrypt(plain)`, `encrypt(plain, keyId)`, `encrypt(plain, keyId, algo)`, `encryptRaw(plain)`, `encryptRaw(plain, keyId)`, `encryptRaw(plain, keyId, algo)`, `decrypt(cipher)`, `decrypt(cipher, keyId)`, `decrypt(cipher, keyId, algo)`, `decryptOrOriginal(cipher)`, `decryptOrDefault(cipher, def)`, `isEncrypted(text)`, `formatToken(keyId, algo, cipher)`, `findDecryptor(algo)` | `EncryptCryptoController.java`, `ProgrammaticCryptoScenarioService.java`, `EncryptTemplateTest.java` |
| **底层对称门面** | 纯原生静态加解密执行门面 | `ConfigEncryptor` | - | `encrypt(plain, pass)`, `encrypt(plain, pass, algo)`, `decrypt(cipher, pass)`, `decrypt(cipher, pass, algo)`，对称支持 `AES-GCM`, `AES-CBC`, `SM4-CBC`, `SM4-ECB`, `RSA`, `SM2` | `ProgrammaticCryptoScenarioService.java`, `ConfigEncryptorTest.java` |
| **多 Key 动态路由** | 多命名密钥隔离与平滑轮换机制 | `MultiKeyRegistry`, `KeyRotatedEvent` | `keys.<key-id>` | `registerKey(keyId, pass)`, `unregisterKey(keyId)`, `getKey(keyId)`, `getKeyOrDefault(keyId)`, `rotateDefaultKey(pass)`, `addChangeListener(listener)`, `fromProperties(props)`, `fromEnvironment(env)`；支持 `ENC[keyId](...)` 标签路由 | `EncryptMultiKeyController.java`, `MultiKeyRoutingScenarioService.java`, `MultiKeyRegistryTest.java` |
| **纯原生对称算法** | AES-256-GCM AEAD 与 AES-CBC 解密引擎 | `AesGcmConfigDecryptor`, `AesCbcConfigDecryptor`, `EncryptAlgorithm` | `algorithm=AES-GCM` 或 `AES-CBC` | `encrypt(plain, pass)`, `decrypt(cipher, pass)`，`EncryptAlgorithm.fromCode(code)`；标配 12 字节随机 IV 与 128 位防篡改认证 Tag | `AesGcmConfigDecryptorTest.java`, `AesCbcConfigDecryptorTest.java` |
| **纯原生国密 SM4** | 零外部依赖纯原生国密对称加解密 | `Sm4ConfigDecryptor`, `ConfigDecryptor` | `algorithm=SM4-CBC` 或 `SM4-ECB` | `encryptCbc(plain, pass)`, `encryptEcb(plain, pass)`, `decrypt(cipher, pass)`，标配 16 字节随机 IV 与 PKCS5Padding | `Sm4ConfigDecryptorTest.java`, `SampleEncryptWebControllerTest.java` |
| **纯原生国密 SM2/SM3** | 国标 GM/T 椭圆曲线非对称、SM3 杂凑与密钥派生 | `Sm2ConfigDecryptor`, `Sm2Engine`, `Sm3Digest`, `KeyDerivationHelper` | `algorithm=SM2` | `generateKeyPair()`, `encrypt(plain, pubHex)`, `decrypt(cipher, priHex)`, `c1c3c2ToDer(c1c3c2)`, `derToC1c3c2(der)`, `Sm3Digest.digest(...)`, `KeyDerivationHelper.deriveKey(pass, len)` | `Sm2ConfigDecryptorTest.java`, `Sm2EngineTest.java` |
| **标准 RSA 非对称** | 2048/4096 位 RSA 密钥分域与 PEM 互通 | `RsaConfigDecryptor`, `ConfigDecryptor` | `algorithm=RSA` | `generateKeyPair(size)`, `encodePublicKeyToPem(pub)`, `encodePrivateKeyToPem(pri)`, `encrypt(plain, pemPub)`, `decrypt(cipher, pemPri)` | `RsaConfigDecryptorTest.java`, `EncryptCliTest.java` |
| **密文语法解析** | 自定义前后缀与 Spring Cloud 兼容提取器 | `CiphertextParser`, `ParsedToken` | `prefix`, `suffix`, `compatible-cipher-prefix` | `hasEncrypted(value)`, `replaceTokens(value, fn)`, 实例级 `formatToken(cipher)` / `formatToken(keyId, cipher)` / `formatToken(algo, cipher)` / `formatToken(defAlgo, keyId, algo, cipher)` 及静态 `formatToken(...)`；支持 `CIPHER[keyId](...)`、`MY_ENC[keyId][...]` 双向对称与 `{cipher}...` 兼容 | `CiphertextParserTest.java`, `EncryptTemplateTest.java`, `EncryptPropertySourceWrapperTest.java` |
| **生产防误伤门禁** | 生产强制加密与三道防误伤过滤链 | `EnforceEncryptionPolicy`, `EncryptProperties` | `enforce-encrypted`, `sensitive-key-pattern`, `ignored-keys` | `enforce(env, pattern, ignoredKeys, parser)`；URI 协议（`http://`, `https://`, `classpath:`, `file:`）与多级绝对路径放行、日志级别（`DEBUG` 等）放行、白名单硬放行；裸明文敏感属性刚性拦截 | `EncryptGuardController.java`, `EnforceEncryptionPolicyTest.java`, `SampleEncryptWebControllerTest.java` |
| **配置热更新防御** | 配置中心热推送防倒灌与单例注册表联动 | `PlaintextIngressGuard` | `enforce-encrypted` | `onEnvironmentChange(changedKeys, env)`, `onApplicationEvent(event)`；复用容器受管 `MultiKeyRegistry` 原子置换缓存并拦截动态推送裸明文 | `EncryptGuardController.java`, `IngressGuardScenarioService.java`, `PlaintextIngressGuardTest.java` |
| **配置解密引擎装配工厂** | 配置解密引擎 SPI 发现与无双重重试装配 | `ConfigDecryptorFactory` | `retry.*` | `createDefaultDecryptors(custom, retry)`，收敛 `ServiceLoader` 发现、KMS 单层重试适配（杜绝双重重试）与 JDK 原生配置解密引擎装配 | `AbstractKmsConfigDecryptorTest.java`, `EncryptSampleApplicationTest.java` |
| **外部 KMS SPI** | 外部云 KMS / HSM 抽象解密扩展基类 | `AbstractKmsConfigDecryptor`, `ConfigDecryptor` | `algorithm=KMS`, `retry.*` | 继承实现 `doDecrypt(ciphertext, keyIdOrPassword)`，支持 `supports(algo)` 与 `updateRetryProperties(retry)` 无缝对接云 KMS 及硬件加密机 | `AbstractKmsConfigDecryptorTest.java`, `EncryptSampleApplicationTest.java` |
| **强类型弹性重试** | 远程云端解密指数退避重试装饰器 | `RetryingConfigDecryptor`, `EncryptRetryProperties` | `retry.max-attempts`, `retry.delay`, `retry.max-delay`, `retry.multiplier`, `retry.jitter` | 默认 `max-attempts=3`、`delay=300ms`、`max-delay=5s`、`multiplier=2.0`、`jitter=true`；`toRetryConfig(...)`，自动排除密码学错误快速失败 | `RetryingConfigDecryptorTest.java`, `EncryptRetryPropertiesTest.java` |
| **Actuator 物理脱敏** | 监控端点主密钥、多密钥与密文物理掩码 | `SanitizingGuard` | `password`, `keys.*`, `sensitive-key-pattern` | `apply(data)` 实现 `SanitizingFunction`，对 `ateng.boot.encrypt.password`、`ateng.boot.encrypt.keys.*`、密文标记与敏感键名在 `/actuator/env` 无条件掩码为 `******` | `EncryptSampleApplicationTest.java`, `SampleEncryptWebControllerTest.java`, `SanitizingGuardTest.java` |
| **就地双模智能回写** | YAML/Properties 保真回写与多行块标量往返 | `InPlaceRewriter`, `EncryptFileRewriter`, `# @encrypt` 指令 | - | `rewriteEncrypt(path)`, `rewriteDecrypt(path)`, `encryptFile(file, pass)`, `decryptFile(file, pass)`, `resolveBackupPath(path)`；支持多文档 `---`、占位符 `${...}` 免疫、YAML `\|` 块标量无损往返与 `.bak` 备份 | `EncryptInPlaceRewriteController.java`, `InPlaceRewriteScenarioService.java`, `InPlaceRewriterTest.java` |
| **脱机安全运维 CLI** | 终端脱机单值、整文件回写与密钥工具箱 | `EncryptCli` | - | `EncryptCli.main(args)`, `run(args)`；批处理支持 `--encrypt`/`-e`、`--decrypt`/`-d`、`--encrypt-file`/`-ef`、`--decrypt-file`/`-df`、`--file`/`-f`、`--keygen`/`-k`、`--key-id`，交互式菜单 `[1]-[7]` | `EncryptCliTest.java` |
| **多行大文本规范** | PEM 证书与多行私钥清洗及标准化格式化 | `MultilineSecretFormatter` | - | `cleanCiphertext(raw)`, `isPem(text)`, `formatPem(text)`, `formatPem(text, lineSeparator)`, `formatAsPem(type, base64)`，清洗 YAML 折行空白并保真还原 PEM 首尾边界 | `MultilineSecretFormatterTest.java` |
| **密钥探测与防护** | 主密钥分级探测与严格外部授信源拦截 | `KeyDetector` | `password`, `keys.*` | `detect(env)`, `resolveDefaultPassword(env)`, `getPassword(keyId)`；仅放行命令行、系统属性、环境变量与内存测试源，阻断本地/`application-test.yml` 自加密 | `KeyDetectorTest.java`, `EncryptSampleApplicationTest.java` |
| **语义化异常体系** | 保留因果链的解密失败与安全阻断异常 | `ConfigDecryptionException`, `ConfigSecurityException` | `fail-on-error`, `enforce-encrypted` | `ConfigDecryptionException` 暴露 `getPropertyKey()`, `getKeyId()`, `getAlgorithm()` 并保留完整 `cause` 链（严禁打印明文/密文）；`ConfigSecurityException` 阻断明文倒灌与自加密 | `EncryptTemplateTest.java`, `KeyDetectorTest.java`, `EnforceEncryptionPolicyTest.java` |

---

## 1. 模块定位与快速起步 (维度一：最小可用配置与极简实战代码)

### 1.1 一句话痛点与设计原则
在微服务与单体应用中，数据库连接密码、缓存凭据、第三方 API 令牌、证书私钥等明文写入配置文件存在极高泄露风险；而传统加密插件依赖重、启动慢、且容易因配置中心握手时序问题引发 403 认证死锁。`ateng-boot-encrypt` 专职解决企业级配置安全问题：
- **纯原生零依赖 (Zero-Bloat & Core-First)**：100% 依托 JDK 17+ 标准库与 Spring 6 原生能力，国密 SM2/SM3/SM4 纯算法内聚实现，**坚决不引入 BouncyCastle、Jasypt 等重型第三方依赖**；
- **透明无感懒解密 (Transparent Lazy Decryption)**：基于 Spring Boot `EnvironmentPostProcessor` 与 `ConfigData` 两阶段机制（`Two-Phase Wrapper Sync`），在配置加载与容器期自动装配同步解密组件，业务代码、`@ConfigurationProperties` 与 `@Value` 完全无感；
- **现代高强密码标配 (Modern Cryptography Defaults)**：默认采用具备 AEAD 完整性认证防篡改的 **AES-256-GCM** 算法，自动附带 12 字节安全随机 IV，彻底抛弃过时的 ECB/DES 模式；
- **多 Key 轮换与隔离 (Multi-Key Routing & Isolation)**：独创 `ENC[keyId](...)`（及自定义前后缀 `[keyId]`）语义契约，支持不同组件独立密钥管理、`KeyRotatedEvent` 领域事件通知与无停机平滑轮换；
- **内存真明文原则 (In-Memory Plaintext Invariant)**：业务层与实体在内存中始终保持 100% 原始真实明文，只在向外暴露端点处执行掩码脱敏，杜绝脏数据写回。

### 1.2 引入依赖 (Maven)

**方式一：通过 BOM 统一版本管理（推荐，免配 `<version>`）**：
先在工程 `<dependencyManagement>` 中导入 [`ateng-boot-bom`](../ateng-boot-bom/README.md)，再引入模块坐标：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-encrypt</artifactId>
</dependency>
```

**方式二：独立显式版本引入**：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-encrypt</artifactId>
    <version>1.2.0</version>
</dependency>
```

### 1.3 最小可用配置 (`application.yml`)
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
    database-password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)
    # 缓存密码：指定多 Key 路由 [redis-cluster] 执行解密
    redis-password: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)
    # 第三方API令牌：显式指定国密 SM4-CBC 算法加解密
    third-party-token: ENC(SM4-CBC@ODPVE+KjssNqGN6yrBqMlXcYvDKQgNYADkGV+HV6vL+/H/Juo4KFpgIuVeRMmIT+)
```

### 1.4 业务代码透明无感使用与主密钥注入规范
业务代码完全无需感知密文，直接通过强类型属性对象获取已解密的真实明文：
```java
@Data
@ConfigurationProperties(prefix = "sample.secret")
public class SampleSecretProperties {
    private String databasePassword;
    private String redisPassword;
    private String thirdPartyToken;
}
```

专职场景服务透明注入与验证（提取自 `ConfigDecryptionScenarioService.java`）：
```java
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

> [!IMPORTANT]
> **主密钥注入规范与防自加密铁律**：严禁将解密主密钥（`ateng.boot.encrypt.password`）写在本地 `application.yml` 中（Starter 将直接抛出 `ConfigSecurityException` 拦截自加密）。请统一通过环境变量或启动参数动态注入：
> ```bash
> # 生产标准推荐：环境变量
> export ATENG_BOOT_ENCRYPT_PASSWORD="SampleMasterSecretKey2026!"
> 
> # 或 JVM 启动参数
> java -Dateng.boot.encrypt.password=SampleMasterSecretKey2026! -jar app.jar
> ```

---

## 2. 核心架构拓扑与设计不变量

```mermaid
flowchart TD
    subgraph ConfigLayer["配置输入与生命周期层 (Config Sources)"]
        YAML_SRC["本地文件: application.yml / properties"]
        ENV_SRC["系统环境: SystemEnvironment (K8s Secret)"]
        CMD_SRC["命令行: SimpleCommandLine (--ateng.boot.encrypt.password)"]
        REMOTE_SRC["配置中心: Nacos / Apollo (spring.config.import)"]
    end

    subgraph PreResolverPhase["阶段一：ConfigData 瞬态预解析守卫与两阶段同步 (Pre-Resolver & Two-Phase Sync)"]
        HOOK["EncryptConfigDataLocationResolver (ORDER = HIGHEST_PRECEDENCE)"]
        INSTANT_WRAP["即时装饰原始 PropertySource 为 EncryptPropertySourceWrapper"]
        NACOS_BIND["优先解密 nacos.username / nacos.password 连接凭据"]
        SYNC_BRIDGE["Two-Phase Wrapper Sync (EPP 与容器期反向回填同步)"]
        HOOK --> INSTANT_WRAP --> NACOS_BIND --> SYNC_BRIDGE
    end

    subgraph CoreEngine["加解密核心引擎矩阵 (Crypto & Decryptor Engine)"]
        TEMPLATE["EncryptTemplate (受管高阶门面)"]
        STATIC_FACADE["ConfigEncryptor (静态无状态对称纯函数)"]
        ROUTER["MultiKeyRegistry (多密钥路由 & 版本平滑轮换)"]
        AES_GCM["AesGcmConfigDecryptor (默认: 12字节IV + 128位Tag)"]
        SM4_CBC["Sm4ConfigDecryptor (纯JDK原生国密 SM4)"]
        RSA_ASYM["RsaConfigDecryptor (非对称公私钥分离: 2048/4096)"]
        SM2_ASYM["Sm2ConfigDecryptor (纯原生国密 SM2 C1C3C2 / DER)"]
        RETRY_DEC["RetryingConfigDecryptor (基于 Core RetryUtil 的指数退避重试)"]
        KMS_SPI["AbstractKmsConfigDecryptor (外部云 KMS / HSM SPI)"]
        ROUTER --> AES_GCM
        ROUTER --> SM4_CBC
        ROUTER --> RSA_ASYM
        ROUTER --> SM2_ASYM
        ROUTER --> RETRY_DEC --> KMS_SPI
    end

    subgraph DefenseGuards["多重防御与质量门禁 (Runtime & Pre-Flight Guards)"]
        ENFORCE_GUARD["EnforceEncryptionPolicy (生产环境敏感字段明文硬阻断)"]
        INGRESS_GUARD["PlaintextIngressGuard (配置中心动态热更新防明文倒灌)"]
        ACTUATOR_GUARD["SanitizingGuard (Spring Boot 3 SanitizingFunction 物理掩码)"]
        FAIL_FAST["FailFastPolicy (解密失败指明出错Key，严禁带病启动)"]
    end

    subgraph ToolingLayer["离线运维与开发者工具链 (Developer Tooling)"]
        CLI["EncryptCli (终端单值加解密、整文件 --encrypt-file/--decrypt-file 与密钥对生成)"]
        REWRITER["InPlaceRewriter (YAML/Properties 就地加解密、多行块标量无损往返与 .bak 备份)"]
        FORMATTER["MultilineSecretFormatter (cleanCiphertext / formatPem 清洗与 PEM 标准化)"]
    end

    ConfigLayer --> PreResolverPhase
    PreResolverPhase --> CoreEngine
    CoreEngine --> DefenseGuards
    ToolingLayer -.->|离线生成密文 / 就地回写| ConfigLayer
```

---

## 3. 环境属性透明懒解密与多算法协同套件

### 3.1 默认 AES-256-GCM AEAD 透明解密
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        enabled: true
        algorithm: AES-GCM # 全局默认高强算法 (带 128 位抗篡改 Tag)
        prefix: "ENC("     # 密文起始标记
        suffix: ")"        # 密文结束标记
        fail-on-error: true # 解密失败立即阻断启动
  ```
- **业务 Java 示例**（提取自 `EncryptDecryptionController.java`）：
  ```java
  @GetMapping("/secrets")
  public Result<SampleSecretVO> getDecryptedSecrets() {
      SampleSecretVO vo = SampleSecretVO.builder()
              .databasePassword(decryptionScenarioService.getDatabasePasswordFromProperties())
              .redisPassword(decryptionScenarioService.getRedisPasswordFromProperties())
              .thirdPartyToken(decryptionScenarioService.getThirdPartyTokenFromProperties())
              .activeAlgorithm("AES-GCM")
              .decryptionStatus(decryptionScenarioService.verifyDecryptionConsistency() ? "VERIFIED_CONSISTENT" : "MISMATCH")
              .build();
      return Result.success(vo);
  }
  ```
- **核心机制与边界防御**：
  1. 采用具备 AEAD（Authenticated Encryption with Associated Data）认证的 AES-256-GCM 算法，每次加密生成 12 字节安全随机 IV，有效抵御密文篡改与重放攻击；
  2. 采用懒解密机制，在配置属性首次通过 `getProperty(name)` 访问时按需计算并原子写入内存缓存；解密失败时抛出语义化 `ConfigDecryptionException` 指明出错键名。

### 3.2 显式指定算法加解密 (SM4-CBC / SM2 / RSA)
- **专属配置片段**：
  ```yaml
  sample:
    secret:
      # 显式指定国密 SM4-CBC 算法解密（格式: ENC(SM4-CBC@<base64>)）
      third-party-token: ENC(SM4-CBC@ODPVE+KjssNqGN6yrBqMlXcYvDKQgNYADkGV+HV6vL+/H/Juo4KFpgIuVeRMmIT+)
  ```
- **业务 Java 示例**（提取自 `SampleEncryptWebControllerTest.java`）：
  ```java
  @Test
  void testDecryptedSecrets() throws Exception {
      mockMvc.perform(get("/sample/encrypt/decrypt/secrets"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.thirdPartyToken").value("sm4ApiSecretToken888"));
  }
  ```
- **核心机制与边界防御**：
  1. 令牌解析器（`CiphertextParser`）支持在括号内以 `@` 前缀显式指定算法名称，如 `ENC(SM4-CBC@...)`、`ENC(RSA@...)`；
  2. 当密文内声明了显式算法时，其优先级高于全局默认 `ateng.boot.encrypt.algorithm`，实现混合多算法安全协同。

### 3.3 Spring Cloud `{cipher}` 历史前缀平滑兼容
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        compatible-cipher-prefix: true # 开启历史 Spring Cloud Config {cipher} 前缀兼容
  ```
- **业务 Java 实测示例**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  // 配合 Two-Phase Wrapper Sync，即使通过 spring.config.import 后置覆盖 compatible-cipher-prefix: true，
  // 仍可透明解密 {cipher}... 属性：
  SampleSecretProperties props = ctx.getBean(SampleSecretProperties.class);
  assertEquals("twoPhaseLegacyRedisPass", props.getRedisPassword());
  ```
- **核心机制与边界说明**：
  当旧工程迁移至 `ateng-boot` 时，开启 `compatible-cipher-prefix: true` 后，解析器将透明剥离 `{cipher}` 前缀并调用解密引擎，实现零业务代码修改平滑迁移。

---

## 4. 统一编程式加解密与双层完备对称门面套件

### 4.1 Spring 受管 `EncryptTemplate` 业务门面
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        enabled: true
        algorithm: AES-GCM
  ```
- **业务 Java 示例**（提取自 `EncryptCryptoController.java`）：
  ```java
  @PostMapping("/encrypt")
  public Result<CryptoResultVO> encryptDefault(@Valid @RequestBody CryptoEncryptRequest request) {
      String token = cryptoScenarioService.encryptDefault(request.getPlaintext());
      CryptoResultVO vo = CryptoResultVO.builder()
              .plaintext(request.getPlaintext())
              .token(token)
              .algorithm("AES-GCM")
              .build();
      return Result.success(vo);
  }

  @PostMapping("/decrypt")
  public Result<String> decrypt(@Valid @RequestBody CryptoDecryptRequest request) {
      String plain = cryptoScenarioService.decrypt(request.getCiphertext());
      return Result.success(plain);
  }
  ```
- **核心机制与边界防御说明**：
  `EncryptTemplate` 为 Spring 单例受管 Bean，具备完全对称语义（`encrypt` / `decrypt`），内部自动装配当前主密钥、算法与前后缀解析器，避免业务层手动解析 SPI。

### 4.2 `ConfigEncryptor` 静态无状态对称纯函数门面
- **适用配置说明**：纯原生无状态静态门面，无需 Spring 容器或 YAML 配置，直接传入明文/密文、密钥与 `EncryptAlgorithm` 即可执行。
- **业务 Java 示例**（提取自 `ProgrammaticCryptoScenarioService.java`）：
  ```java
  public String executeDirectStaticCrypto(String plaintext, String password, EncryptAlgorithm algorithm) {
      String cipher = ConfigEncryptor.encrypt(plaintext, password, algorithm);
      return ConfigEncryptor.decrypt(cipher, password, algorithm);
  }
  ```
- **核心机制与边界防御说明**：
  `ConfigEncryptor` 提供静态纯函数 API，具备严格对称语义（入参为 `null` 返回 `null`），脱离 Spring 容器亦可直接运行，非常适合单测测试夹具或独立命令行工具调用。

### 4.3 纯 Base64 裸密文、命名密钥快捷重载与容错逃生舱
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        algorithm: AES-GCM # 命名密钥缺省重载自动沿用环境默认算法
        keys:
          redis-cluster: RedisSpecialClusterKey2026!
  ```
- **业务 Java 示例**（提取自 `EncryptCryptoController.java`）：
  ```java
  @PostMapping("/encrypt-key")
  public Result<CryptoResultVO> encryptWithKey(@Valid @RequestBody CryptoKeyEncryptRequest request) {
      String token = cryptoScenarioService.encryptWithKey(request.getPlaintext(), request.getKeyId());
      CryptoResultVO vo = CryptoResultVO.builder()
              .plaintext(request.getPlaintext())
              .token(token)
              .keyId(request.getKeyId())
              .algorithm(cryptoScenarioService.getDefaultAlgorithmCode())
              .build();
      return Result.success(vo);
  }

  @PostMapping("/decrypt-key")
  public Result<String> decryptWithKey(@Valid @RequestBody CryptoKeyDecryptRequest request) {
      String plain = cryptoScenarioService.decryptWithKey(request.getCiphertext(), request.getKeyId());
      return Result.success(plain);
  }

  @PostMapping("/encrypt-raw")
  public Result<String> encryptRaw(@Valid @RequestBody CryptoRawRequest request) {
      String raw = cryptoScenarioService.encryptRaw(request.getPlaintext());
      return Result.success(raw);
  }

  @PostMapping("/decrypt-safe")
  public Result<String> decryptSafe(@Valid @RequestBody CryptoSafeDecryptRequest request) {
      String safeValue = cryptoScenarioService.decryptOrDefault(request.getCiphertext(), request.getDefaultValue());
      return Result.success(safeValue);
  }
  ```
- **核心机制与边界说明**：
  1. `EncryptTemplate` 提供 `encrypt(plaintext, keyId)`、`encryptRaw(plaintext, keyId)` 与 `decrypt(ciphertext, keyId)` 三个缺省算法对称重载，自动沿用环境默认算法并路由至指定 `keyId`（缺失密钥或密文损坏时 Fail-Fast 抛出保留 `cause` 的 `ConfigDecryptionException`）；
  2. `decryptOrDefault` / `decryptOrOriginal` 提供容错逃生舱机制：在面对非合规密文或损坏数据时安全返回默认值或原串，杜绝单点解密失败瘫痪核心业务主流程。

### 4.4 自定义前后缀 (`prefix` / `suffix`) 与 `[keyId]` 双向对称闭环
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        prefix: "CIPHER(" # 也支持 MY_ENC[ / SEC{ 或非括号前缀 CIPHER:
        suffix: ")"
        keys:
          redis-cluster: CustomPrefixRedis2026!
  ```
- **业务 Java 实测示例**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  EncryptTemplate template = ctx.getBean(EncryptTemplate.class);
  String routedToken = template.encrypt("round-trip-routed", "redis-cluster");
  // 输出: CIPHER[redis-cluster](...)
  assertTrue(template.isEncrypted(routedToken));
  assertEquals("round-trip-routed", template.decrypt(routedToken, "redis-cluster"));

  String sm4RoutedToken = template.encrypt("round-trip-sm4", "redis-cluster", EncryptAlgorithm.SM4_CBC);
  // 输出: CIPHER[redis-cluster](SM4-CBC@...)
  assertEquals("round-trip-sm4", template.decrypt(sm4RoutedToken));
  ```
- **核心机制与边界说明**：
  1. `CiphertextParser` 在配置非默认 `prefix` / `suffix` 时，若 `prefix` 以开括号（`(`、`[`、`{`）结尾，自动在开括号前解析/组装可选 `[keyId]` 标签（如 `CIPHER[redis-cluster](...)`、`MY_ENC[redis-cluster][...]`），非括号前缀亦支持 `[keyId]` 对称识别；
  2. `EncryptTemplate.formatToken` 与 `encrypt` 委派至持有的解析器实例，保障 `EncryptTemplate` 与 `EncryptPropertySourceWrapper` 在任意自定义前后缀下 100% 往返一致，并完整保留静态 `CiphertextParser.formatToken(...)` 与标准 `ENC(...)` 向后兼容。

---

## 5. 多 Key 命名路由与无感平滑轮换套件

### 5.1 `ENC[keyId](...)` 别名语义路由
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        keys:
          redis-cluster: RedisSpecialClusterKey2026!
          payment-shard: PaymentMasterSecretPass888!

  sample:
    secret:
      redis-password: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)
  ```
- **业务 Java 示例**（提取自 `EncryptMultiKeyController.java`）：
  ```java
  @PostMapping("/decrypt-routed")
  public Result<String> decryptRoutedToken(@Valid @RequestBody RoutedDecryptRequest request) {
      String plain = multiKeyService.decryptRoutedToken(request.getToken());
      return Result.success(plain);
  }
  ```
- **核心机制与边界说明**：
  支持 `ENC[keyId](...)` 与 `ENC[keyId](algorithm@...)` 语法，解析器自动提取方括号内的 `keyId` 并路由至 `MultiKeyRegistry` 获取对应密钥，实现不同业务组件独立秘钥隔离。

### 5.2 动态密钥注册与平滑轮换
- **业务 Java 示例**（提取自 `EncryptMultiKeyController.java`）：
  ```java
  @PostMapping("/rotate")
  public Result<Boolean> rotateKey(@Valid @RequestBody KeyRotateRequest request) {
      multiKeyService.rotateKey(request.getKeyId(), request.getNewPassword());
      return Result.success(true);
  }
  ```
- **核心机制与边界防御说明**：
  调用 `MultiKeyRegistry.registerKey` 注册或更新密钥时，系统自动清空已代理属性源中的解密内存缓存，并通过领域事件通知下游监听器，零停机平滑轮换。

### 5.3 `KeyRotatedEvent` 领域事件与缓存原子置换
- **业务 Java 示例**（提取自 `MultiKeyRegistry.java`）：
  ```java
  @EventListener
  public void onKeyRotated(KeyRotatedEvent event) {
      log.info("监听到密钥轮换事件: keyId={}, timestamp={}", event.getKeyId(), event.getTimestamp());
  }
  ```
- **核心机制说明**：
  轮换触发后广播 Spring 上下文事件 `KeyRotatedEvent`，底层属性源装饰器执行原子置换 `AtomicReference<ConcurrentHashMap<String, Object>>`，并发读取线程既无锁竞争又绝对不会脏读旧明文。

---

## 6. 生产强制加密与三道防误伤门禁套件

### 6.1 `enforce-encrypted` 生产敏感明文刚性阻断
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        enforce-encrypted: true # 生产环境建议开启：强制阻断裸明文密码
        sensitive-key-pattern: ".*password.*|.*secret.*|.*token.*|.*private-key.*"
  ```
- **业务 Java 实测断言**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  @Test
  void testEnforceEncryptedBlocksUnencryptedSecretsInApplication() {
      assertThrows(Exception.class, () -> {
          new SpringApplicationBuilder(EncryptSampleApplication.class)
                  .properties("ateng.boot.encrypt.password=SampleMasterSecretKey2026!", "ateng.boot.encrypt.enforce-encrypted=true")
                  .initializers(context -> {
                      context.getEnvironment().getPropertySources().addFirst(
                              new MapPropertySource("external-source", Map.of("spring.datasource.password", "nakedPlaintextSecret999")));
                  })
                  .run();
      });
  }
  ```
- **核心机制与边界说明**：
  在应用启动前置扫描外部属性源，若命中敏感正则且属性值未以 `ENC(...)` 标记包裹（包括未加双引号的纯数字密码），抛出 `ConfigSecurityException` 立即熔断阻断启动。

### 6.2 三道防误伤过滤链 (协议/路径/日志级别/白名单)
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        enforce-encrypted: true
        ignored-keys:
          - sample.secret.whitelist-key
  ```
- **业务 Java 示例**（提取自 `EncryptGuardController.java`）：
  ```java
  @GetMapping("/rules")
  public Result<IngressRuleVO> getIngressRules() {
      String configuredPattern = encryptProperties.getSensitiveKeyPattern();
      String effectivePattern = (configuredPattern != null && !configuredPattern.trim().isEmpty())
              ? configuredPattern.trim()
              : EnforceEncryptionPolicy.DEFAULT_SENSITIVE_KEY_PATTERN;
      Set<String> effectiveIgnoredKeys = encryptProperties.getIgnoredKeys() != null
              ? Collections.unmodifiableSet(new LinkedHashSet<>(encryptProperties.getIgnoredKeys()))
              : Collections.emptySet();

      IngressRuleVO vo = IngressRuleVO.builder()
              .enforceEncrypted(encryptProperties.isEnforceEncrypted())
              .sensitivePattern(effectivePattern)
              .ignoredKeys(effectiveIgnoredKeys)
              .protocolImmunities(List.of("http://", "https://", "classpath:", "file:", "ws://", "wss://"))
              .logLevelImmunities(List.of("DEBUG", "INFO", "WARN", "ERROR", "TRACE"))
              .build();
      return Result.success(vo);
  }
  ```
- **核心机制说明**：
  1. **协议与多级路径特征豁免**：以 `http://`、`https://`、`classpath:`、`file:` 开头，或属于多级绝对路径（如 `/etc/ssl/cert.pem`）自动豁免；
  2. **日志级别特征豁免**：属性值为标准日志级别（`DEBUG`、`INFO` 等）时自动放行；
  3. **白名单硬放行**：`ateng.boot.encrypt.*` 属性与 `ignored-keys` 显式声明的属性跳过校验。

### 6.3 `PlaintextIngressGuard` 配置中心动态热推防倒灌
- **业务 Java 实测断言**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  @Test
  void testPlaintextIngressGuardBlocksDynamicUnencryptedPush() {
      assertThrows(ConfigSecurityException.class, () -> {
          service.simulatePlaintextIngress("spring.datasource.password", "hotPushedPlaintext123");
      });
  }
  ```
- **核心机制说明**：
  监听 `EnvironmentChangeEvent` / `RefreshEvent` 事件，在配置中心热推送配置时持续校验，坚决拒绝生产环境动态推送裸明文敏感配置覆盖有效密文。

---

## 7. 配置文件就地双模智能回写与格式保真套件

### 7.1 YAML / Properties 保真就地回写与多行块标量无损往返 (`Block Scalar Round-Trip Rewrite`)
- **适用配置与指令语法**：
  ```yaml
  rsa:
    # @encrypt
    private-key: |
      -----BEGIN PRIVATE KEY-----
      MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7
      -----END PRIVATE KEY-----
  ```
- **业务 Java 示例**（提取自 `EncryptInPlaceRewriteController.java` 与 `SampleEncryptWebControllerTest.java`）：
  ```java
  @PostMapping("/encrypt-yaml")
  public Result<RewriteResultVO> encryptYaml(@Valid @RequestBody InPlaceRewriteRequest request) {
      RewriteResultVO vo = executeSandboxRewrite(
              request.getContent(), "yml", request.getPassword(),
              request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM, true);
      return Result.success(vo);
  }

  @PostMapping("/decrypt-yaml")
  public Result<RewriteResultVO> decryptYaml(@Valid @RequestBody InPlaceRewriteRequest request) {
      RewriteResultVO vo = executeSandboxRewrite(
              request.getContent(), "yml", request.getPassword(),
              request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM, false);
      return Result.success(vo);
  }
  ```
- **核心机制与边界防御说明**：
  1. `InPlaceRewriter`（及别名 `EncryptFileRewriter`）识别敏感键或 `# @encrypt` / `# @encrypt[keyId](ALGO)` 指令并就地替换为 `ENC(...)`，重写前强制生成 `.bak` 备份（`InPlaceRewriter.resolveBackupPath(path)`），100% 保持缩进、空行、行尾注释与原始换行符（`LF` / `CRLF`）；
  2. **YAML 多行块标量无损往返**：逆向执行 `rewriteDecrypt` 时，若非 `.properties` 的 YAML 键值行解密出多行文本（如 PEM 私钥/证书），自动重构为 `<indent><key><separator>|<inlineComment>` 后接 `<indent>  ` 缩进的合法 YAML 块标量。

### 7.2 占位符 `${...}` 与 SpEL 免疫机制
- **核心机制说明**：
  在执行就地加密时，自动识别形如 `${spring.profiles.active}`、`${DB_PASS:root}` 的环境变量占位符或 SpEL 表达式（`#{...}`）并严格豁免加密，杜绝将动态外部注入变量固化为死密文。

### 7.3 脱机安全运维终端 CLI 工具箱 (`EncryptCli`)
- **终端交互式菜单（菜单 `[1]-[7]`，含 `[5]` 配置文件就地加密与 `[6]` 配置文件就地解密还原）**：
  ```bash
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli
  ```
- **命令行批处理（适配 CI/CD 流水线，支持单值加解密、整文件就地回写与密钥对生成）**：
  ```bash
  # 1. 快速单值加密 (AES-GCM)
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
    --encrypt -v "mysqlRootPass2026!" -p "SampleMasterSecretKey2026!"

  # 2. 整文件就地加密（生成 .bak 备份并输出处理字段数，支持 --key-id 与 -a 算法指定）
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
    --encrypt-file -f application-prod.yml -p "SampleMasterSecretKey2026!"

  # 3. 整文件就地解密还原（短参 -df，支持 YAML 多行块标量无损还原）
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli \
    -df -f application-prod.yml -p "SampleMasterSecretKey2026!"

  # 4. 生成非对称 RSA / 纯原生国密 SM2 密钥对
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli --keygen -a RSA --key-size 2048
  java -cp ateng-boot-encrypt.jar io.github.atengk.boot.encrypt.cli.EncryptCli --keygen -a SM2
  ```

---

## 8. 运行时端点安全防御与物理脱敏套件

### 8.1 Actuator `/actuator/env` 物理掩码脱敏 (`SanitizingGuard`)
- **专属配置片段**：
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: env,health,info
    endpoint:
      env:
        show-values: ALWAYS
  ```
- **业务 Java 实测断言**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  @Test
  void testActuatorSanitizingGuardMasking() {
      SanitizingGuard sanitizingGuard = applicationContext.getBean(SanitizingGuard.class);
      SanitizableData sensitiveData = new SanitizableData(null, "sample.secret.database-password", "mysqlRootPass2026!");
      SanitizableData sanitized = sanitizingGuard.apply(sensitiveData);
      assertEquals("******", sanitized.getValue());
  }
  ```
- **核心机制说明**：
  基于 Spring Boot 3 `SanitizingFunction` 规范，对所有命中敏感正则、包含密文标记，以及主密钥（`ateng.boot.encrypt.password`）与任意命名多密钥（`ateng.boot.encrypt.keys.*`）在监控端点无条件物理掩码为 `******`，彻底杜绝运行时凭据外泄。

---

## 9. 生产扩展接管与核心非功能边界 (维度二 & 维度三)

### 9.1 扩展外部云 KMS / 硬件加密机 SPI (`AbstractKmsConfigDecryptor`)
- **专属配置片段**：
  ```yaml
  sample:
    kms:
      value-secret: ENC(KMS@value-payload)
      config-secret: ENC(KMS@config-payload)
  ```
- **扩展实现示例**（提取自 `EncryptSampleApplicationTest.java`）：
  ```java
  @Bean
  AbstractKmsConfigDecryptor sampleKmsDecryptor(KmsInvocationTracker tracker) {
      return new AbstractKmsConfigDecryptor() {
          @Override
          protected String doDecrypt(String ciphertext, String password) throws Exception {
              if ("bad-padding".equals(ciphertext)) {
                  tracker.badPaddingAttempts.incrementAndGet();
                  throw new BadPaddingException("sample bad padding");
              }
              int attempt = tracker.transientAttempts.incrementAndGet();
              if (attempt < 4) {
                  throw new IOException("sample transient kms failure #" + attempt);
              }
              return "e2e-kms:" + ciphertext;
          }
      };
  }
  ```
- **核心机制与边界说明**：
  通过 Spring `@Bean` 或 `META-INF/services/io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor` SPI 注册后，容器同步桥接机制自动将 KMS 配置解密引擎回填至环境属性源包装器，无缝支持 `@Value`、`@ConfigurationProperties` 与 `EncryptTemplate` 透明调用。

### 9.2 强类型弹性指数退避重试 (`RetryingConfigDecryptor`)
- **专属配置片段**：
  ```yaml
  ateng:
    boot:
      encrypt:
        retry:
          max-attempts: 3  # 最大尝试次数（含首次执行）
          delay: 300ms     # 初始退避延迟 (java.time.Duration)
          max-delay: 5s    # 最大退避延迟 (java.time.Duration)
          multiplier: 2.0  # 指数退避乘数
          jitter: true     # 启用 Full Jitter 打散重试洪峰
  ```
- **边界防御说明**：
  1. 内置密码学异常短路机制：遇到 `BadPaddingException`、`AEADBadTagException` 等密文/口令损坏错误时立即快速失败，杜绝盲目重试；
  2. `ConfigDecryptorFactory` 在合并容器 `AbstractKmsConfigDecryptor` Bean 时直接更新其内部重试策略而不额外包裹 `RetryingConfigDecryptor`，彻底消除 KMS 双重嵌套重试。

### 9.3 配置中心极早期穿透守卫 (`EncryptConfigDataLocationResolver`)
- **核心机制说明**：
  在 Spring Boot 3 `ConfigDataLocationResolver` 极早期阶段（`ORDER = HIGHEST_PRECEDENCE`），Nacos / Apollo 客户端发起网络连接握手前，守卫穿透已加载的贡献源优先解密连接凭据，彻底根除 403 认证死锁。

---

## 10. 完整 YAML 配置属性清单 (全量速查附录)

### 10.1 全量配置属性字典

| 配置项 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `ateng.boot.encrypt.enabled` | `Boolean` | `true` | 是否启用配置加解密透明解密底座 |
| `ateng.boot.encrypt.password` | `String` | `null` | 全局默认主解密密钥（严禁写入本地配置文件） |
| `ateng.boot.encrypt.algorithm` | `EncryptAlgorithm` | `AES-GCM` | 全局默认加密算法（支持 `AES-GCM`, `AES-CBC`, `SM4-CBC`, `SM4-ECB`, `RSA`, `SM2`, `KMS`） |
| `ateng.boot.encrypt.prefix` | `String` | `ENC(` | 密文起始包裹标记 |
| `ateng.boot.encrypt.suffix` | `String` | `)` | 密文结束包裹标记 |
| `ateng.boot.encrypt.fail-on-error` | `Boolean` | `true` | 解密失败时是否立即阻断启动（快速失败） |
| `ateng.boot.encrypt.compatible-cipher-prefix` | `Boolean` | `false` | 是否兼容解析 Spring Cloud Config `{cipher}` 历史前缀 |
| `ateng.boot.encrypt.enforce-encrypted` | `Boolean` | `false` | 生产环境强制加密门禁：检测到未加密敏感配置时阻断启动 |
| `ateng.boot.encrypt.sensitive-key-pattern` | `String` | `.*password.*\|.*secret.*\|.*token.*\|.*private-key.*` | 敏感属性键名正则表达式特征 |
| `ateng.boot.encrypt.ignored-keys` | `Set<String>` | `empty` | 生产强制加密门禁显式豁免白名单属性集合 |
| `ateng.boot.encrypt.keys.*` | `Map<String, String>` | `empty` | 多 Key 命名密钥映射表（KeyId -> Password/PrivateKey） |
| `ateng.boot.encrypt.retry.max-attempts` | `Integer` | `3` | 远程云 KMS 调用最大尝试次数（含首次执行） |
| `ateng.boot.encrypt.retry.delay` | `Duration` | `300ms` | 远程云 KMS 调用初始退避重试延迟 |
| `ateng.boot.encrypt.retry.max-delay` | `Duration` | `5s` | 远程云 KMS 调用最大退避重试延迟 |
| `ateng.boot.encrypt.retry.multiplier` | `Double` | `2.0` | 指数退避乘数 |
| `ateng.boot.encrypt.retry.jitter` | `Boolean` | `true` | 是否启用 Full Jitter 抖动打散重试洪峰 |

### 10.2 样例工程 Controller 快速索引

| 业务领域 | Controller 类 | 根路由路径 | 核心体验动作 |
| :--- | :--- | :--- | :--- |
| **透明解密呈现** | `EncryptDecryptionController` | `/sample/encrypt/decrypt` | `GET /secrets`, `GET /property` |
| **编程式对称门面** | `EncryptCryptoController` | `/sample/encrypt/crypto` | `POST /encrypt`, `POST /decrypt`, `POST /encrypt-custom`, `POST /encrypt-key`, `POST /encrypt-raw-key`, `POST /decrypt-key`, `POST /encrypt-raw`, `POST /decrypt-safe` |
| **多 Key 路由轮换** | `EncryptMultiKeyController` | `/sample/encrypt/multi-key` | `GET /keys`, `POST /decrypt-routed`, `POST /rotate` |
| **生产防倒灌守卫** | `EncryptGuardController` | `/sample/encrypt/guard` | `POST /refresh`, `POST /simulate-ingress`, `GET /rules` |
| **沙箱就地智能回写** | `EncryptInPlaceRewriteController` | `/sample/encrypt/rewrite` | `POST /encrypt-yaml`, `POST /decrypt-yaml`, `POST /encrypt-properties`, `POST /decrypt-properties` |
