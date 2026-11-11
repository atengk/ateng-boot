# ateng-boot-sample-encrypt 配置安全加解密体验工程

> 专注于演示与自验 `ateng-boot-encrypt` 核心特性在真实业务场景下的开箱使用：环境属性透明懒解密、受管编程式加解密、多 Key 别名路由与平滑轮换、生产防明文倒灌守卫、YAML/Properties 沙箱就地无损回写及 Actuator 物理掩码脱敏。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 EncryptSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-encrypt
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线自洽配置，主密钥通过 JVM 参数或环境变量注入，无需任何外部网络或中间件依赖即可秒级启动与执行测试；
- **Local 真实模式**：若需在本地自定义主密钥与命名密钥测试，可在启动命令中附加 `-Dateng.boot.encrypt.password=YourMasterPassword` 或配置系统环境变量 `ATENG_BOOT_ENCRYPT_PASSWORD`，启动后即可通过标准 HTTP/cURL 接口进行全场景端点交互体验。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **业务机密解密呈现** | `EncryptDecryptionController` | `GET /sample/encrypt/decrypt/secrets` | `curl -s "http://localhost:8080/sample/encrypt/decrypt/secrets"` |
| **单值环境属性解密** | `EncryptDecryptionController` | `GET /sample/encrypt/decrypt/property` | `curl -s "http://localhost:8080/sample/encrypt/decrypt/property?propertyName=sample.secret.database-password"` |
| **编程式默认加密** | `EncryptCryptoController` | `POST /sample/encrypt/crypto/encrypt` | `curl -s -X POST "http://localhost:8080/sample/encrypt/crypto/encrypt" -H "Content-Type: application/json" -d '{"plaintext": "mysqlRootPass2026!"}'` |
| **编程式密文解密** | `EncryptCryptoController` | `POST /sample/encrypt/crypto/decrypt` | `curl -s -X POST "http://localhost:8080/sample/encrypt/crypto/decrypt" -H "Content-Type: application/json" -d '{"ciphertext": "ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==)"}'` |
| **指定算法与Key加密** | `EncryptCryptoController` | `POST /sample/encrypt/crypto/encrypt-custom` | `curl -s -X POST "http://localhost:8080/sample/encrypt/crypto/encrypt-custom" -H "Content-Type: application/json" -d '{"plaintext": "sm4ApiSecretToken888", "keyId": "redis-cluster", "algorithm": "SM4_CBC"}'` |
| **纯 Base64 裸密文加密** | `EncryptCryptoController` | `POST /sample/encrypt/crypto/encrypt-raw` | `curl -s -X POST "http://localhost:8080/sample/encrypt/crypto/encrypt-raw" -H "Content-Type: application/json" -d '{"plaintext": "nakedPayloadSecret"}'` |
| **容错逃生舱安全解密** | `EncryptCryptoController` | `POST /sample/encrypt/crypto/decrypt-safe` | `curl -s -X POST "http://localhost:8080/sample/encrypt/crypto/decrypt-safe" -H "Content-Type: application/json" -d '{"ciphertext": "corrupted_token_string", "defaultValue": "safeFallbackValue"}'` |
| **多 Key 注册状态探查** | `EncryptMultiKeyController` | `GET /sample/encrypt/multi-key/keys` | `curl -s "http://localhost:8080/sample/encrypt/multi-key/keys"` |
| **多 Key 路由密文解密** | `EncryptMultiKeyController` | `POST /sample/encrypt/multi-key/decrypt-routed` | `curl -s -X POST "http://localhost:8080/sample/encrypt/multi-key/decrypt-routed" -H "Content-Type: application/json" -d '{"token": "ENC[redis-cluster](YbIg1S6F2964mdnyLgtvn1D4SjJ2QqxN8M45CBCnWs0VpgEJ4b5wEZyEKOJyDA==)"}'` |
| **动态注册与轮换密钥** | `EncryptMultiKeyController` | `POST /sample/encrypt/multi-key/rotate` | `curl -s -X POST "http://localhost:8080/sample/encrypt/multi-key/rotate" -H "Content-Type: application/json" -d '{"keyId": "payment-shard", "newPassword": "NewPaymentPass2026!"}'` |
| **全量环境热更新刷新** | `EncryptGuardController` | `POST /sample/encrypt/guard/refresh` | `curl -s -X POST "http://localhost:8080/sample/encrypt/guard/refresh" -H "Content-Type: application/json" -d '{}'` |
| **局部属性热更新刷新** | `EncryptGuardController` | `POST /sample/encrypt/guard/refresh` | `curl -s -X POST "http://localhost:8080/sample/encrypt/guard/refresh" -H "Content-Type: application/json" -d '{"changedKeys": ["sample.secret.database-password"]}'` |
| **模拟敏感明文倒灌拦截** | `EncryptGuardController` | `POST /sample/encrypt/guard/simulate-ingress` | `curl -s -X POST "http://localhost:8080/sample/encrypt/guard/simulate-ingress" -H "Content-Type: application/json" -d '{"sensitiveKey": "custom.dynamic.password", "plaintextValue": "nakedPassword123"}'` |
| **防误伤规则特征探查** | `EncryptGuardController` | `GET /sample/encrypt/guard/rules` | `curl -s "http://localhost:8080/sample/encrypt/guard/rules"` |
| **YAML 沙箱就地加密** | `EncryptInPlaceRewriteController` | `POST /sample/encrypt/rewrite/encrypt-yaml` | `curl -s -X POST "http://localhost:8080/sample/encrypt/rewrite/encrypt-yaml" -H "Content-Type: application/json" -d '{"content": "app:\n  datasource:\n    password: mysqlPass2026! # @encrypt\n", "password": "SampleMasterSecretKey2026!", "algorithm": "AES_GCM"}'` |
| **YAML 沙箱就地解密** | `EncryptInPlaceRewriteController` | `POST /sample/encrypt/rewrite/decrypt-yaml` | `curl -s -X POST "http://localhost:8080/sample/encrypt/rewrite/decrypt-yaml" -H "Content-Type: application/json" -d '{"content": "app:\n  datasource:\n    password: ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==) # @encrypt\n", "password": "SampleMasterSecretKey2026!", "algorithm": "AES_GCM"}'` |
| **Properties 沙箱就地加密** | `EncryptInPlaceRewriteController` | `POST /sample/encrypt/rewrite/encrypt-properties` | `curl -s -X POST "http://localhost:8080/sample/encrypt/rewrite/encrypt-properties" -H "Content-Type: application/json" -d '{"content": "app.datasource.password=mysqlPass2026! # @encrypt\n", "password": "SampleMasterSecretKey2026!", "algorithm": "AES_GCM"}'` |
| **Properties 沙箱就地解密** | `EncryptInPlaceRewriteController` | `POST /sample/encrypt/rewrite/decrypt-properties` | `curl -s -X POST "http://localhost:8080/sample/encrypt/rewrite/decrypt-properties" -H "Content-Type: application/json" -d '{"content": "app.datasource.password=ENC(CZAQmB7bsLk2Z8AWBDWuLuEdXBkLlPSelifEvpUKZF8hnxMYmS5qiJ0QPqAICQ==) # @encrypt\n", "password": "SampleMasterSecretKey2026!", "algorithm": "AES_GCM"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-encrypt,ateng-boot-samples/ateng-boot-sample-encrypt -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-encrypt README](../../ateng-boot-encrypt/README.md)。
