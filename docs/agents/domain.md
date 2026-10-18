# 领域文档与架构决策 (Domain Docs)

工程技能在探索和修改本多模块代码库时读取和消费领域文档的准则。

## 探索代码库前必须读取

- 根目录下的 **`CONTEXT-MAP.md`** —— 各模块上下文的总映射入口，指向各个子模块独立的 `CONTEXT.md`。本项目遵循多上下文架构，**根目录严格且唯一维护 `CONTEXT-MAP.md`，坚决严禁在根目录新建单体 `CONTEXT.md`**。请根据正在处理的模块阅读并维护对应的上下文文档；
- 根目录下的 **`docs/adr/`** 目录 —— 记录跨模块的系统级架构决策（全局 ADR）；
- 涉及具体子模块时，亦须查看该模块目录下的 `docs/adr/`（若存在）—— 记录特定模块的局部决策。

若上述文件尚未创建，**请静默继续，无需主动报错或阻断**。这些文件将由 `/domain-modeling` 技能在模块边界定义或重大决策收敛时自动增量创建。

## 目录布局（多上下文结构 Multi-context）

```text
ateng-boot/
├── CONTEXT-MAP.md                      # 全局上下文映射总览
├── docs/adr/                           # 跨模块系统级架构决策
├── ateng-boot-bom/
├── ateng-boot-core/
│   ├── CONTEXT.md                      # Core 核心底座领域上下文与术语
│   └── docs/adr/                       # Core 模块专用决策记录
├── ateng-boot-web/
│   ├── CONTEXT.md                      # Web 增强领域上下文与术语
│   └── docs/adr/                       # Web 模块专用决策记录
├── ateng-boot-mybatis-plus/
│   ├── CONTEXT.md                      # MyBatis-Plus 增强领域上下文与术语
│   └── docs/adr/                       # MP 模块专用决策记录
├── ateng-boot-all/
└── ateng-boot-samples/
```

## 术语与词汇表一致性

在命名领域概念（类名、接口名、Issue 标题、重构提议、测试用例）时，严格遵循对应模块 `CONTEXT.md` 中定义的术语，杜绝随意自造同义词。

## 标明 ADR 冲突

若设计或输出与既有的 ADR 存在冲突，必须显式在方案中声明并说明理由，严禁静默覆盖。
