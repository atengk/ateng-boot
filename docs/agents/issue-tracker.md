# Issue 跟踪器：GitHub

本仓库的所有 Issue、任务工单与特性规范均直接托管在 GitHub Issues 中。所有操作统一使用 `gh` CLI 执行。

## 操作规约

- **创建 Issue**：`gh issue create --title "..." --body "..."`（多行内容使用 Here-doc）。
- **查看 Issue**：`gh issue view <number> --comments`。
- **查询列表**：`gh issue list --state open --json number,title,body,labels,comments` 并辅以 `--label` 过滤。
- **添加评论**：`gh issue comment <number> --body "..."`。
- **打标/移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **关闭 Issue**：`gh issue close <number> --comment "..."`。

## PR 作为外部需求分诊渠道

**PR 作为需求分诊入口：关闭 (no)。**（若后续希望将外部 PR 纳入分诊队列，可将此项修改为 `yes`）。

## 技能约定

- 当技能提示“发布至 Issue 跟踪器 (publish to the issue tracker)”时：创建 GitHub Issue。
- 当技能提示“抓取相关工单 (fetch the relevant ticket)”时：执行 `gh issue view <number> --comments`。

## 寻路器操作 (Wayfinding operations)

供 `/wayfinder` 技能使用。**导航图 (Map)** 是一个带有 `wayfinder:map` 标签的统领 Issue，**子工单 (Child ticket)** 为其子任务。

- **导航图 Issue**：打上 `wayfinder:map` 标签的统领 Issue，承载“笔记 / 迄今决策 / 迷雾”正文。执行 `gh issue create --label wayfinder:map`。
- **子任务工单**：作为 GitHub Sub-issue 关联至导航图 Issue。若未开启 Sub-issues，则在导航图正文的任务列表中引用并在子工单顶部标注 `Part of #<map>`。标签格式为 `wayfinder:<type>`（如 `research` / `prototype` / `grilling` / `task`）。被认领后指派给当前开发者。
- **依赖阻塞阻断**：使用 GitHub 原生 Issue 依赖能力（`issue_dependencies_summary.blocked_by`），无原生支持时在工单正文顶部标注 `Blocked by: #<n>`。当所有前置阻断工单均关闭时，该工单自动解锁。
- **认领任务**：`gh issue edit <n> --add-assignee @me`。
- **完成任务**：`gh issue comment <n> --body "<answer>" && gh issue close <n>`，并将决策要点追加至导航图正文。
