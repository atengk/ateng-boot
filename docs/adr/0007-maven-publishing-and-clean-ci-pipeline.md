# 7. Maven 中央仓库发布解耦与 CI 纯净化治理决策

## 背景与痛点
在 `v0.0.1` 首次打通 GitHub Actions 统合发版流水线时，暴露了三个关键工程治理与发布效率问题：
1. **CI 悬挂等待过久**：Sonatype Central Portal 在构件通过合法性校验（Validation）后，同步至全球 CDN 节点与镜像索引（Publishing）耗时较长（通常 5~15 分钟），导致 CI Runner 长时间打印 `Waiting until Deployment is published` 并悬挂等待；
2. **非发布模块部署污染**：示例工程 `ateng-boot-samples` 作为自验套件，打包产物被错误纳入部署阶段，导致 staging 因缺失 pom 而报拒绝；此前试图通过插件层硬编码 `excludeArtifacts` 或在 CI 临时降级 Maven 3.9.9 规避，违背了工程纯净化原则；
3. **提交信息缺乏列表式详情承载**：常规小提交通常仅有单行标题，但在聚合提交时无法在正文中清晰罗列每一项细化改动。

## 核心架构与工程决策

### 1. Central 校验门禁解耦 (`<waitUntil>validated</waitUntil>`)
在根 `pom.xml` 的 `central-publishing-maven-plugin` 中，显式将 `<waitUntil>` 策略由 `published` 调整为 `validated`：
- **收益**：CI 只要收到 Sonatype 服务端对 GPG 签名、Javadoc、源码 Jar、Checksum 及 POM 规范的“校验通过 (VALIDATED)”信号即视为发布成功并准出流水线；
- **保障**：Sonatype 官方承诺一旦 Deployment 状态转入 `PUBLISHING`，服务端会自动保证其全球镜像分发与索引建立，无需在 CI 资源中死等。

### 2. 子模块发布排斥与 Central Bundle 双重防御 (`maven.deploy.skip` + `excludeArtifacts`)
针对不向公网分发的示例工程、自验套件（如 `ateng-boot-samples`）：
- **标准属性拦截**：统一在目标子模块自身 `pom.xml` 的 `<properties>` 中声明 `<maven.deploy.skip>true</maven.deploy.skip>`，由 Maven 标准构建生命周期原生拦截默认的部署行为；
- **Central Bundle 反应堆排除**：由于 Sonatype 官方 `central-publishing-maven-plugin` 以 Reactor 反应堆维度扫描打包并上传 Staging Bundle，缺少 POM 封包会导致服务端报校验拒绝（`Bundle has content that does NOT have a .pom file`），因此必须在根插件中协同声明 `<excludeArtifacts><excludeArtifact>ateng-boot-samples</excludeArtifact></excludeArtifacts>`，形成生命周期与 Central 打包双重隔离闭环。

### 3. CI 流水线纯净化与拒绝非标补丁
- 彻底清除在 GitHub Actions 运行阶段通过 `curl` 动态下载解压并临时替换系统 Maven 的非标 Hack 脚本；
- 统一依赖官方推荐的 `actions/setup-java` 受管工具链，保持 CI 工作流定义的声明式、幂等性与高可维护性。

### 4. 提交门面脚本支持列表式变更正文
扩展 `scripts/commit.sh` 门面脚本：
- 支持命令行传递 `-l` / `--item` 选项以多项参数形式罗列变更条目，自动排版为标准 Markdown 列表正文（Body）；
- 交互模式下支持在输入标题后逐条录入变动说明（回车输入下一项，空行即提交）；
- 严格遵循 Conventional Commits 规约，确保 Git Log 与自动化变更日志生成（git-cliff）可追溯、结构化。
