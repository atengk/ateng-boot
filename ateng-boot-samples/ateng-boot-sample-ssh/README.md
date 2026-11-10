# ateng-boot-sample-ssh SSH 与 SFTP 基础设施体验工程

> 专注于演示与自验 `ateng-boot-ssh` 核心特性在真实业务场景下的开箱使用：涵盖命令执行与安全提权、集群批量巡检与动态切源、受管本地端口转发与跳板机级联穿透、SFTP 文档生命周期与目录双向镜像同步、以及 WebSSH 交互式伪终端会话。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSshApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-ssh
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 `EmbeddedSshTestConfig` 内存桩与自洽配置，无需任何外部物理 SSH 服务器即可直接启动并在秒级跑通全部回归测试；
- **Local 真实模式**：若需连接真实生产或开发环境的 Linux 主机，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml` 并配置真实服务器凭据（已被 `.gitignore` 忽略，CI 零泄露风险），激活 `local` Profile 启动即可直连真实环境。

---

## 3. 核心 Controller 接口与一键体验

本样例工程按功能领域解耦为 5 大 REST 领域控制器与 1 个 WebSSH 终端诊断接口，所有接口统一采用强类型契约并显式返回 `Result<T>`：

| 领域分类 | Controller 类 | 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- | :--- |
| **命令执行** | `SshCommandController` | 同步执行远程短命令 | `POST /sample/ssh/command/exec` | `curl -s -X POST "http://localhost:8080/sample/ssh/command/exec" -H "Content-Type: application/json" -d '{"command":"uname -a"}'` |
| **命令执行** | `SshCommandController` | 异步下发命令 (Future) | `POST /sample/ssh/command/exec-async` | `curl -s -X POST "http://localhost:8080/sample/ssh/command/exec-async" -H "Content-Type: application/json" -d '{"command":"hostname"}'` |
| **命令执行** | `SshCommandController` | 原生 Sudo 提权与密码抹零 | `POST /sample/ssh/command/exec-sudo` | `curl -s -X POST "http://localhost:8080/sample/ssh/command/exec-sudo" -H "Content-Type: application/json" -d '{"command":"whoami","sudoPassword":"YOUR_SUDO_PASSWORD"}'` |
| **命令执行** | `SshCommandController` | 多行复杂运维脚本管道执行 | `POST /sample/ssh/command/exec-script` | `curl -s -X POST "http://localhost:8080/sample/ssh/command/exec-script" -H "Content-Type: application/json" -d '{"scriptBody":"echo line1\necho line2"}'` |
| **命令执行** | `SshCommandController` | 长任务滚动日志按行流式消费 | `POST /sample/ssh/command/stream-log` | `curl -s -X POST "http://localhost:8080/sample/ssh/command/stream-log" -H "Content-Type: application/json" -d '{"command":"for i in 1 2 3; do echo item $i; done"}'` |
| **命令执行** | `SshCommandController` | 节点连通性轻量无害探针 | `GET /sample/ssh/command/probe-health` | `curl -s -X GET "http://localhost:8080/sample/ssh/command/probe-health?serverName=node1"` |
| **集群治理** | `SshClusterController` | 集群多节点高级批量并发巡检 | `POST /sample/ssh/cluster/batch-inspect` | `curl -s -X POST "http://localhost:8080/sample/ssh/cluster/batch-inspect" -H "Content-Type: application/json" -d '{"serverNames":["node1","node2"],"command":"uptime","concurrency":2,"failFast":false}'` |
| **集群治理** | `SshClusterController` | CMDB 纯内存资产动态直连 | `POST /sample/ssh/cluster/dynamic-connect` | `curl -s -X POST "http://localhost:8080/sample/ssh/cluster/dynamic-connect" -H "Content-Type: application/json" -d '{"host":"127.0.0.1","port":2222,"username":"testuser","password":"testpassword","command":"id"}'` |
| **集群治理** | `SshClusterController` | 线程级上下文透明切源执行 | `POST /sample/ssh/cluster/context-switch` | `curl -s -X POST "http://localhost:8080/sample/ssh/cluster/context-switch" -H "Content-Type: application/json" -d '{"serverName":"node1","command":"hostname"}'` |
| **受管隧道** | `SshTunnelController` | 开启本地端口转发隧道 | `POST /sample/ssh/tunnel/open-tunnel` | `curl -s -X POST "http://localhost:8080/sample/ssh/tunnel/open-tunnel" -H "Content-Type: application/json" -d '{"localPort":0,"remoteHost":"127.0.0.1","remotePort":80}'` |
| **受管隧道** | `SshTunnelController` | 对称安全释放端口转发隧道 | `POST /sample/ssh/tunnel/close-tunnel` | `curl -s -X POST "http://localhost:8080/sample/ssh/tunnel/close-tunnel" -H "Content-Type: application/json" -d '{"localPort":8088}'` |
| **受管隧道** | `SshTunnelController` | ProxyJump 两级跳板机级联穿透 | `POST /sample/ssh/tunnel/proxy-jump` | `curl -s -X POST "http://localhost:8080/sample/ssh/tunnel/proxy-jump" -H "Content-Type: application/json" -d '{"targetHost":"172.16.0.10","targetPort":22,"jump1Host":"10.0.0.1","jump1Port":22,"jump2Host":"192.168.1.1","jump2Port":22,"command":"uptime"}'` |
| **受管隧道** | `SshTunnelController` | 端口转发真实 TCP 流量交互 | `POST /sample/ssh/tunnel/tcp-echo` | `curl -s -X POST "http://localhost:8080/sample/ssh/tunnel/tcp-echo" -H "Content-Type: application/json" -d '{"remoteHost":"127.0.0.1","remotePort":7777,"message":"ping"}'` |
| **SFTP 文档** | `SftpDocumentController` | 表单文件原子上传 (.tmp+rename) | `POST /sample/ssh/sftp-doc/upload` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/upload" -F "file=@local.txt" -F "remotePath=/upload/remote.txt"` |
| **SFTP 文档** | `SftpDocumentController` | 对称流式下载远端文件 | `GET /sample/ssh/sftp-doc/download` | `curl -s -X GET "http://localhost:8080/sample/ssh/sftp-doc/download?remotePath=/upload/remote.txt" -o downloaded.txt` |
| **SFTP 文档** | `SftpDocumentController` | 纯文本与 JSON 快捷写入 | `POST /sample/ssh/sftp-doc/write-text` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/write-text" -H "Content-Type: application/json" -d '{"remotePath":"/app/config.json","content":"{\"env\":\"prod\"}"}'` |
| **SFTP 文档** | `SftpDocumentController` | 纯文本与 JSON 快捷读取 | `GET /sample/ssh/sftp-doc/read-text` | `curl -s -X GET "http://localhost:8080/sample/ssh/sftp-doc/read-text?remotePath=/app/config.json"` |
| **SFTP 文档** | `SftpDocumentController` | 文件尾部增量追加写 | `POST /sample/ssh/sftp-doc/append-text` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/append-text" -H "Content-Type: application/json" -d '{"remotePath":"/app/audit.log","content":"user logged in\n"}'` |
| **SFTP 文档** | `SftpDocumentController` | 跨目录安全平移文件 | `POST /sample/ssh/sftp-doc/copy-move` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/copy-move" -H "Content-Type: application/json" -d '{"sourcePath":"/app/config.json","targetPath":"/backup/config.json"}'` |
| **SFTP 文档** | `SftpDocumentController` | 原地原子重命名文件 | `POST /sample/ssh/sftp-doc/rename` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/rename" -H "Content-Type: application/json" -d '{"oldPath":"/backup/config.json","newPath":"/backup/config-v1.json"}'` |
| **SFTP 文档** | `SftpDocumentController` | 查询文件详细元数据 | `GET /sample/ssh/sftp-doc/info` | `curl -s -X GET "http://localhost:8080/sample/ssh/sftp-doc/info?remotePath=/backup/config-v1.json"` |
| **SFTP 文档** | `SftpDocumentController` | 探测远程文件路径是否存在 | `GET /sample/ssh/sftp-doc/exists` | `curl -s -X GET "http://localhost:8080/sample/ssh/sftp-doc/exists?remotePath=/backup/config-v1.json"` |
| **SFTP 文档** | `SftpDocumentController` | 单文件确定性物理删除 | `POST /sample/ssh/sftp-doc/delete` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-doc/delete" -H "Content-Type: application/json" -d '{"remotePath":"/backup/config-v1.json"}'` |
| **目录同步** | `SftpSyncController` | 目录树整包递归镜像上传同步 | `POST /sample/ssh/sftp-sync/upload-dir` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-sync/upload-dir" -H "Content-Type: application/json" -d '{"localDir":"/tmp/local-data","remoteDir":"/remote/sync-data"}'` |
| **目录同步** | `SftpSyncController` | 目录树整包递归镜像下载同步 | `POST /sample/ssh/sftp-sync/download-dir` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-sync/download-dir" -H "Content-Type: application/json" -d '{"remoteDir":"/remote/sync-data","localDir":"/tmp/downloaded-data"}'` |
| **目录同步** | `SftpSyncController` | DFS 递归清空删除非空目录树 | `POST /sample/ssh/sftp-sync/delete-dir` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-sync/delete-dir" -H "Content-Type: application/json" -d '{"remotePath":"/remote/sync-data","recursive":true}'` |
| **目录同步** | `SftpSyncController` | 大文件带节流防抖进度流式上传 | `POST /sample/ssh/sftp-sync/upload-large` | `curl -s -X POST "http://localhost:8080/sample/ssh/sftp-sync/upload-large" -H "Content-Type: application/json" -d '{"remotePath":"/remote/bigfile.bin","sizeKb":512}'` |
| **WebSSH** | `SshTerminalController` | WebSSH 终端规格与诊断指标 | `GET /sample/ssh/terminal/info` | `curl -s -X GET "http://localhost:8080/sample/ssh/terminal/info"` |
| **WebSSH** | `WebSshWebSocketHandler` | 原生 WebSocket 实时终端交互 | `WS /ws/ssh` | 浏览器直连或使用 `wscat -c "ws://localhost:8080/ws/ssh"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-ssh,ateng-boot-samples/ateng-boot-sample-ssh -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-ssh README](../../ateng-boot-ssh/README.md)。
