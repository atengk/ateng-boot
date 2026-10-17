# 8. 自动化发版闭环、多维变更日志与常青文档架构决策

## 背景与痛点

在项目完成 `v0.0.1` 与 `v0.0.2` 发布后，全链路发版复盘暴露了三项关键工程体验与规范治理痛点：

1. **发布日志中英割裂与格式冗余**：
   - GitHub Actions 中调用 `orhun/git-cliff-action@v4` 时未显式传入隐藏配置文件 `.cliff.toml` 的路径，导致其降级为内置默认英文模板（双语分类与作者链接丢失）；
   - 同时因误启 `softprops/action-gh-release@v3` 的 `generate_release_notes: true`，导致 GitHub 原生 Release Notes（`What's Changed` 等）被强行拼接在下方，造成中英混杂与双重冗余；
2. **文档版本硬编码与维护负担**：
   - 门面文档 `README.md` 中的接入依赖若写死固定版本（如 `0.0.2`），每次发版都必须由人工或脚本对 Markdown 文件进行物理字符串替换；
   - 试图在 XML 内部插入机器注释（如 `<version><!-- ATENG-VERSION -->0.0.2<!-- /ATENG-VERSION --></version>`）会严重破坏用户的直接复制体验，并在 Git 历史中产生大量针对 README 的噪声提交；
3. **主干开发版本与发布标签冲突**：
   - 发版打 Tag（如 `v0.0.3`）需要 POM 版本为正式版，而发版完成后主干分支若不及时推进至下一代快照（`SNAPSHOT`），容易导致本地日常开发与已发布正式构件发生版本碰撞。

---

## 核心架构与工程决策

### 1. 规范化 GitHub Releases 渲染与单一权威源 (Single Source of Truth)

- **显式指定配置文件**：在 `.github/workflows/release.yml` 的 `git-cliff` 步骤中显式声明：
  ```yaml
  config: ${{ hashFiles('.cliff.toml') != '' && '.cliff.toml' || '' }}
  ```
  确保 100% 加载根目录 `.cliff.toml` 模板，产出标准中英双语分类（`🐛 缺陷修复 (Bug Fixes)`、`🚀 新特性 (Features)` 等），并自动关联作者与 Commit/PR 直达链接；
- **关闭原生生成**：将 `generate_release_notes` 显式设为 `false`，杜绝内容冗余拼接；
- **统一权威源与零冗余文件**：以 GitHub Releases 页面作为项目唯一动态权威发布日志源头；代码库根目录不持久化任何冗余静态 `CHANGELOG.md` 文件，发版脚本亦无需在本地安装 Rust/git-cliff 运行日志生成，彻底解耦本地开发环境。

### 2. 复制即用具体版本与原生 sed -i 原地自驱更新 (Copy-Ready Concrete Version & In-Place sed)

- **终端体验第一 (Copy-Paste-Run)**：门面文档 `README.md` 快速接入指南坚持展示纯净具体的真实版本号（如 `<version>0.0.2</version>`），彻底避免 `${...}` 占位符导致新手粘贴至本地 POM 报红、强迫跳转查版本的体验断层；
- **拒绝 XML 机器注释污染**：严禁在面向开发者的代码块中插入 `<!-- ATENG-VERSION -->` 等机器锚点注释，保持代码展示绝对原生清爽；
- **原生 sed -i 原地流式自驱更新**：在 `scripts/release.sh` 中利用原生 `sed -i`（配合探测 `sed --version` 自适应适配 GNU sed 与 macOS BSD sed）执行原地修改：
  ```bash
  sed -i -E "/<artifactId>ateng-boot-(all|bom)<\/artifactId>/{n;s|<version>[^<]+</version>|<version>${RAW_VERSION}</version>|}" README.md
  ```
  相比传统 POSIX `awk` 必须经由 `> README.md.tmp && mv README.md.tmp README.md` 临时文件中转，`sed -i` 通过 `n;`（读取并前进至下一行）在底层实现直接原地原子修改，**零临时文件、零外部依赖、零残留风险**；
- **发版原子性保证**：发版时由脚本将更新后的 POM 与 `README.md` 一并精准安全暂存，随发版 Tag 一次性原子推送，保证发布的 Git 标签与文档说明绝对同步。

### 3. 主干单一版本流与发版提交 `[skip ci]` 降噪 (Single Concrete Version & `[skip ci]` De-noising)

- **告别 SNAPSHOT 快照流困扰**：
  - 传统双态流（先设正式版打 Tag，再自增 `x.x.x-SNAPSHOT`）在现代开源托管（如 GitHub/Sonatype Central Portal）中带来了严重的认知困扰（主干代码与最新发版号不一致）与 CI 资源浪费（主干 push 连续触发两次冗余构建）；
  - 现代主流开源库（如 Spring Boot、MyBatis-Plus、Vite 等）普遍采用**单一正式版本流（Single Concrete Version Flow）**：主干始终停留在当前最新发布的正式版本（如 `0.0.3`），只有在下一次真正发版时才递增为新版本，全流程只产生 1 个干净的发版提交；
- **发版提交智能短路直通 (CI Smart Short-Circuiting)**：
  - **平台机制防御**：实测发现 GitHub Actions 全局规则规定，若某次推送包含 `[skip ci]` 标记，其底层会无差别跳过该次推送所触发的所有 Workflow Run（包括基于 `on: push: tags` 触发的 `Release` 独立发版工作流），导致标签推送后远端流水线被误杀；
  - **内置网关解耦**：发版提交恢复为干净纯粹的规范格式（`chore(release): bump version to vX.Y.Z`，不挂载 `[skip ci]` 标记）；在常规 `ci.yml` 的变更探测网关中内置智能判定：一旦识别到 `chore(release):` 提交，自动将代码构建状态标记为短路直通（秒级盖戳放行并直出结构化报告），跳过耗时的矩阵测试；
  - **收益**：彻底消除双 CI 冗余排队运行，同时 100% 保障 Tag 推送无缝唤醒 `Release` 流水线完成 Central 分发；
- **预发布需求对齐 SemVer 标准**：
  - 若需要发布公开尝鲜或测试构件，统一发布符合语义化规范的预发布版本（如 `0.0.4-rc.1` 或 `0.0.4-beta.1`），Central Portal 官方 100% 允许此类不可变构件，GitHub Releases 会自动标注 `🧪 Pre-release`，杜绝快照版本在公共仓库中的不可控覆盖与安全隐患；
- **全链路 SNAPSHOT 防呆强熔断**：
  - 在本地 `scripts/release.sh` 与 GitHub Actions `.github/workflows/release.yml` 的前置校验中，部署针对包含 `SNAPSHOT` 字符的强规则拦截；一旦触发立即安全熔断退出，阻断误操作并将反馈压缩在 0 秒内。

### 4. 主干发布铁律与双端分支防呆治理 (Trunk-Based Release & Two-Ended Branch Guards)

- **不可变性与单向依赖**：Maven Central Release 构件具备严格的不可变性（发布后绝不可覆盖、修改或撤销）；若允许在特性分支（feature branch）或未经评审的孤立提交随意发版，会导致未经验证或废弃代码污染公共依赖并在全网永久残留；同时非主干打 Tag 会导致版本树谱系断裂与发布日志混乱；
- **单主干收口原则**：在项目当前演进阶段，严格收口为 **单主干驱动发布模型（Trunk-Based Release）**，版本发布必须且只能源自 `main` 主干分支；
- **双端防御与安全熔断机制**：
  - **本地脚本防线**：`scripts/release.sh` 强校验当前分支必须为 `main`（或 `master`），且工作区 100% clean、与远端完全同步，非主干直接拒绝运行并退出；
  - **远端 CI 调度防线**：在 `.github/workflows/release.yml` 中部署双重校验：
    1. 针对 `workflow_dispatch` 网页调度：强制要求触发分支必须为 `main`（`github.ref_name == 'main'`）；
    2. 针对 `push tags` 标签推送：通过 `git merge-base --is-ancestor "$TAG_COMMIT" origin/main` 校验 Tag 指向的提交必须是 `origin/main` 主干的直系祖先，若发现来自未合入的分支或孤立提交，流水线立即安全熔断中断构建。
