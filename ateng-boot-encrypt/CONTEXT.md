# Encrypt 配置加解密与运行时安全底座

面向企业级应用、微服务集群与信创高合规场景，提供纯 JDK 原生零臃肿、全属性源动态透明解密、Nacos 启动时序穿透、公私钥职责隔离、生产防明文误提交及 Actuator 运行时防窥探的生产就绪配置加密基础设施。

## Language

**Config Decryptor (配置解密引擎)**:
实现对称或非对称解密算法、负责将密文还原为真实明文的核心算法组件。
_Avoid_: 解密器, 加密工具, DecryptService, PasswordDecoder

**Config Encryptor (配置加密引擎)**:
负责将明文凭据加密为标准密文格式的离线或构建期核心组件。
_Avoid_: 加密器, EncodeUtil, CipherEncoder

**Pre-Resolver Hook (配置数据瞬态预解析守卫)**:
在 Spring Boot 3 `ConfigData` 体系解析 `spring.config.import` 之前介入并包装原始配置源的早期生命周期守卫，用于彻底杜绝 Nacos 等配置中心客户端因未解密密码导致的启动 403 冲突。
_Avoid_: 早期监听器, 预加载器, NacosFixer, BootHook

**Encrypt Property Source Wrapper (加密属性源透明装饰器)**:
对 Spring `PropertySource` 进行只读动态代理的装饰器，拦截 `getProperty(name)` 调用并执行按需解密与并发安全缓存。
_Avoid_: 属性代理, 动态PropertySource, EncryptMap, CustomPropertySource

**Enforce Encryption Policy (生产强制加密门禁)**:
在指定生产环境下，针对包含敏感关键字的配置项进行前置合规检测，若检测到明文凭据则立即触发 Fail-Fast 阻断启动的安全防御策略。
_Avoid_: 密码拦截器, 明文检测器, SecurityCheckFilter

**Sanitizing Guard (运行时端点安全脱敏守卫)**:
基于 Spring Boot `SanitizingFunction` 规范，对所有曾被加密的配置项在 Actuator `/actuator/env` 等端点进行物理掩码的运行时防御组件。
_Avoid_: 端点过滤器, 脱敏器, ActuatorMasker

**Key Separation (公私钥职责隔离)**:
在非对称加密场景下，开发环境与 CI/CD 仅持有公钥用于生成密文，解密私钥仅在生产环境受控注入的安全管理范式。
_Avoid_: 密钥分离, 双私钥, 权限划分

**Multi-Key Registry (多密钥命名注册表)**:
支持多个命名密钥共存、通过 KeyId 动态路由解密引擎，以支持业务分域隔离与密钥平滑轮换的注册管理组件。
_Avoid_: 密钥池, 密钥管理器, KeyMap

**In-Place Rewriter (整文件就地加解密回写器)**:
在保持 YAML 注释、缩进与多行结构完全不变的前提下，自动扫描并批量就地替换敏感明文为密文并生成 `.bak` 备份的离线运维工具。
_Avoid_: 批量修改脚本, 文件替换器, YamlFormatter

**Lazy Decryption (按需懒解密)**:
仅在业务组件或 `@Value` 真实请求配置项时才触发解密计算与密钥校验的按需评估机制，为 Spring 切片测试与模块化单测提供最大化宽容度。
_Avoid_: 延迟解密, 惰性求值, 动态加载

**Plaintext Ingress Guard (动态防明文倒灌守卫)**:
在配置中心（Nacos/Apollo）运行时热推送配置时，持续执行强制加密校验、拒绝生产环境明文推送并保护有效密文不被覆盖的动态安全守卫。
_Avoid_: 动态校验器, Nacos热推拦截器, IngressFilter

**Multiline Secret Hygiene (多行大文本机密保真)**:
支持自动容忍并清洗 YAML 缩进与换行杂质，同时 100% 还原 PEM 证书/私钥格式的非破坏性大文本解密机制。
_Avoid_: 换行处理, 文本转义, PemParser

**Encrypt Template (统一编程式加解密受管门面)**:
Spring 受管的高阶对称加解密门面 Bean，自动整合默认算法、前后缀解析器与多密钥注册表，为业务层提供简单对称的编程式加解密、密文识别与 Token 组装能力。
_Avoid_: 加密服务, EncryptHelper, CryptoManager, CipherBean

**Properties In-Place Rewriter (属性文件就地回写引擎)**:
在保持等号（`=`）两端空格、行尾注释与换行符严格保真的前提下，对标准 `.properties` 配置文件执行就地敏感字段加密与还原的脱机回写引擎。
_Avoid_: 属性替换脚本, PropertiesFormatter, ConfigWriter

**Key Rotated Event (密钥轮换失效事件)**:
在多密钥注册表中新增或平滑轮换密钥时触发的上下文事件与回调，用于原子清空并刷新所有已代理属性源中的解密内存缓存。
_Avoid_: 刷新事件, 密钥变更监听, CacheClearer

**Encrypt Retry Policy (弹性解密退避重试策略)**:
针对外部云 KMS 或硬件加密机 (HSM) 远程调用网络抖动，基于强类型 `Duration` 与最大尝试次数提供生产受控的指数退避重试配置体系。
_Avoid_: 重试拦截器, KmsRetryer, BackoffConfig

**Placeholder Immunity (占位符免疫策略)**:
在执行就地配置文件加密（InPlaceRewriter）时，自动识别形如 `${...}`（环境变量占位符）或 `#{...}`（SpEL 表达式）的动态变量值并严格豁免加密的防御机制，杜绝破坏动态外部注入。
_Avoid_: 变量跳过, 表达式忽略, SpelFilter

**False-Positive Immunity Filter (敏感特征防误伤过滤链)**:
在生产环境开启 `enforce-encrypted: true` 时，基于协议特征（`http://`, `https://`, `classpath:`）、标准日志级别以及显式白名单（`ignoredKeys`）自动放行非凭据字段的三道安全过滤防线。
_Avoid_: 白名单拦截器, URL跳过, FalseAlarmGuard

**Compatible Cipher Hook (兼容加密前缀开关)**:
在密文语法解析中提供受控开关，支持透明解析 Spring Cloud `{cipher}...` 历史前缀的零成本平滑迁移机制。
_Avoid_: 兼容模式, 老系统适配器, LegacyPrefix

**SM2 Interoperable Conversion (国密 ASN.1 DER 双向转译器)**:
在纯 JDK 原生 SM2 密码引擎中，提供标准 C1C3C2 原始拼接字节流与 ASN.1 DER 结构化序列之间的双向无损转译原语，并支持解密时首字节 `0x30` 自动探测解包。
_Avoid_: DER编解码器, 格式转换器, Asn1Util

**Encrypt Domain Controller (配置加解密领域控制器)**:
按业务能力领域将配置透明解密、受管编程式加解密、多 Key 路由与轮换、安全门禁及沙箱就地回写能力暴露为 HTTP 交互体验端的高内聚接入层控制器。
_Avoid_: 加密接口, EncryptApi, CryptoHandler, EncryptManagerController

**Sandbox In-Place Rewrite (沙箱就地回写探查)**:
在操作系统受控临时沙箱目录（`java.io.tmpdir`）中安全创建瞬态配置文件，模拟 YAML 与 Properties 物理文件就地加密、还原、Diff 对比与 `.bak` 备份生成，并在操作后确定性清理的交互探查机制。
_Avoid_: 内存回写, 伪回写, 文件替换测试, DirectDiskRewrite

**Dynamic Ingress Simulation (动态明文倒灌模拟)**:
通过模拟配置中心（Nacos/Apollo）动态推送裸明文敏感配置，验证生产强制加密门禁与防明文倒灌守卫刚性拦截阻断的合规自验机制。
_Avoid_: 热推测试, 倒灌探针, IngressTester

**Multi-Key Routing Inspection (多Key路由探查)**:
动态探查命名密钥映射表状态、多 Key 别名列表，并自验携带 `ENC[keyId](...)` 标签的密文精准路由解密与密钥轮换缓存原子置换的运维机制。
_Avoid_: 密钥查看器, KeyInfo, KeyLister



