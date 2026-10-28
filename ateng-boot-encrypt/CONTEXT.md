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
