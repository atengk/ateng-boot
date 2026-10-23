# ateng-boot-ssh 基础设施 Starter

> 工业级 SSH 远程命令执行与网络穿透基础设施 Starter，基于 Apache MINA SSHD 原生驱动构建。提供四位一体多主机拓扑（缺省单机 / YAML 命名多节点 / 线程上下文切源 / CMDB 纯内存值对象直连）、LRU/TTL 活跃心跳会话缓存池（`SshSessionPool`）、RFC 4254 孤儿进程超时主动信号防卫（`TERM`/`INT`）、长任务实时按行流式消费（`execStream`）、原生 Sudo 提权安全管道（`-S -p ''` 提权协议与密码即时内存擦除抹零）、Core 四维上下文全链路透传集群并发巡检（`execBatch`）、本地端口转发受管句柄（`SshTunnelHandle`）与 ProxyJump 级联多跳跳板机穿透。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.ssh.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **短命令同步执行** | 同步短命令执行契约 | `SshTemplate` | `command-timeout` | `exec(String)` / `exec(String, SshExecOptions)` / `exec(String, String)` / `exec(SshServerInfo, String)` / `exec(SshServerInfo, String, SshExecOptions)`，返回强类型 `SshExecResult`（含 `exitCode`、`stdout`、`stderr`、`duration`） | `SshTemplateCommandExecutionTest` / `SampleSshIntegrationTest` |
| **多行脚本管道执行** | 复杂运维脚本管道下发 | `SshTemplate` | `command-timeout` | `execScript(String)` / `execScript(String, String)` / `execScript(SshServerInfo, String)`，自动将多行命令整包打包为远端 Shell 脚本管道执行并返回聚合结果 | `SshTemplateCommandExecutionTest` / `SampleSshIntegrationTest` |
| **长任务流式按行消费** | 实时输出回调监听 | `SshTemplate`<br>`Consumer<String>` | `command-timeout` | `execStream(String, Consumer<String>)` / `execStream(String, Consumer<String>, Consumer<String>)` / `execStream(SshServerInfo, String, Consumer<String>, Consumer<String>, SshExecOptions)`，实时按行回调消费远端滚动日志，返回 `int` 退出码 | `LineStreamingOutputStreamTest` / `SampleSshIntegrationTest` |
| **原生 Sudo 提权安全管道** | 无 TTY Sudo 提权与密码内存抹零 | `SshTemplate` | `command-timeout` | `execSudo(String, String)` / `execSudo(String, char[])` / `execSudo(String, String, String)` / `execSudo(SshServerInfo, String, String)`，底层构建 `-S -p ''` 协议管道，密码使用完毕即时执行 `Arrays.fill` 物理擦除抹零 | `SshTemplateSudoAndBatchTest` / `SampleSshIntegrationTest` |
| **瞬态单次任务隔离执行** | 零连接残留单次执行闭包 | `SshTemplate`<br>`SshSessionPool` | - | `execOnce(SshServerInfo, String)` / `execOnce(SshServerInfo, String, SshExecOptions)`，执行完毕立即确定性物理断开关闭底层连接，零句柄残留 | `ProxyJumpCascadingTest` / `SampleSshIntegrationTest` |
| **集群多主机并发巡检** | 受管线程池集群批量巡检 | `SshTemplate` | `command-timeout` | `execBatch(Collection<SshServerInfo>, String)` / `execBatchNamed(Collection<String>, String)` / `execSudoBatch(Collection<SshServerInfo>, String, String)`，联动 Core 受管线程池并透传四维快照，节点级异常隔离 | `SshTemplateSudoAndBatchTest` / `SampleSshIntegrationTest` |
| **受管本地端口转发** | 本地端口至远端服务透明代理 | `SshTemplate`<br>`SshTunnelHandle` | - | `openLocalTunnel(int, String, int)` / `openLocalTunnel(String, int, String, int)` / `openLocalTunnel(SshServerInfo, int, String, int)` / `openLocalTunnel(SshServerInfo, SshdSocketAddress, SshdSocketAddress)`，支持动态分配随机端口 | `SshTemplateTunnelTest` / `SampleSshIntegrationTest` |
| **受管隧道生命周期句柄** | 端口转发长连接安全治理 | `SshTunnelHandle`<br>`AutoCloseable` | - | 实现 `AutoCloseable`，提供 `close()`、`isAlive()`、`isClosed()`、`getLocalPort()`、`getLocalHost()`、`getRemoteHost()`、`getRemotePort()`，幂等安全释放本地监听 Socket | `SshTunnelHandleTest` / `SshTemplateTunnelTest` |
| **ProxyJump 跳板机穿透** | 级联多跳跳板机代理穿透 | `SshServerInfo`<br>`SshSessionPool` | `connect-timeout` | `SshServerInfo.builder().jumpHost(...)`，递归解析级联跳板机拓扑，通过跳板机会话开启临时转发直连目标，双向生命周期级联清理 | `ProxyJumpCascadingTest` / `SampleSshIntegrationTest` |
| **受管会话缓存池** | LRU/TTL 高性能连接池 | `SshSessionPool`<br>`AutoCloseable` | `pool.max-sessions`<br>`pool.idle-timeout`<br>`pool.heartbeat-interval` | 按 `username@host:port#hash->via(...)` 复合键池化复用活会话，消除 200~500ms 重复建连开销；后台探针心跳保活与空闲超时自动剔除失效连接 | `SshSessionPoolTest` |
| **线程级动态切源路由** | 线程上下文透明切源 | `SshContextHolder` | `servers.*` | `setServer(String)` / `setServerInfo(SshServerInfo)` / `getServer()` / `getServerInfo()` / `runWith(String, Supplier<T>)` / `runWithInfo(SshServerInfo, Supplier<T>)` / `clear()`，支持栈式嵌套作用域 | `SshContextHolderTest` |
| **命名多主机受管注册表** | 静态命名节点字典中心 | `SshClientRegistry` | `servers.*` | `register(String, SshServerInfo)` / `getServer(String)` / `getDefaultServer()` / `containsServer(String)` / `getServerNames()`，集中纳管 YAML 声明的多主机资产 | `SshClientRegistryTest` |
| **云原生纯内存指纹容器** | K8s 只读容器免挂载指纹校验 | `InMemoryServerKeyVerifier`<br>`HostKeyPolicy` | `host-key-policy` | 提供 `LENIENT`（宽容记录首次指纹）、`STRICT`（生产已知指纹校验）、`IGNORE`（内网免检），100% 内存运行，绝不尝试写盘 `~/.ssh/known_hosts` | `InMemoryServerKeyVerifierTest` |
| **确定性三步停机治理** | 应用退出物理资源绝对释放 | `SshLifecycleManager`<br>`SmartLifecycle` | - | 停机时序：【1. 优先关闭所有活跃端口转发隧道 -> 2. 安全注销池化 Session -> 3. 停止底层 NIO SshClient】，杜绝端口悬挂与野进程残留 | `SshLifecycleManagerTest` / `SshLifecycleManagerTunnelTeardownTest` |
| **因果异常体系与错误码** | 语义化异常与统一错误码 | `SshOperationException`<br>`SshResultCode` | - | 拦截底层 NIO 与协议层异常，转译为语义化 `SshOperationException`，赋予十维规范错误码（`56001`~`56012`）并 100% 完整保留原始因果链堆栈 | `SshExceptionAndResultCodeTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-ssh` 是面向生产环境的工业级 SSH 远程治理基础设施 Starter：
- **纯原生依赖**：以 Apache MINA SSHD (`sshd-core`) 为底层驱动引擎，100% 原生 NIO 异步非阻塞事件驱动模型，绝不引入外部臃肿第三方包；
- **长连接池化与秒级复用**：彻底取缔传统短连接频繁握手认证与野线程创建，将命令执行握手开销从 300ms 降至 0ms；
- **云原生只读容器适配**：纯内存主机指纹校验器彻底解决了 Kubernetes 只读根文件系统（Read-Only Rootfs）下写入 `known_hosts` 权限崩溃的顽疾；
- **高阶远程安全穿透**：原生支持无 TTY Sudo 提权密码管道、RFC 4254 孤儿进程超时信号防卫、ProxyJump 跳板机级联多跳穿透与受管本地端口转发。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-ssh</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
运维与业务系统经常需要对远端 Linux 服务器下发配置、查询系统指标、执行 Sudo 提权维护或借道跳板机访问内网数据库；传统方式手写 JSch 或裸 Socket 极其繁琐，极易引发连接泄露、未闭合端口悬挂、超时远端僵尸进程以及密码明文泄漏等线上事故。

### 1. 最小可用配置
在 `application.yml` 中添加基础连接信息：
```yaml
ateng:
  boot:
    ssh:
      enabled: true
      host: 127.0.0.1
      port: 22
      username: deployer
      password: YOUR_PASSWORD
      connect-timeout: 10s
      auth-timeout: 15s
      command-timeout: 60s
```

### 2. 开箱即用注入操作
```java
@Service
@RequiredArgsConstructor
public class SystemInspectService {

    private final SshTemplate sshTemplate;

    // 1. 同步执行短命令
    public SshExecResult checkDiskUsage() {
        return sshTemplate.exec("df -h");
    }

    // 2. 原生 Sudo 提权安全管道执行（密码即时内存擦除抹零）
    public SshExecResult restartService(String sudoPassword) {
        return sshTemplate.execSudo("systemctl restart nginx", sudoPassword);
    }

    // 3. 长任务流式实时按行消费（用于滚动日志或构建输出）
    public void streamDockerLogs() {
        sshTemplate.execStream("docker logs -f app", System.out::println);
    }

    // 4. 开启本地端口转发隧道访问内网服务（如内网 MySQL 3306）
    public void forwardDatabaseAccess() throws Exception {
        // localPort 传 0 由操作系统自动分配空闲随机端口
        try (SshTunnelHandle tunnel = sshTemplate.openLocalTunnel(0, "10.0.0.5", 3306)) {
            int allocatedLocalPort = tunnel.getLocalPort();
            // 本地直接连接 127.0.0.1:allocatedLocalPort 即可透明访问远端 10.0.0.5:3306
        }
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 1. 声明式配置命名多主机字典
支持在 `application.yml` 中统一纳管缺省主节点与静态多节点：
```yaml
ateng:
  boot:
    ssh:
      enabled: true
      host: 127.0.0.1
      port: 22
      username: root
      password: rootpassword
      pool:
        max-sessions: 64
        idle-timeout: 5m
        heartbeat-interval: 15s
      servers:
        node1:
          host: 192.168.1.101
          port: 22
          username: worker1
          password: workerpass1
        node2:
          host: 192.168.1.102
          port: 22
          username: worker2
          private-key-path: /etc/ssh/id_rsa
        bastion:
          host: 192.168.1.200
          port: 22
          username: ops
          password: opspassword
```

### 2. 线程上下文透明动态切源 (`SshContextHolder`)
```java
// 作用域内自动切源至 node1，作用域结束后确定性复位，杜绝线程脏读
SshExecResult result = SshContextHolder.runWith("node1", () -> {
    return sshTemplate.exec("uptime");
});
```

### 3. CMDB / 数据库动态资产直连描述符 (`SshServerInfo`)
对于资产从业务数据库（CMDB / 主机表）动态查询的场景，直接构造不可变值对象传入门面：
```java
// 支持纯内存载入 PEM 格式私钥文本，严禁落盘临时文件，防范物理磁盘泄密
SshServerInfo dynamicServer = SshServerInfo.builder()
        .host("10.10.20.15")
        .port(22)
        .username("admin")
        .privateKey("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----")
        .hostKeyPolicy(HostKeyPolicy.LENIENT)
        .build();

SshExecResult result = sshTemplate.exec(dynamicServer, "uname -a");
```

### 4. ProxyJump 两级跳板机级联穿透 (Client -> Jump1 -> Jump2 -> Target)
```java
// 1. 构建第一级堡垒机
SshServerInfo jump1 = SshServerInfo.builder()
        .host("bastion1.corp.internal")
        .port(22)
        .username("jump1_user")
        .password("jump1_pass")
        .build();

// 2. 构建第二级跳板机，指定上一级 jumpHost
SshServerInfo jump2 = SshServerInfo.builder()
        .host("bastion2.dmz.internal")
        .port(22)
        .username("jump2_user")
        .password("jump2_pass")
        .jumpHost(jump1)
        .build();

// 3. 构建内网隔离目标服务器，指定入口 jumpHost 为 jump2
SshServerInfo target = SshServerInfo.builder()
        .host("db-cluster.private.internal")
        .port(22)
        .username("dbadmin")
        .password("dbpass")
        .jumpHost(jump2)
        .build();

// 底层自适应级联穿透建连，享受连接池复用与双向级联资源清理
SshExecResult result = sshTemplate.exec(target, "systemctl status mysqld");
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. RFC 4254 孤儿进程超时主动信号防卫
- **机制原理**：传统客户端超时直接强杀本地通道（Close Channel），而远端服务器的进程（如失控死循环脚本）会脱离父进程沦为孤儿守护进程继续消耗 CPU/内存；
- **主动信号防卫**：当触发 `command-timeout` 超时阈值时，`SshCommandExecutor` 强制先向服务端发送 RFC 4254 标准 `SSH_MSG_CHANNEL_REQUEST`（信号载荷为 `SIGTERM`，重试兜底为 `SIGINT`），给远端子进程优雅捕获并退出机会，随后才闭合 Channel，从源头消灭僵尸进程。

### 2. 原生 Sudo 提权密码即时内存擦除抹零 (In-Memory Scrubbing)
- **机制原理**：`execSudo` 底层使用标准输入安全传输密码，严禁回显；
- **内存擦除防卫**：密码无论以 `String` 还是 `char[]` 传入，在写入远端标准输入管道后，底层立即执行 `Arrays.fill(bytes, (byte) 0)` 与 `Arrays.fill(chars, '\0')` 进行物理内存抹零擦除，防范利用 JVM Heap Dump 或 Core Dump 窃取提权密码。

### 3. 本地端口转发受管句柄确定性三步停机
- **资源确定性**：`openLocalTunnel` 返回的 `SshTunnelHandle` 实现 `AutoCloseable`；
- **三步优雅停机时序**：在 Spring 容器退出阶段，`SshLifecycleManager` 赋予最高优先级阶段（`Phase = Integer.MIN_VALUE + 1000`），严格遵循：
  1. 【时序 1/3】优先释放并关闭所有处于活跃状态的端口转发隧道（`SshTunnelHandle`），断开监听并释放本地 TCP 端口；
  2. 【时序 2/3】安全注销并断开受管会话缓存池（`SshSessionPool`）；
  3. 【时序 3/3】停止底层 NIO 异步驱动客户端引擎（`SshClient`），杜绝端口悬挂与孤儿 Socket 监听。

### 4. 集群批量并发巡检与 Core 四维上下文透传
- **并发纪律**：`execBatch` 基于 Core 模块受管异步线程池设施（`ThreadPoolTaskExecutor`），坚决杜绝创建野线程；
- **四维上下文透传**：异步并发下发任务时，统一挂载上下文装饰器，透明透传 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 快照并在任务完成后可靠清理现场；各节点异常相互隔离，单机失败绝不影响集群整体巡检。

---

## 5. 常见问题与排错指南 (FAQ)

### Q1: 在 Kubernetes 只读根文件系统（Read-Only Rootfs）中启动报错怎么办？
- **解答**：`ateng-boot-ssh` 默认开启 `host-key-policy: lenient`，依托纯内存校验器 [`InMemoryServerKeyVerifier`](./src/main/java/io/github/atengk/boot/ssh/verifier/InMemoryServerKeyVerifier.java) 运行，绝不尝试写入 `~/.ssh/known_hosts` 文件，支持 0 目录挂载、0 写入权限在非 root 容器中安全启动。

### Q2: 为什么 `openLocalTunnel` 建议指定本地端口为 0？
- **解答**：高并发微服务环境或动态多租户场景下，硬编码固定本地端口极易遭遇 `BindException: Address already in use`。传入 `0` 时由宿主操作系统动态分配高位空闲端口，通过 `tunnel.getLocalPort()` 即可反查实际分配的端口，兼顾安全性与并发隔离性。
