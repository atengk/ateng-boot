# ateng-boot-sample-ftp 客户端自验集成工程

> 专一面向生产就绪的 FTP/FTPS 协议客户端自验沙箱：覆盖 Commons Pool2 连接池借还生命周期与心跳保活、工作目录双重复位防串目录、`FtpContextHolder` 线程栈级动态切源、全目录递归镜像同步与 Fail-Safe 容错批处理引擎、`FtpProgressListener` 流式防抖进度监听、物理连通性健康探针、原子上传守护（`.tmp` + `rename`）、RFC 3659 MLSD 机器目录解析、级联递归创建（`mkdir -p`）、跨目录移动、路径穿越防卫与双模自适应实测。

---

## 1. 场景定位与功能矩阵

传统 FTP 客户端在业务集成中通常面临以下痛点：
1. **短连接开销巨大与长连接池化复杂**：传统单次传输频繁握手鉴权开销严重，而自行维护连接池易导致网络断线、假死悬挂或会话泄露；
2. **工作目录污染引发严重串目录事故**：FTP 状态机具备全局工作目录（CWD），若某次操作切换目录后未还原，下游操作将全线写错或读错目录；
3. **云防火墙截断控制通道**：长时间传输大文件时，控制通道无数据流动极易被云安全组或防火墙静默斩断；
4. **半截残缺文件灾难**：网络波动导致上传中断时，残余半截废文件直接暴露给下游消费系统，引发严重业务解析异常；
5. **多环境多节点切换代码侵入严重**：多 FTP 数据源切换时代码充斥 `if-else` 或硬编码配置；
6. **路径穿越（Path Traversal）安全风险**：直接拼接用户输入路径容易遭遇 `../` 越权逃逸风险；
7. **大文件下载引发堆内存 OOM**：缺乏安全阈值守护，一次性加载大文件直接撑爆 JVM 堆内存；
8. **批量递归目录同步与容错缺失**：面对多层级子目录对账文件树，缺乏镜像同步与单文件失败隔离的聚合回执模型。

`ateng-boot-sample-ftp` 提供了工业级规范且完全离线自洽的集成验证沙箱：
- **纯内存 FakeFtpServer 极速自验**：零物理网络与外部 FTP 依赖，毫秒级独立离线全绿运行；
- **Apache Commons Pool2 受管对象池**：提供心跳保活（`NOOP`）、存活探针与异常自动剔除；
- **绝对工作目录隔离**：借出与归还双重 `cwd("/")` 复位，彻底消除串目录隐患；
- **全目录递归镜像同步与 Fail-Safe 批处理**：多层级目录镜像上传下载，系统隐性文件自动剔除，失败明细强类型聚合回执；
- **流式传输进度平滑防抖节流**：1% 步长或 200ms 防抖节流 + 100% 满额终态保底，杜绝高频日志风暴；
- **无副作用连通性健康探针**：借出连接发送 `NOOP` 指令毫秒级检测控制通道，全程零物理文件读写副作用；
- **原子上传守护与异常补偿**：采用 `.tmp` 临时文件写入 + 成功原子 `rename`，异常时在独立会话中物理清理脏文件；
- **线程栈级动态切源路由**：基于 `FtpContextHolder` 实现栈式嵌套上下文切换，`finally` 确定性复位；
- **严格路径穿越安全清洗**：`cleanAndValidatePath` 前置防御 `..` 序列与空字符注入；
- **双模自适应单测试套件**：默认装载 FakeFtpServer 100% 离线自洽构建，激活 `local` Profile 直连本地真实服务。

---

## 2. 5 大业务场景服务组件速查表

| 场景服务组件 | 核心职责与业务场景 | 核心门面 / API | 校验要点与特性 |
| :--- | :--- | :--- | :--- |
| `DocumentSyncScenarioService` | 企业日常文档全生命周期管理 | `uploadBytes` / `uploadFile` / `downloadToFile` / `writeText` / `readText` / `appendText` / `copyDocument` / `renameDocument` / `moveDocument` / `deleteDocument` | 覆盖 CRUD、RFC 959 原生增量追加写、受管单文件原子管道对拷、重命名、跨目录移动与目录递归删除 |
| `SettlementBatchSyncScenarioService` | 金融日结对账全目录递归镜像同步 | `syncSettlementToRemote` / `syncSettlementFromRemote` (`BatchTransferResult`) | 多层级目录树递归镜像同步，自动过滤 Windows `desktop.ini`、macOS `.DS_Store` 等宿主隐性文件，Fail-Safe 容错聚合回执 |
| `LargeFileTransferScenarioService` | 大文件流式传输与进度监听 | `uploadLargeFile` / `downloadLargeFile` (`FtpProgressListener`) | 大文件稳定吞吐，挂载进度监听器，验证 1% 步长/200ms 防抖节流与 100% 满额保底 |
| `DynamicRoutingScenarioService` | 多节点动态切源与主备隔离 | `executeOnServer` / `writeToBackupServer` / `readFromBackupServer` | 基于 `FtpContextHolder.runWith` 实现线程栈级安全隔离切源，作用域结束自动复位，物理级隔离验证 |
| `ConnectivityProbeScenarioService` | 物理连通性自验健康探针 | `checkDefaultServerHealth` / `checkServerHealth` / `assertServerHealthy` | 无副作用发送 `NOOP` 指令毫秒级探活，探查异常抛出受管 `FtpException` |

---

## 3. 双模自适应测试与本地联调

### 1. 默认 / CI 模式 (100% 离线自洽)
无需配置任何外部服务，测试套件自动装载 FakeFtpServer 纯内存桩，执行秒级完整端到端自验：
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-ftp -am -T 1C -nsu -B
```

### 2. Local 真实环境直连模式 (本地联调)
若需直连本地或局域网真实 FTP 服务器（如 vsftpd、FileZilla Server 等）：
1. 复制资产模板：
   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```
2. 在 `application-local.yml` 中填写真实服务地址与账号凭据；
3. 激活 `local` Profile 运行集成测试：
   ```bash
   mvn test -pl ateng-boot-samples/ateng-boot-sample-ftp -Dspring.profiles.active=local -am -T 1C -nsu -B
   ```
   测试套件将自动跳过 FakeFtpServer 桩，直连您的真实 FTP 服务全真运行。
