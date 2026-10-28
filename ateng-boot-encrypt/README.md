# ateng-boot-encrypt 配置安全加解密套件

> 零臃肿纯原生 Spring Boot 3 生产级配置加解密底座：支持现代高强 AES-GCM / 纯原生国密 SM2/SM4 / 非对称 RSA、动态多 Key 轮换路由、Actuator 物理脱敏防泄露、生产环境明文倒灌拦截、配置中心两阶段预解密 Hook、整文件就地无损智能回写与脱机安全运维 CLI 工具箱。

---

## 1. 模块定位与技术基线

`ateng-boot-encrypt` 专职解决企业级应用中数据库连接密码、第三方 API 令牌、私钥证书等敏感配置在代码库、YAML 配置文件及远端配置中心中的明文暴露风险。

### 核心设计原则
- **零臃肿与底座收敛 (Zero-Bloat & Core-First)**：100% 依托 JDK 17+ 标准库与 Spring 6 原生能力，国密 SM2/SM3/SM4 纯算法内聚实现，**坚决不引入 BouncyCastle、Jasypt 等重型第三方传递依赖**；
- **透明无感 (Transparent & Ergonomic)**：基于 Spring Boot `EnvironmentPostProcessor` 与 `ConfigData` 机制，在配置加载阶段透明懒解密，业务代码、`@ConfigurationProperties` 与 `@Value` 完全无感；
- **现代安全标配 (Modern Cryptography Defaults)**：默认采用具备 AEAD（Authenticated Encryption with Associated Data）完整性认证防篡改的 **AES-256-GCM** 算法，自动附带 12 字节安全随机 IV，彻底抛弃过时不安全的 ECB/DES 算法；
- **多 Key 轮换与隔离 (Multi-Key Routing & Isolation)**：独创 `ENC[keyId](...)` 语义契约，天然支持多中间件独立密钥隔离与无停机密钥平滑轮换；
- **全生命周期安全闭环 (Full Lifecycle Governance)**：涵盖开发期脱机加解密 CLI、CI/CD 阶段整文件就地回写、配置中心拉取前预解密 Hook、生产环境明文倒灌强制阻断、以及运行期 Actuator 监控物理脱敏。

### Maven 坐标
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-encrypt</artifactId>
</dependency>
```

---

## 2. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **透明配置解密** | 运行时环境配置属性透明懒解密 | `EncryptEnvironmentPostProcessor`, `EncryptPropertySourceWrapper` | `ateng.boot.encrypt.enabled`, `password`, `algorithm`, `prefix`, `suffix`, `fail-on-error` | 拦截全量 `PropertySource`，自动识别 `ENC(...)` 密文并还原为真实明文；支持快速失败 | `EncryptEnvironmentPostProcessorTest.java`, `EncryptSampleApplicationTest.java` |
| **多 Key 动态路由** | 多命名密钥隔离与平滑轮换机制 | `MultiKeyRegistry`, `ENC[keyId](...)` 语法契约 | `ateng.boot.encrypt.keys.<key-id>` | `registerKey(keyId, password)`, `getKeyOrDefault(keyId)`，支持通过密文方括号别名指定独立解密密钥 | `MultiKeyRegistryTest.java`, `EncryptSampleApplicationTest.java` |
| **现代对称加密** | 具备 AEAD 认证防篡改的现代对称算法 | `AesGcmConfigDecryptor`, `AesCbcConfigDecryptor`, `ConfigDecryptor` | `ateng.boot.encrypt.algorithm=AES-GCM` (默认) 或 `AES-CBC` | `AesGcmConfigDecryptor.encrypt(plain, pass)`，支持 12 字节安全 IV 与 GCM AEAD 完整性校验 | `AesGcmConfigDecryptorTest.java`, `AesCbcConfigDecryptorTest.java` |
| **纯原生国密合规** | 零第三方依赖纯原生国密算法套件 | `Sm4ConfigDecryptor`, `Sm2ConfigDecryptor`, `Sm2Engine`, `Sm3Digest`, `ConfigDecryptor` | `ateng.boot.encrypt.algorithm=SM4-CBC`, `SM4-ECB`, `SM2` | 100% 依托原生 JDK 17 实现；支持 SM4-CBC 16 字节 IV 密文加解密，SM2 椭圆曲线非对称加解密与密钥对生成 | `Sm4ConfigDecryptorTest.java`, `Sm2ConfigDecryptorTest.java`, `EncryptSampleApplicationTest.java` |
| **非对称密钥分发** | RSA 非对称加密与权限分域管控 | `RsaConfigDecryptor`, `ConfigDecryptor` | `ateng.boot.encrypt.algorithm=RSA` | `generateKeyPair(size)`, `encodePublicKeyToPem(pubKey)`, `encodePrivateKeyToPem(priKey)`, `encrypt(plain, pemPubKey)`, `decrypt(cipher, pemPriKey)` | `RsaConfigDecryptorTest.java`, `EncryptCliTest.java` |
| **编程式加解密门面** | 统一完备对称加解密工具门面 | `ConfigEncryptor`, `CiphertextParser` | - | `ConfigEncryptor.encrypt(plain, pass, algo)`, `ConfigEncryptor.encrypt(plain, pass)`，`ciphertextParser.hasEncrypted(text)`, `replaceTokens(text, replacer)` | `ConfigEncryptorTest.java`, `CiphertextParserTest.java`, `EncryptScenarioController.java` |
| **弹性退避重试** | 外部网络与云端解密指数退避装饰器 | `RetryingConfigDecryptor`, `ConfigDecryptor` | - | 基于 Core 原生 `RetryUtil`，为云端解密提供指数退避重试，自动排除不可重试的密码学错误（填充错误/Tag校验失败） | `RetryingConfigDecryptorTest.java` |
| **外部 KMS / HSM** | 外部云 KMS 与硬件加密机标准 SPI | `AbstractKmsConfigDecryptor`, `ConfigDecryptor` | `ateng.boot.encrypt.algorithm=KMS` | 抽象基类标准契约，提供 `doDecrypt(ciphertext, keyIdOrPassword)` 扩展点，无缝对接 AWS KMS、阿里云 KMS 与硬件加密机 | `AbstractKmsConfigDecryptorTest.java` |
| **Actuator 物理脱敏** | 监控端点配置属性物理掩码脱敏防泄露 | `SanitizingGuard`, `EncryptSanitizingFunction` | - | 实现 Spring Boot Actuator 的 `SanitizingFunction`，对 `/actuator/env` 等监控端点中携带 `ENC(...)` 密文的敏感属性实施物理掩码为 `******` | `SanitizingGuardTest.java`, `EncryptSampleApplicationTest.java` |
| **生产明文倒灌拦截** | 生产环境未加密敏感配置强制防卫门禁 | `PlaintextIngressGuard`, `EnforceEncryptionPolicy` | `ateng.boot.encrypt.enforce-encrypted`, `sensitive-key-pattern` | 在生产环境下扫描全量 PropertySource，若发现匹配敏感特征的裸明文属性，立即中断应用启动，强制执行密码学治理 | `PlaintextIngressGuardTest.java` |
| **整文件就地智能回写** | 保持注释与格式保真的智能回写器 | `InPlaceRewriter`, `# @encrypt` 指令 | - | `rewriteEncrypt(path)`, `rewriteDecrypt(path)`，逐行解析 YAML/Properties，支持 `# @encrypt[keyId](ALGO)` 行内指令，100% 保持缩进、注释与换行符保真，自动生成 `.bak` 备份 | `InPlaceRewriterTest.java`, `EncryptSampleApplicationTest.java` |
| **脱机安全运维 CLI** | 终端脱机安全加解密与密钥生成工具箱 | `EncryptCli` | - | `EncryptCli.main(args)`，支持命令行批处理（`--encrypt`, `--decrypt`, `--keygen`）与交互式控制台菜单，支持脱机生成 RSA 与 SM2 密钥对与单值加解密 | `EncryptCliTest.java` |
| **多行大文本规范** | 多行大文本与 PEM 证书单行化编解码 | `MultilineSecretFormatter` | - | `toSingleLine(pem)`, `toMultiLine(singleLinePem)`，将多行 RSA 私钥/公钥与 PEM 证书规范编码为单行，适配配置中心与 YAML 属性规范 | `MultilineSecretFormatterTest.java` |
| **配置中心预解密 Hook** | Nacos / Apollo 远端拉取前凭据预解密 | `EncryptConfigDataLocationResolver`, `EncryptConfigDataEnvironmentUpdateListener` | - | Spring Boot 2.4+ ConfigData 规范接入，在远端配置中心拉取配置前完成 `spring.cloud.nacos.config.password` 等连接凭据的优先解密，规避 403 权限拒绝 | `EncryptConfigDataLocationResolverTest.java` |

---

## 3. 最小起步体验三板斧

### 第一板斧：引入依赖
在业务模块 `pom.xml` 中引入 Starter（版本由根工程 BOM 统管）：
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-encrypt</artifactId>
</dependency>
```

### 第二板斧：配置文件声明密文并注入主密钥
在 `application.yml` 中声明已加密的配置项：
```yaml
ateng:
  boot:
    encrypt:
      enabled: true
      algorithm: AES-GCM # 默认现代高强对称加密算法

# 业务敏感属性（由 ENC(...) 标记包裹）
sample:
  secret:
    database-password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)
```

> [!IMPORTANT]
> **主密钥注入铁律**：严禁将解密主密钥（`ateng.boot.encrypt.password`）硬编码在任何由版本控制纳管的本地 `application.yml` 中！必须通过环境变量或容器启动参数动态注入：
> ```bash
> # 推荐生产注入方式：操作系统环境变量
> export ATENG_BOOT_ENCRYPT_PASSWORD="SampleMasterSecretKey2026!"
> 
> # 或 JVM 启动参数
> java -Dateng.boot.encrypt.password=SampleMasterSecretKey2026! -jar app.jar
> ```

### 第三板斧：业务代码无感透明使用
业务实体类与 `@ConfigurationProperties` 强类型模型完全无感知，直接使用明文：
```java
package io.github.atengk.boot.sample.encrypt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sample.secret")
public class SampleSecretProperties {

    /**
     * 数据库连接密码（运行时由 Starter 自动透明解密）
     */
    private String databasePassword;
}
```

Controller 传输边界显式契约返回真实明文（逆向提取自已跑通的真实样例）：
```java
@GetMapping("/secrets")
public Result<SampleSecretVO> getDecryptedSecrets() {
    SampleSecretVO vo = SampleSecretVO.builder()
            .databasePassword(secretProperties.getDatabasePassword())
            .build();
    return Result.success(vo);
}
```

请求响应结果验证：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "databasePassword": "mysqlRootPass2026!"
  },
  "success": true,
  "traceId": "7c5e13d987a048a8b1a830b80ef92315"
}
```

---

## 4. 高级密码学引擎与纯原生国密合规

`ateng-boot-encrypt` 彻底摒弃传统方案中引入外部沉重依赖的做法，所有算法 100% 依托 JDK 17 原生能力与纯算法数学建模。

### 4.1 现代高强 AES-GCM（默认引擎）
- **算法模式**：`AES/GCM/NoPadding`（256 位密钥派生，12 字节强随机 IV，128 位认证 Tag）；
- **AEAD 认证防篡改**：不仅保护机密性，还能在解密阶段自动检测密文与附加验证数据是否遭受恶意篡改，校验失败直接抛出异常快速失败；
- **编程式调用**：
  ```java
  // 1. 快速加密（密文前 12 字节为随机 IV，Base64 封装）
  String cipher = AesGcmConfigDecryptor.encrypt("mysqlRootPass2026!", "SampleMasterSecretKey2026!");
  
  // 2. 快速解密
  String plain = new AesGcmConfigDecryptor().decrypt(cipher, "SampleMasterSecretKey2026!");
  ```

### 4.2 纯原生国密 SM4（商用密码合规）
- **算法模式**：支持 `SM4-CBC`（标配 16 字节随机 IV，PKCS5Padding）与 `SM4-ECB`；
- **零依赖合规**：纯原生 Java 实现，无需配置或挂载 BouncyCastle Provider，彻底杜绝 JVM 模块化和安全策略限制问题；
- **密文语法**：在密文中显式标明算法标识 `ENC(SM4-CBC@...)`：
  ```yaml
  sample:
    secret:
      third-party-token: ENC(SM4-CBC@ODPVE+KjssNqGN6yrBqMlXcYvDKQgNYADkGV+HV6vL+/H/Juo4KFpgIuVeRMmIT+)
  ```
- **编程式调用**：
  ```java
  // 国密 SM4-CBC 加密
  String cipher = Sm4ConfigDecryptor.encryptCbc("sm4ApiSecretToken888", "SampleMasterSecretKey2026!");
  // 解密还原
  String plain = new Sm4ConfigDecryptor().decrypt(cipher, "SampleMasterSecretKey2026!");
  ```

### 4.3 纯原生国密 SM2（椭圆曲线非对称密码）
- **算法标准**：GM/T 0003-2012《SM2 椭圆曲线公钥密码算法》；
- **标准曲线**：支持国标推荐 256 位椭圆曲线参数与 SM3 杂凑密码算法，密文输出格式遵循标准 C1C3C2；
- **编程式调用与密钥对生成**：
  ```java
  // 1. 一键生成原生 SM2 密钥对
  Sm2ConfigDecryptor.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
  String privateKeyHex = keyPair.privateKeyHex(); // 32 字节私钥十六进制（生产持有）
  String publicKeyHex = keyPair.publicKeyHex();   // 65 字节非压缩公钥十六进制（公开发布）
  
  // 2. 公钥加密
  String cipher = Sm2ConfigDecryptor.encrypt("sensitiveTokenValue", publicKeyHex);
  
  // 3. 私钥解密
  String plain = new Sm2ConfigDecryptor().decrypt(cipher, privateKeyHex);
  ```

### 4.4 非对称 RSA（公钥加密，私钥解密）
- **分域权限管控**：研发团队、CI/CD 构建流水线仅持有公钥用于配置加密；解密私钥仅存在于生产运行环境，根绝运维与研发之间的密钥泄露风险；
- **密钥生成与 PEM 互转**：
  ```java
  // 1. 生成 2048 位密钥对并导出标准 PEM 格式
  KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(2048);
  String publicPem = RsaConfigDecryptor.encodePublicKeyToPem(keyPair.getPublic());
  String privatePem = RsaConfigDecryptor.encodePrivateKeyToPem(keyPair.getPrivate());
  
  // 2. 公钥加密
  String cipher = RsaConfigDecryptor.encrypt("superSecretPassword", publicPem);
  
  // 3. 私钥解密
  String plain = new RsaConfigDecryptor().decrypt(cipher, privatePem);
  ```

---

## 5. 多 Key 动态路由与多域密钥轮换

在企业级大型系统或多租户架构中，不同中间件（如 MySQL、Redis、RabbitMQ）往往由不同的运维团队负责，或者系统需要定期进行密钥轮换。

### 5.1 方括号别名语法契约：`ENC[keyId](...)`
Starter 统一遵循 `ENC[keyId](ciphertext)` 契约。若未声明 `[keyId]`，则默认使用全局主密钥 `default`：
```yaml
ateng:
  boot:
    encrypt:
      enabled: true
      keys:
        # 命名密钥映射表（KeyId -> Password/PrivateKey）
        redis-cluster: RedisSpecialClusterKey2026!
        mq-broker: MqDedicatedKey2026!

sample:
  secret:
    # 使用默认主密钥解密
    database-password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)
    # 动态路由至 redis-cluster 命名密钥解密
    redis-password: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)
```

### 5.2 编程式注册与无感平滑轮换
通过 `MultiKeyRegistry` 可在运行时编程式动态挂载或更新命名密钥：
```java
@Autowired
private MultiKeyRegistry multiKeyRegistry;

// 1. 动态注册或更新新版本密钥
multiKeyRegistry.registerKey("v2026-q4", "NewQuarterlySecretKey2026!");

// 2. 获取指定密钥或保底主密钥
String activeKey = multiKeyRegistry.getKeyOrDefault("v2026-q4");
```
密钥轮换期间，配置文件可同时存在 `ENC[v2026-q3](...)` 与 `ENC[v2026-q4](...)`，应用平滑重启或热重载，无需全量同步切换。

---

## 6. Actuator 监控脱敏与生产明文倒灌拦截

### 6.1 Actuator 运行时物理脱敏防泄露
Spring Boot 默认在暴露 `/actuator/env` 或 `/actuator/configprops` 端点时可能会泄露配置解密后的明文或带 `ENC(...)` 标记的原始密文。

Starter 内置 `SanitizingGuard`（基于 Spring Boot Actuator `SanitizingFunction`）：
- 只要配置属性属于被解密属性或包含 `ENC(...)` 标记，监控端点一律在物理层输出恒定掩码：
  ```json
  {
    "name": "sample.secret.database-password",
    "value": "******",
    "origin": "class path resource [application.yml] - 27:24"
  }
  ```
- **内存真明文与输出脱敏绝不冲突**：业务 Service 与实体对象在内存中始终保持 100% 原始真实明文，而在对外监控出口执行不可逆物理脱敏，彻底杜绝数据外泄。

### 6.2 生产环境明文倒灌拦截门禁 (`PlaintextIngressGuard`)
在生产部署流程中，防止开发人员或运维人员疏忽将明文密码直接推送到生产环境：
```yaml
ateng:
  boot:
    encrypt:
      enforce-encrypted: true # 生产环境强行开启防倒灌校验
      sensitive-key-pattern: ".*password.*|.*secret.*|.*token.*|.*private-key.*"
```
**防御行为**：在 Spring Boot 启动阶段自动扫描全量外部配置源，若发现匹配正则特征的敏感键仍为未加密裸明文，直接拦截抛出 `ConfigSecurityException` 并阻断容器启动，实现生产安全红牌门禁。

---

## 7. 生产扩展与弹性接管

### 7.1 自定义解密器与外部云 KMS / HSM 逃生舱
当企业级生产环境需对接 AWS KMS、阿里云 KMS、腾讯云 KMS 或 PCI-E 硬件加密机时，开发者只需继承 `AbstractKmsConfigDecryptor` 或实现 `ConfigDecryptor` SPI：

```java
package io.github.atengk.boot.sample.encrypt.extension;

import io.github.atengk.boot.encrypt.decryptor.AbstractKmsConfigDecryptor;
import org.springframework.stereotype.Component;

/**
 * 自定义企业外部云 KMS 解密器
 */
@Component
public class CustomAliyunKmsConfigDecryptor extends AbstractKmsConfigDecryptor {

    @Override
    protected String doDecrypt(String ciphertext, String keyIdOrPassword) throws Exception {
        // 调用企业统一 KMS SDK 或内网 RPC 解密服务
        return kmsClient.decrypt(ciphertext, keyIdOrPassword);
    }
}
```

配置文件中声明算法为 `KMS`：
```yaml
datasource:
  password: ENC(KMS@AQICAHjW3...==)
```

### 7.2 外部网络抖动弹性指数退避 (`RetryingConfigDecryptor`)
对接远程云 KMS 或网络解密服务时，网络抖动可能导致服务启动瞬时失败。通过包装 `RetryingConfigDecryptor` 赋予其工业级弹性退避容灾能力：

```java
ConfigDecryptor kmsDecryptor = new CustomAliyunKmsConfigDecryptor();

// 自动挂载基于 ateng-boot-core 的 RetryUtil 原生指数退避重试装饰器
ConfigDecryptor resilientDecryptor = new RetryingConfigDecryptor(
        kmsDecryptor,
        RetryUtil.RetryConfig.builder()
                .maxAttempts(3)
                .delay(Duration.ofMillis(100))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(5))
                .jitter(true) // 启用 Full Jitter 打散重试洪峰
                .retryPredicate(RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE)
                .build()
);
```

> [!NOTE]
> `DEFAULT_RETRYABLE_PREDICATE` 会自动递归分析因果异常链，若属于不可重试的密码学错误（如 `BadPaddingException`、`AEADBadTagException`、`InvalidKeyException` 等密钥或密文损坏），立即中断并快速失败，绝不进行无意义的盲目重试。

### 7.3 Nacos / Apollo 远端配置解析预拦截 (`PreResolverHook`)
在 Spring Boot 2.4+ 的 `ConfigData` 加载机制下，若 Nacos / Apollo 连接配置自身的用户名和密码也被加密（例如 `spring.cloud.nacos.config.password=ENC(...)`），常规的 `EnvironmentPostProcessor` 会因介入过晚而导致客户端拉取远端配置时抛出 403 权限拒绝。

Starter 内置 `EncryptConfigDataLocationResolver` 与 `EncryptConfigDataEnvironmentUpdateListener`，在配置中心拉取远端资源前的极早期阶段自动完成解密预拦截，彻底消除配置中心连接 403 痛点。

---

## 8. 整文件就地智能回写与脱机安全运维 CLI

### 8.1 配置文件整文件就地智能加解密回写器 (`InPlaceRewriter`)
传统加解密工具在重写 YAML/Properties 文件时，往往会打乱原有配置文件的缩进、破坏注释行并丢失空白换行符。

`InPlaceRewriter` 采用逐行语义扫描算法，能够做到：
1. **100% 格式与注释保真**：完美保留原有注释、缩进空白以及平台中立换行符（CRLF / LF）；
2. **支持智能自动识别与显式指令**：
   - 自动识别匹配敏感正则的字段进行就地加密；
   - 支持显式行内/行前指令：`# @encrypt`、`# @encrypt[keyId]`、`# @encrypt(SM4-CBC)`、`# @encrypt[keyId](RSA)`；
3. **确定性自动备份**：执行就地重写前自动在同级目录生成 `.bak` 备份文件，确保运维操作 100% 可回滚；
4. **对称完备性**：支持就地加密 `rewriteEncrypt` 与就地逆向解密还原 `rewriteDecrypt`。

```java
// 编程式就地重写加密
InPlaceRewriter rewriter = new InPlaceRewriter("SampleMasterSecretKey2026!", EncryptAlgorithm.AES_GCM);
int encryptedCount = rewriter.rewriteEncrypt(Path.of("src/main/resources/application.yml"));

// 就地逆向解密还原明文
int decryptedCount = rewriter.rewriteDecrypt(Path.of("src/main/resources/application.yml"));
```

### 8.2 脱机安全运维终端 CLI 工具箱 (`EncryptCli`)
纯原生 JDK 实现，内置脱机终端菜单与批处理能力，无需启动任何 Spring 容器即可完成生产密钥生成与密文运算：

#### 交互式菜单模式
直接执行入口类进入运维控制台：
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

### 8.3 多行大文本与 PEM 证书单行化编解码 (`MultilineSecretFormatter`)
在配置中心或 YAML 文件中存储非对称公私钥或多行 PEM 证书时，多行换行往往会导致缩进错乱。

`MultilineSecretFormatter` 提供纯原生单行化与还原转换：
```java
// 多行 PEM 证书转单行（安全转义换行符并保留头尾）
String singleLine = MultilineSecretFormatter.toSingleLine(pemPrivateKey);

// 单行字符串还原为标准 RFC 规范的多行 PEM 证书
String standardPem = MultilineSecretFormatter.toMultiLine(singleLine);
```

---

## 9. 核心生产边界与非功能语义

1. **确定性初始化时序与引导规范**：
   - `EncryptEnvironmentPostProcessor` 在 Spring Boot 最早期的 `EnvironmentPostProcessor` 阶段介入，早于所有业务 `@Configuration` 与 Bean 初始化；
   - 遵循 Spring Boot 3 规范，`EnvironmentPostProcessor` 注册于 `META-INF/spring.factories`，自动装配类注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；
   - 替换底层属性源后严格调用 `ConfigurationPropertySources.attach(environment)`，确保 `@ConfigurationProperties` 强类型绑定实时感知解密代理。
2. **Spring Boot 测试环境与 MapPropertySource 强转防御**：
   - `EncryptPropertySourceWrapper` 直接继承自 `MapPropertySource`，彻底根除 Spring Boot 测试框架在 `SpringBootTestRandomPortEnvironmentPostProcessor` 中对属性源进行 `(MapPropertySource)` 强制类型转换时引发的 `ClassCastException`。
3. **跨平台换行符保真机制**：
   - `InPlaceRewriter` 在读取目标配置文件时自动探测源文件的换行符规范（CRLF 或 LF）并在写回时严格保留，杜绝 Windows 与 Linux 跨环境协作时 Git Diff 产生全文件换行符污染。
4. **内存真明文与不可倒灌不变量**：
   - 业务层在运行时内存中获取的始终为 100% 原始真实明文，免除任何业务代码侵入；
   - 只有在向外暴露（Actuator 监控端点）或文件写回时进行物理脱敏或密码学重写，不可逆防护。
