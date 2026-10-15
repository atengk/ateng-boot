# Maven Central 自动化发布配置实战指南

本文档指导维护者如何在 GitHub 仓库中配置所需机密与环境参数，以便利用已内置的 GitHub Actions 流水线，实现向 **Maven Central（Maven 官方中央仓库）** 的全自动打包、GPG 签名、质检与发布。

---

## 整体发版架构

```mermaid
flowchart TD
    A["维护者触发发版<br/>(网页端输入版本号 / Git Tag 推送)"] --> B["GitHub Actions: Release 工作流"]
    B --> C["不可变多模块构建打包<br/>(jar / sources / javadoc)"]
    B --> D["生成 SHA-256 校验和清单并发布 GitHub Release"]
    B --> E{"是否开启 Maven 发布?<br/>vars.ENABLE_MAVEN_PUBLISH == 'true'"}
    E -- 否 / 未配置 --> F["⚡ 安全跳过 Maven 发布<br/>(保留 GitHub Release 产物)"]
    E -- 是 --> G["从 Secrets 提取 GPG 私钥与 Central Token"]
    G --> H["调用 central-publishing-maven-plugin<br/>GPG 签名、上传并以 validated 异步解耦准出"]
    H --> I["🎉 Sonatype 后台自动完成全球镜像同步<br/>(可在 search.maven.org 检索并依赖)"]
```

---

## 第一步：Sonatype Central Portal 账号与命名空间认领

现代 Maven Central 已全面迁移至 **[Sonatype Central Portal](https://central.sonatype.com/)**（取代旧版 JIRA/OSSRH 工单系统），发布速度与验证效率大幅提升。

1. **登录平台**：访问 [https://central.sonatype.com/](https://central.sonatype.com/)，推荐直接使用 **GitHub 账号授权登录**；
2. **认领命名空间 (Namespace)**：
   - 点击右上角头像 -> **View Namespaces** -> **Add Namespace**；
   - 输入本项目对应的 GroupId：`io.github.atengk`；
   - 平台会给出一个唯一的验证码字符串（如 `verification-key: abc123xyz`）；
   - 按照提示在您的 GitHub 个人主页下创建一个名为该验证码的**公开临时仓库**（Verification Repository）；
   - 回到 Portal 页面点击 **Verify Namespace**，系统会在数秒内完成校验并提示 Verified；校验通过后即可删除该临时仓库。
3. **生成发布专属 User Token**：
   - 点击右上角头像 -> **Account** -> 找到 **Generate User Token** 按钮；
   - 点击生成后，页面会弹出一组专门用于 CI/CD 认证的凭据：
     - **Username / Token Name**（如一段字符 ID）
     - **Password / Token Secret**（如一段长哈希字符）
   - **请妥善记录这两项凭据，关闭弹窗后将无法再次查看！**

---

## 第二步：生成 GPG 签名密钥并分发公钥

Maven Central 强制要求所有发布的 Jar、POM 与 Javadoc 包必须附带 `.asc` 签名文件，以防网络劫持与篡改。

### 1. 生成 GPG 密钥对
在本地终端执行命令（需已安装 `gpg` 工具）：

```bash
# 生成高强度 RSA 4096 密钥对
gpg --full-generate-key
```
- 密钥类型选择：`(1) RSA and RSA`（默认）；
- 密钥长度输入：`4096`；
- 有效期选择：`0`（永不过期，或根据需要设置）；
- 输入真实姓名（如 `Ateng`）与邮箱（如 `2385569970@qq.com`）；
- **设置 Passphrase**：输入一个强保护密码，**请牢记该密码**，后续将作为 `MAVEN_GPG_PASSPHRASE` 使用。

### 2. 查看密钥 ID
执行以下命令列出已有公钥：

```bash
gpg --list-keys --keyid-format LONG
```

输出示例：
```text
pub   rsa4096/8F3A2B1C4D5E6F7A 2026-10-15 [SC]
      E2B8C1D0F3A2B1C4D5E6F7A890123456789ABCDE
uid                 [ultimate] Ateng <2385569970@qq.com>
sub   rsa4096/1A2B3C4D5E6F7A8B 2026-10-15 [E]
```
其中的 `8F3A2B1C4D5E6F7A`（即 `pub rsa4096/` 后面的 16 位字符）即为您的 **Key ID**。

### 3. 将公钥同步至公钥服务器 (Keyservers)
Maven Central 的自动验签系统会向公钥服务器拉取公钥以校验签名。执行以下命令向主流服务器分发公钥：

```bash
# 替换为您的真实 Key ID
gpg --keyserver keyserver.ubuntu.com --send-keys 8F3A2B1C4D5E6F7A
gpg --keyserver keys.openpgp.org --send-keys 8F3A2B1C4D5E6F7A
```

### 4. 导出私钥 ASCII 文本
在终端导出可导入 CI 环境变量的 Armor 私钥文本：

```bash
# 替换为您的真实 Key ID
gpg --armor --export-secret-keys 8F3A2B1C4D5E6F7A
```

终端将打印如下格式的完整文本（从 `-----BEGIN PGP PRIVATE KEY BLOCK-----` 开始至 `-----END PGP PRIVATE KEY BLOCK-----` 结束）：

```text
-----BEGIN PGP PRIVATE KEY BLOCK-----

lQdGBG...（此处为多行密文）...==
-----END PGP PRIVATE KEY BLOCK-----
```
**复制这整段完整文本（包括首尾两行标记线）。**

---

## 第三步：在 GitHub 仓库配置 Secrets 与 Variables

前往 GitHub 仓库页面：
**Settings** -> **Secrets and variables** -> **Actions**

### 1. 配置 1 个全局变量 (Variables)
点击 **Variables** 标签页 -> **New repository variable**：
- **Name**: `ENABLE_MAVEN_PUBLISH`
- **Value**: `true`

> 💡 **提示**：只要该变量值为 `true`，Release 流水线就会在发版时自动执行 Maven 发布；若临时不需要发布至中央仓库，只需将该变量修改为 `false` 或删除即可。

### 2. 配置 4 个机密密钥 (Secrets)
点击 **Secrets** 标签页 -> **New repository secret**，逐项添加以下 4 个项：

| Secret 名称 | 填入的值与内容来源 |
| :--- | :--- |
| `CENTRAL_USERNAME` | 第一步在 Sonatype Central 生成的 User Token 用户名 |
| `CENTRAL_PASSWORD` | 第一步在 Sonatype Central 生成的 User Token 密码 |
| `MAVEN_GPG_PRIVATE_KEY` | 第二步导出的完整 GPG 私钥 ASCII 文本（包含首尾标记行） |
| `MAVEN_GPG_PASSPHRASE` | 第二步生成 GPG 时设置的保护密码口令 |

---

## 第四步：触发全自动发版与验证

> [!IMPORTANT]
> **主干发版铁律与分支防呆 (Trunk-Only Policy)**：
> 本项目严禁在任何特性分支或未合入主干的分支发版！发版必须且只能在 `main` 主干分支发起。
> 本地脚本与 CI 流水线均已部署强门禁：
> - 本地门禁：`scripts/release.sh` 自动拦截非 `main` 分支与脏代码；
> - 远端门禁：`.github/workflows/release.yml` 严格校验网页调度分支（仅允许 `main`）及 Tag 祖先谱系（`git merge-base --is-ancestor` 确保必须合入主干），彻底阻断非合规发版。

配置完成后，即可享受全自动流水线发版：

### 方式一：网页端一键调度发版 (推荐首选)
1. 进入 GitHub 仓库 -> 点击顶部 **Actions** 标签页；
2. 在左侧选择 **Release** 流水线；
3. 点击右侧 **Run workflow** 下拉框（**触发分支必须选择 `main`**）；
4. 输入拟发布的版本号（例如 `v1.0.0`）；
5. 点击绿色 **Run workflow** 按钮。

流水线将自动执行：
- 校验触发分支为 `main`、校验版本格式与防重名自检；
- 创建并推送 Git Tag；
- 构建不可变 Jar 包并生成校验清单挂载至 GitHub Release；
- 自动化调用 GPG 签名，并将构件推送到 Sonatype Central Portal。

### 方式二：本地发版助手触发 (推荐首选)
在本地终端（Git Bash / WSL / Linux）执行：

```bash
git checkout main
git pull origin main
bash scripts/release.sh
```

脚本将自动执行：
- 自动检测历史最新标签并推算下一个语义化版本（或支持交互式输入自定义版本如 `v0.0.3`）；
- 自动同步将全工程 POM 版本设为发版版本，通过原生 `sed -i` 原地更新接入文档，精准暂存提交并注入 `[skip ci]` 降噪；
- 本地与远程同名 Tag 防重名检测，防止误覆盖已有发布；
- 自动创建附注标签（Annotated Tag）并原子推送到远程仓库，无缝唤起 GitHub Actions 发版，彻底消除冗余 CI 与快照版本困扰。

### 方式三：手动 Git Tag 命令触发
```bash
git checkout main
git pull origin main
git tag -a v0.0.3 -m "Release v0.0.3"
git push origin v0.0.3
```

---

## 第五步：验证中央仓库发布状态

1. 流水线成功执行完毕后，登录 [Sonatype Central Portal](https://central.sonatype.com/)；
2. 在 **Deployments** 列表项中可以看到一条属于 `ateng-boot` 的发布记录；
3. Portal 会自动执行签名与规范检验，状态转为 `PUBLISHED`；
4. 大约 **10 ~ 30 分钟** 内，全球 Maven Central 镜像同步完成；
5. 下游开发者即可直接在 `pom.xml` 中引入正式依赖（最新稳定版实时参见顶部 Central 徽章）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-all</artifactId>
    <version>0.0.2</version>
</dependency>
```

---

## 第六步：关键架构配置与避坑最佳实践

在多模块工程与无交互 CI/CD 发布实践中，以下三项配置为确保发布顺畅、高效与合规的黄金法则（详见 [ADR-0007: Maven 中央仓库发布解耦与 CI 纯净化治理决策](../adr/0007-maven-publishing-and-clean-ci-pipeline.md)）：

### 1. 发布提速与异步解耦 (`<waitUntil>validated</waitUntil>`)
Sonatype Central Portal 的生命周期为：`PENDING` $\to$ `VALIDATING` $\to$ `PUBLISHING` $\to$ `PUBLISHED`。
- **默认陷阱**：若配置 `<waitUntil>published</waitUntil>`，插件必须在 CI Runner 中持续轮询死等直到全球所有 CDN 镜像与索引同步完成，通常耗费 10 ~ 20 分钟；
- **最佳解法**：在根 `pom.xml` 中将发布插件配置为 `<waitUntil>validated</waitUntil>`。一旦通过服务端 GPG 验签、Javadoc、POM 契约与 Checksums 校验（约 1 ~ 2 分钟），流水线即刻亮绿准出，后续全球镜像同步由 Sonatype 异步托管。

### 2. GPG 无 TTY 环境签名 (`--pinentry-mode loopback`)
GitHub Actions Runner 等 CI 环境属于无图形界面与无控制台终端（Non-interactive / No-TTY）环境：
- **默认陷阱**：自 GPG 2.1 起，密码输入收口至 `gpg-agent` 并强制拉起交互式 `pinentry` 弹窗。在 CI 环境中将直接抛出致命异常：`gpg: signing failed: No pinentry`；
- **最佳解法**：在 `pom.xml` 的 `maven-gpg-plugin` 中显式注入 `--pinentry-mode loopback` 参数，允许直接消费命令行传入的 Passphrase：
  ```xml
  <configuration>
      <gpgArguments>
          <arg>--pinentry-mode</arg>
          <arg>loopback</arg>
      </gpgArguments>
  </configuration>
  ```

### 3. 多模块非发布子模块的双重排除规约 (`maven.deploy.skip` + `excludeArtifacts`)
若工程包含演示 Demo、自验测试套件（如 `ateng-boot-samples`）：
- **生命周期拦截**：在目标子模块自身的 `pom.xml` 中声明 `<maven.deploy.skip>true</maven.deploy.skip>`，Maven 构建生命周期将原生跳过该模块的部署阶段；
- **Central Bundle 反应堆排除**：由于 Sonatype 官方 `central-publishing-maven-plugin` 以 Reactor 反应堆维度扫描打包并上传 Staging Bundle，缺少 POM 封包会导致服务端报校验拒绝（`Bundle has content that does NOT have a .pom file`），因此必须在根插件中协同声明 `<excludeArtifacts><excludeArtifact>ateng-boot-samples</excludeArtifact></excludeArtifacts>`，形成生命周期与 Central 封包双重隔离闭环（详见 [ADR-0007](../adr/0007-maven-publishing-and-clean-ci-pipeline.md)）。

### 4. 发布日志规范化与接入文档自驱更新
- **日志双语规范**：CI 工作流中 `git-cliff-action` 必须显式声明 `config: ${{ hashFiles('.cliff.toml') != '' && '.cliff.toml' || '' }}`，并关闭 `generate_release_notes`，确保 GitHub Releases 页面呈现地道中英双语分类与作者直达链接；
- **复制即用与自驱改版**：`README.md` 接入示例坚持展示具体的真实版本号（如 `<version>0.0.2</version>`），方便开发者一键复制即用；发版时由 `scripts/release.sh` 内置的原生 `sed -i` 原地流式自驱更新文档版本并随 Tag 原子提交（详见 [ADR-0008: 自动化发版闭环、多维变更日志与常青文档架构决策](../adr/0008-automated-release-changelog-and-evergreen-docs.md)）。

### 5. 主干发布铁律与双端分支防呆治理
- **不可变性防线**：Maven Central 构件不可撤销，随意在未合入主干的特性分支发版将造成灾难性的脏版本污染；
- **双端防御落地**：本地执行 `scripts/release.sh` 强校验当前必须处于 `main` 分支且工作区 100% 干净；GitHub Actions 在网页调度时校验 `github.ref_name == 'main'`，在 Tag 推送时校验 `git merge-base --is-ancestor` 确保提交必须合入主干，形成严密闭环。

