# ateng-boot-sample-ftp 远程文件传输自验样例工程

> 专注于演示与自验 `ateng-boot-ftp` 核心特性在真实企业级场景（电子合同安全流转、金融日结对账全目录递归镜像同步、大文件流式防抖进度监听、多节点栈式动态切源、无副作用物理健康探针与连接池生命周期治理）下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `src/main/resources/application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：在 IDE 中直接运行主启动类 main 方法
  io.github.atengk.boot.sample.ftp.SampleFtpApplication

  # 方式二：命令行极速启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-ftp
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 模式**（未激活 `local` Profile）：
  测试套件内置纯内存 `FakeFtpServer`，自动在本地动态端口挂载 `default`（主节点）与 `backup`（备用节点），无需安装或启动任何外部物理 FTP 服务，即可秒级启动运行与 100% 离线自洽通过全套自动化回归测试；
- **Local 真实模式**（激活 `local` Profile）：
  若需连接真实物理 FTP/FTPS 中间件（如 vsftpd、ProFTPD、FileZilla Server 等），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实主机、端口与鉴权账密；启动应用或运行测试时追加 `-Dspring.profiles.active=local` 即可直连真实服务联调（该文件已被 `.gitignore` 严格拦截，CI 零风险与凭据安全）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能领域 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **表单文件原子上传** | `FtpDocumentController` | `POST /sample/ftp/document/upload` | `curl -X POST "http://localhost:8080/sample/ftp/document/upload" -F "file=@README.md" -F "remotePath=/upload/notice.txt"` |
| **磁盘文件指定上传** | `FtpDocumentController` | `POST /sample/ftp/document/upload-file` | `curl -X POST "http://localhost:8080/sample/ftp/document/upload-file" -H "Content-Type: application/json" -d "{\"localPath\":\"/tmp/contract.pdf\",\"remotePath\":\"/docs/contract.pdf\"}"` |
| **快捷写入纯文本** | `FtpDocumentController` | `POST /sample/ftp/document/write-text` | `curl -X POST "http://localhost:8080/sample/ftp/document/write-text" -H "Content-Type: application/json" -d "{\"remotePath\":\"/docs/policy.txt\",\"content\":\"Enterprise Security Policy v1.0\"}"` |
| **快捷读取纯文本** | `FtpDocumentController` | `GET /sample/ftp/document/read-text` | `curl "http://localhost:8080/sample/ftp/document/read-text?remotePath=/docs/policy.txt"` |
| **RFC 959 增量追加写** | `FtpDocumentController` | `POST /sample/ftp/document/append-text` | `curl -X POST "http://localhost:8080/sample/ftp/document/append-text" -H "Content-Type: application/json" -d "{\"remotePath\":\"/docs/policy.txt\",\"content\":\"\nAmendment: Section 4.2 Approved.\"}"` |
| **受管管道流原子对拷** | `FtpDocumentController` | `POST /sample/ftp/document/copy` | `curl -X POST "http://localhost:8080/sample/ftp/document/copy" -H "Content-Type: application/json" -d "{\"sourcePath\":\"/docs/policy.txt\",\"targetPath\":\"/docs/policy-bak.txt\"}"` |
| **跨目录安全移动** | `FtpDocumentController` | `POST /sample/ftp/document/move` | `curl -X POST "http://localhost:8080/sample/ftp/document/move" -H "Content-Type: application/json" -d "{\"sourcePath\":\"/docs/policy-bak.txt\",\"targetPath\":\"/archive\"}"` |
| **同级文件重命名** | `FtpDocumentController` | `POST /sample/ftp/document/rename` | `curl -X POST "http://localhost:8080/sample/ftp/document/rename" -H "Content-Type: application/json" -d "{\"sourcePath\":\"/docs/policy.txt\",\"targetPath\":\"/docs/policy-v2.txt\"}"` |
| **查询文件详细元数据** | `FtpDocumentController` | `GET /sample/ftp/document/info` | `curl "http://localhost:8080/sample/ftp/document/info?remotePath=/docs/policy-v2.txt"` |
| **RFC 3659 MLSD 机器目录** | `FtpDocumentController` | `GET /sample/ftp/document/list-files` | `curl "http://localhost:8080/sample/ftp/document/list-files?remoteDir=/docs"` |
| **对称流式文件下载** | `FtpDocumentController` | `GET /sample/ftp/document/download` | `curl -OJ "http://localhost:8080/sample/ftp/document/download?remotePath=/docs/policy-v2.txt"` |
| **单文件确定性删除** | `FtpDocumentController` | `POST /sample/ftp/document/delete-file` | `curl -X POST "http://localhost:8080/sample/ftp/document/delete-file" -H "Content-Type: application/json" -d "{\"path\":\"/upload/notice.txt\"}"` |
| **级联清空删除目录** | `FtpDocumentController` | `POST /sample/ftp/document/delete-directory` | `curl -X POST "http://localhost:8080/sample/ftp/document/delete-directory" -H "Content-Type: application/json" -d "{\"path\":\"/archive\",\"recursive\":true}"` |
| **金融日结对账全目录上传** | `FtpBatchSyncController` | `POST /sample/ftp/sync/upload-directory` | `curl -X POST "http://localhost:8080/sample/ftp/sync/upload-directory" -H "Content-Type: application/json" -d "{\"localDir\":\"/data/settlement/20261110\",\"remoteDir\":\"/finance/settlement\",\"filterExtension\":\".csv\",\"failFast\":false}"` |
| **金融日结对账全目录下载** | `FtpBatchSyncController` | `POST /sample/ftp/sync/download-directory` | `curl -X POST "http://localhost:8080/sample/ftp/sync/download-directory" -H "Content-Type: application/json" -d "{\"localDir\":\"/data/audit/20261110\",\"remoteDir\":\"/finance/settlement\",\"filterExtension\":\".csv\",\"failFast\":false}"` |
| **大文件流式进度上传** | `FtpTransferController` | `POST /sample/ftp/transfer/upload-large` | `curl -X POST "http://localhost:8080/sample/ftp/transfer/upload-large" -H "Content-Type: application/json" -d "{\"remotePath\":\"/transfer/payload-10m.bin\",\"sizeKb\":10240}"` |
| **大文件流式进度下载** | `FtpTransferController` | `POST /sample/ftp/transfer/download-large` | `curl -X POST "http://localhost:8080/sample/ftp/transfer/download-large" -H "Content-Type: application/json" -d "{\"remotePath\":\"/transfer/payload-10m.bin\",\"localPath\":\"/tmp/downloaded-10m.bin\"}"` |
| **50MB 内存防爆熔断守护** | `FtpTransferController` | `GET /sample/ftp/transfer/download-safe` | `curl "http://localhost:8080/sample/ftp/transfer/download-safe?remotePath=/transfer/payload-10m.bin"` |
| **默认主节点无副作用探针** | `FtpGovernanceController` | `GET /sample/ftp/governance/probe-health` | `curl "http://localhost:8080/sample/ftp/governance/probe-health"` |
| **具名备用节点健康探针** | `FtpGovernanceController` | `GET /sample/ftp/governance/probe-health` | `curl "http://localhost:8080/sample/ftp/governance/probe-health?serverName=backup"` |
| **物理健康连通性强断言** | `FtpGovernanceController` | `POST /sample/ftp/governance/assert-healthy` | `curl -X POST "http://localhost:8080/sample/ftp/governance/assert-healthy?serverName=backup"` |
| **动态切源写入 (backup 节点)** | `FtpGovernanceController` | `POST /sample/ftp/governance/dynamic-routing-write` | `curl -X POST "http://localhost:8080/sample/ftp/governance/dynamic-routing-write" -H "Content-Type: application/json" -d "{\"serverName\":\"backup\",\"remotePath\":\"/routing/notice.txt\",\"content\":\"Backup Datacenter Payload\"}"` |
| **动态切源读取 (backup 节点)** | `FtpGovernanceController` | `GET /sample/ftp/governance/dynamic-routing-read` | `curl "http://localhost:8080/sample/ftp/governance/dynamic-routing-read?serverName=backup&remotePath=/routing/notice.txt"` |
| **连接池实时运行时指标** | `FtpGovernanceController` | `GET /sample/ftp/governance/pool-stats` | `curl "http://localhost:8080/sample/ftp/governance/pool-stats?serverName=default"` |

---

## 4. 全真自动化回归命令

```bash
# 执行 Starter 核心与 Sample 场景工程联合全真回归
mvn test -pl ateng-boot-ftp,ateng-boot-samples/ateng-boot-sample-ftp -am -T 1C -nsu -B

# 激活 local Profile 直连真实 FTP 服务端联调（需配置 application-local.yml）
mvn test -pl ateng-boot-samples/ateng-boot-sample-ftp -Dspring.profiles.active=local -am -T 1C -nsu -B
```

> 详细架构原理、设计不变量、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-ftp README](../../ateng-boot-ftp/README.md)。
