# Issue 跟踪器与工单流转规约 (GitHub Issue Tracker)

本仓库的所有任务工单、缺陷跟踪与特性规范均托管于 GitHub Issues，统一使用官方 `gh` CLI 操作。

## 1. 标准 `gh` CLI 操作命令

- **创建 Issue**：`gh issue create --title "..." -F <body-file>`
- **查看 Issue 详情与评论**：`gh issue view <number> --comments`
- **查询列表**：`gh issue list --state open --json number,title,labels,assignees`（可附加 `--label <label>`）
- **添加评论**：`gh issue comment <number> -F <file>`（单行简短回复可用 `--body "..."`）
- **打标/移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`（标准标签见 [`triage-labels.md`](./triage-labels.md)）
- **认领指派**：`gh issue edit <number> --add-assignee "@me"`
- **关闭 Issue**：`gh issue close <number>`

## 2. 跨平台参数避坑防线 (Windows PowerShell)

1. **特殊字符强制双引号包裹**：在 PowerShell 下执行 `gh` 命令时，含 `@` 等特殊字符的参数必须显式使用双引号包裹（如 `--add-assignee "@me"`），防止 PowerShell 将 `@me` 解析为 Splatting 表达式报错。
2. **长文本强制文件传入 (`-F <file>`)**：创建多行 Issue 正文或回写验收清单时，强制先写入临时文件（存放于会话 Scratch 隔离目录或系统临时目录，严禁污染项目源码树）再通过 `-F <file>` 传入，彻底杜绝命令行多行转义截断与编码乱码。

## 3. 五阶段工单生命周期与 Git 授权边界 (Ticket Lifecycle & Explicit Git Gate)

执行 GitHub Issue 任务时遵循以下五阶段状态机，并严格服从 [`AGENTS.md`](../../AGENTS.md) 第 5 节的**“显式两级 Git 授权门禁”**：

### 第一部分：研发与验证阶段（默认自动执行并停留在工作区）
1. **阶段 1（认领）**：执行 `gh issue view <n> --comments` 研读需求，执行 `gh issue edit <n> --add-assignee "@me"` 指派当前开发者，确认当前处于 `dev` 分支。
2. **阶段 2（TDD 与 Sample-First 实现）**：
   - 遵循 [`AGENTS.md`](../../AGENTS.md) 第 2、3 节完成核心源码与全自洽对抗单测；
   - 遵循 [`documentation-specification.md`](./documentation-specification.md) 在 `ateng-boot-sample-<module>` 中补齐多 Controller 接口与双模端到端测试并跑通全绿，随后 1:1 逆向提取更新 Starter `README.md` 与 Sample 体验卡 `README.md`。
3. **阶段 3（准出测试与双轴审查）**：
   - 执行阶梯二定向测试 `mvn test -pl <changed-module>,ateng-boot-samples/ateng-boot-sample-<module> -am -T 1C -nsu -B`；
   - 对照 [`documentation-specification.md`](./documentation-specification.md) 第 4 节《12 项自检清单》并执行 `/code-review` 双轴审查直至全绿。
   - **🚨 默认停止边界（Default Stop Criterion）**：若用户在会话中未明确下达“提交/推送/关闭工单”指令，Agent 完成阶段 3 后**必须停留在工作区向用户汇报审查结果**，严禁擅自执行阶段 4~5。

### 第二部分：提交、监控与关单阶段（仅在收到用户显式指令或全自动闭环授权时触发）
4. **阶段 4（受控提交与推送）**：收到明确提交/推送指令后，使用精准文件相对路径执行 `bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项>"] -c <n> [-p] -y`。
5. **阶段 5（CI 守护与工单闭环）**：推送后通过 `gh run list -L 1` 与 `gh run watch <run-id>` 持续监控远端流水线直至 100% 全绿；随后通过 `gh issue comment <n> -F <report-file>` 回写验收报告与提交哈希，并执行 `gh issue close <n>` 关闭工单。

## 4. 技能联动与寻路器约定 (Skill & Wayfinding Conventions)

- **PR 作为外部需求分诊入口**：`关闭 (no)`。
- **寻路器（`/wayfinder`）映射**：
  - **导航图 Issue（Map）**：带 `wayfinder:map` 标签的统领 Issue（`gh issue create --label wayfinder:map`），承载全局笔记、已决方案与待探迷雾；
  - **子工单（Child Ticket）**：作为 GitHub Sub-issue 关联至导航图（或在正文顶部标注 `Part of #<map>`），标签格式为 `wayfinder:<type>`（`research` / `prototype` / `grilling` / `task`）；
  - **前置阻塞（Blocked By）**：使用原生 Issue 依赖或正文顶部 `Blocked by: #<n>` 标记，前置工单全部关闭后自动解锁。
