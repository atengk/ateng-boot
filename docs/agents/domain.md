# 领域文档与架构决策 (Domain Docs)

工程技能在探索和修改本多模块代码库时读取和维护领域文档的准则。

## 探索代码库前必须读取

1. **全局上下文映射入口 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)**：定义全仓库所有子模块的限界上下文职责、核心门面与上下游依赖拓扑。本项目遵循多上下文（Multi-Context）架构，**根目录严格且唯一维护 `CONTEXT-MAP.md`，严禁在根目录创建单体 `CONTEXT.md`**。
2. **目标子模块词汇表 `ateng-boot-<module>/CONTEXT.md`**：处理具体模块时必须优先读取并维护该模块目录下的 `CONTEXT.md`。严格恪守**“纯领域词汇表（Glossary）”**定位——仅定义标准领域术语与 `_Avoid_` 禁用反模式词汇，严禁堆砌实现代码或设计方案。
3. **系统级架构决策记录 [`docs/adr/`](../adr/README.md)**：查阅跨模块架构规范与各模块的歷史技术选型。若上述文件在孵化新模块时尚未创建，请静默继续并在决策收敛时按需创建。

## 目录布局（多上下文结构 Multi-Context）

```text
ateng-boot/
├── CONTEXT-MAP.md                              # 全局限界上下文拓扑与关系唯一入口
├── docs/adr/                                   # 系统级架构决策记录 (ADR-0001 ~ 0094+)
├── ateng-boot-bom/                             # 依赖版本统管中心 (CONTEXT.md)
├── ateng-boot-core/                            # 核心底座与通用原语 (CONTEXT.md)
├── ateng-boot-<module>/                        # 各业务与基础设施 Starter (100% 标配独立 CONTEXT.md)
├── ateng-boot-all/                             # 核心 Starter 聚合包 (CONTEXT.md)
└── ateng-boot-samples/                         # 二级聚合验证矩阵 (CONTEXT.md)
    ├── ateng-boot-sample-<module>/             # 与各 Starter 1:1 严格对应的独立体验沙箱
    └── ateng-boot-sample-smoke-test/           # 全家桶装配冒烟门禁
```

## 术语一致性与 ADR 治理准则

- **术语强对齐**：在命名类名、门面方法、配置属性、Issue 标题与测试用例时，严格复用目标模块 `CONTEXT.md` 已定义的标准术语，禁止引入 `_Avoid_` 列表中的同义异名。
- **实时内联更新**：当新增或变更领域概念时，必须在同次变更中同步更新对应模块的 `CONTEXT.md` 与根目录 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
- **ADR 膨胀防线与冲突显式声明**：仅在孵化全新中间件模块、重大协议重构或存在真实技术权衡（Hard to reverse + Surprising without context + Real trade-off）时新建独立 ADR；存量模块演进优先在既有归属 ADR 中增量修订。若新方案与既有 ADR 冲突，必须显式声明并同步添加演进注记（Superseding Note），严禁静默覆盖。
