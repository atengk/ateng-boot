# ateng-boot-sample-encrypt 配置安全加解密场景自验工程

> 专一 `ateng-boot-encrypt` 配置加解密底座自验：环境属性透明懒解密、多 Key 别名路由、国密 SM4/AES-GCM 多算法协同、Web 契约层呈现、Actuator 监控物理脱敏及整文件就地回写。

---

## 1. 场景痛点与功能定位

在微服务与单体架构中，数据库密码、第三方 API 凭据、私钥证书明文写入配置文件存在极高泄露风险。`ateng-boot-sample-encrypt` 专注于端到端全流程安全加解密自验：
- **纯内存零外部依赖 (Zero External Dependency)**：100% 离线自洽运行，无需外部网络与真实数据库连接，秒级启动与执行；
- **透明懒解密与强类型绑定**：自验 `ENC(...)` 密文在 `EnvironmentPostProcessor` 阶段完成解密，无缝注入 `@ConfigurationProperties` 与业务组件；
- **多 Key 动态路由与多域隔离**：自验 `ENC[redis-cluster](...)` 命名密钥语法契约，实现不同组件独立密钥管理与无感轮换；
- **多算法协同支撑**：自验默认 `AES-GCM`（带 AEAD 认证）与显式国密 `SM4-CBC` 混合配置协同工作；
- **运行时监控物理脱敏**：自验 `/actuator/env` 监控端点物理掩码为 `******`，彻底杜绝数据在运维端点明文外泄；
- **就地智能回写与格式保真**：自验 `InPlaceRewriter` 对 YAML 文件就地加密与逆向还原，100% 保持原有缩进、注释与换行符。

---

## 2. 场景组件架构与代码全景

工程内聚场景控制器、强类型配置模型与端到端集成测试，100% 覆盖配置加解密核心特性：

| 场景组件 / 控制器 | 覆盖核心门面与特性 | 对应端到端测试 |
| :--- | :--- | :--- |
| `SampleSecretProperties` | `@ConfigurationProperties(prefix = "sample.secret")`, 强类型明文绑定 | `EncryptSampleApplicationTest#testPropertiesTransparentDecryption` |
| `EncryptScenarioController` | `Result<SampleSecretVO>`, `ConfigEncryptor`, `ConfigDecryptor`, `CiphertextParser`, `MultiKeyRegistry` | `EncryptSampleApplicationTest#testWebEndpointSecrets`, `testWebEndpointEncryptAndDecryptTools` |
| `InPlaceRewriter` | 整文件就地智能扫描、`# @encrypt` 指令识别、换行符保真与 `.bak` 备份 | `EncryptSampleApplicationTest#testInPlaceRewriterOnSampleYaml` |
| `SanitizingGuard` | Spring Boot Actuator `SanitizingFunction` 物理脱敏防御 | `EncryptSampleApplicationTest#testActuatorEnvEndpointMasking` |

---

## 3. 核心场景实测代码范式

### 1. 声明式密文配置与透明解密 (`application.yml`)
```yaml
ateng:
  boot:
    encrypt:
      enabled: true
      algorithm: AES-GCM
      keys:
        redis-cluster: RedisSpecialClusterKey2026!

sample:
  secret:
    # 默认 AES-GCM 算法加密
    database-password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)
    # 动态路由至 redis-cluster 命名密钥解密
    redis-password: ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)
    # 显式指定国密 SM4-CBC 算法解密
    third-party-token: ENC(SM4-CBC@ODPVE+KjssNqGN6yrBqMlXcYvDKQgNYADkGV+HV6vL+/H/Juo4KFpgIuVeRMmIT+)
```

在测试或运行时注入主密钥 `ateng.boot.encrypt.password=SampleMasterSecretKey2026!`，配置类透明注入真实明文：
```java
@Test
void testPropertiesTransparentDecryption() {
    assertEquals("mysqlRootPass2026!", secretProperties.getDatabasePassword());
    assertEquals("redisAuthPass2026!", secretProperties.getRedisPassword());
    assertEquals("sm4ApiSecretToken888", secretProperties.getThirdPartyToken());
}
```

### 2. Web 契约层呈现 (`EncryptScenarioController`)
Controller 显式返回 `Result<SampleSecretVO>`：
```java
@GetMapping("/secrets")
public Result<SampleSecretVO> getDecryptedSecrets() {
    SampleSecretVO vo = SampleSecretVO.builder()
            .databasePassword(secretProperties.getDatabasePassword())
            .redisPassword(secretProperties.getRedisPassword())
            .thirdPartyToken(secretProperties.getThirdPartyToken())
            .build();
    return Result.success(vo);
}
```
HTTP GET `/api/encrypt/secrets` 响应结果：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "databasePassword": "mysqlRootPass2026!",
    "redisPassword": "redisAuthPass2026!",
    "thirdPartyToken": "sm4ApiSecretToken888"
  },
  "success": true,
  "traceId": "9b1c7f42e3ec4dfdfcabb67e39c1a67b"
}
```

### 3. Actuator 监控端点物理掩码脱敏防泄露
当访问 `/actuator/env` 监控端点时，无论配置属性是否解密，包含 `ENC(...)` 的敏感配置强制被物理掩码为 `******`：
```json
{
  "name": "sample.secret.database-password",
  "value": "******",
  "origin": "class path resource [application.yml] - 27:24"
}
```

### 4. YAML 整文件就地智能回写与还原 (`InPlaceRewriter`)
```java
// 1. 就地加密 YAML 文件（生成 sample-app.yml.bak 备份并保持注释缩进）
InPlaceRewriter rewriter = new InPlaceRewriter("SampleMasterSecretKey2026!", EncryptAlgorithm.AES_GCM);
int encCount = rewriter.rewriteEncrypt(yamlFilePath);

// 2. 就地逆向解密还原为明文
int decCount = rewriter.rewriteDecrypt(yamlFilePath);
```

---

## 4. 快速验证与自验指南

### 1. 运行端到端集成测试
在根目录下执行阶梯二定向联动测试：
```bash
mvn test -pl ateng-boot-encrypt,ateng-boot-samples/ateng-boot-sample-encrypt -am -T 1C -nsu -B
```

### 2. 本地快速启动体验
```bash
# 1. 编译构建
mvn clean package -pl ateng-boot-samples/ateng-boot-sample-encrypt -am -DskipTests

# 2. 注入主密钥并启动
java -Dateng.boot.encrypt.password=SampleMasterSecretKey2026! \
     -jar ateng-boot-samples/ateng-boot-sample-encrypt/target/ateng-boot-sample-encrypt-1.0.0.jar
```

### 3. 接口调用体验
```bash
# 查询当前解密后的机密配置
curl -X GET http://localhost:8080/api/encrypt/secrets

# 编程式加密体验
curl -X POST http://localhost:8080/api/encrypt/tool/encrypt \
  -H "Content-Type: application/json" \
  -d '{"plaintext":"MyDatabasePassword888","password":"SampleMasterSecretKey2026!","algorithm":"AES_GCM"}'

# 查看 Actuator 监控脱敏输出
curl -X GET http://localhost:8080/actuator/env
```
