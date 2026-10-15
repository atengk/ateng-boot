# Changelog

All notable changes to this project are documented in [GitHub Releases](https://github.com/atengk/ateng-boot/releases).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.2] - 2026-10-15

### 🐛 缺陷修复 (Bug Fixes)

- **ci**: 修复 Central 发布元数据缺陷并声明式锁定 Maven 环境 (e6286d4)

### 🚜 代码重构 (Refactor)

- **ci**: 优化发布策略与规范文档并配置依赖忽略规则 (f1699ac)

### 📦 构建与依赖 (Build)

- **deps**: Bump the dependencies group with 4 updates in #6 by @dependabot[bot]

### 🎉 新贡献者致谢 (New Contributors)

- 感谢 @dependabot[bot] 提交的首次贡献！

**全量对比**: https://github.com/atengk/ateng-boot/compare/v0.0.1...v0.0.2

---

## [0.0.1] - 2026-10-15

### 🚀 新特性 (Features)

- **samples**: 实现 ateng-boot-all 聚合装配并在 samples 落地 H2 端到端自动化回归门禁 (Closes #5) (b99d86d)
- **mybatis-plus**: 实现 BaseEntity、审计填充器、动态方言插件链与雪花 ID 扩展 (Closes #4) (18f35a5)
- **web**: 实现 TraceFilter 链路追踪、全局异常防御、Jackson 定制与 CORS 装配 (8e40989)
- **core**: 实现 Result 契约、业务异常体系、断言工具与审计/异步上下文 (2b71ea9)

### 🐛 缺陷修复 (Bug Fixes)

- **ci**: 锁定 Maven 3.9.9 规避 3.10+ 在 Central Staging 中遗留 metadata 导致的发布失败 (3e7a52a)
- **ci**: 配置 maven-gpg-plugin loopback 模式以防 CI 环境 No pinentry 签名失败 (a998aa4)
- **ci**: 修复 release.yml 下游发布 Job 因上游被跳过而级联 Skip 的问题 (44bf925)
- **ci**: 修复 release.yml 中 job 级别 if 引用 secrets 错误 (ff9e15d)
- **ci**: 修复 release.yml 标签触发器 Glob 语法错误 (a94d0d9)

### 📝 文档变动 (Documentation)

- **readme**: 添加项目 README 门面文档与 Apache 2.0 开源协议 (2abdc09)

### 🧹 杂项维护 (Chore)

- **build**: 升级依赖基线至Boot 3.3.13与MP 3.5.17并集成开源治理与发版流水线 (316727a)
- **project**: 初始化工程骨架、构建配置与多上下文规范文档 (160807b)

**全量对比**: https://github.com/atengk/ateng-boot/commits/v0.0.1
