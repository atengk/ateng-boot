# ateng-boot-sample-ssh 基础设施自验集成工程

> 专一面向生产就绪的 SSH 协议远程命令执行与网络穿透自验沙箱：覆盖四位一体多主机拓扑、受管会话缓存池复用、RFC 4254 孤儿进程超时信号防卫、原生 Sudo 提权安全管道与密码内存抹零、集群多节点并发巡检、长任务流式日志捕获、本地端口转发受管句柄与两级 ProxyJump 跳板机级联穿透 100% 端到端实测验证。

---

## 1. 场景定位与功能矩阵

`ateng-boot-sample-ssh` 提供了工业级规范且完全离线自洽的集成验证沙箱：
- **纯内存嵌入式 SshServer 极速自验**：零物理网络与外部主机依赖，秒级独立离线全绿运行；
- **四位一体多主机拓扑**：透明打通缺省配置、YAML 命名注册表、线程上下文动态切源与 CMDB 纯内存值对象；
- **原生 Sudo 提权安全管道**：基于 `-S -p ''` 协议管道，密码使用完毕即时执行内存物理擦除抹零；
- **集群并发巡检**：基于受管线程池设施并发下发任务，全链路透传 Core 四维上下文快照；
- **受管本地端口转发**：支持操作系统分配随机可用端口（`localPort=0`），句柄实现 `AutoCloseable` 并在停机时序 1/3 优先确定性释放；
- **ProxyJump 级联穿透**：原生支持级联多跳跳板机（Client -> Jump1 -> Jump2 -> Target）拓扑路由与双向生命周期级联清理。

---

## 2. 10 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 同步短命令执行 | `SshTemplate.exec` | `POST /api/ssh/exec` | 强类型返回 `SshExecResult`，捕获 `exitCode=0` 与标准输出内容 |
| **02** | 原生 Sudo 提权安全管道 | `SshTemplate.execSudo` | `POST /api/ssh/exec-sudo` | 建立标准输入提权管道，远端成功提权执行，密码内存即时抹零 |
| **03** | 集群多主机并发巡检 | `SshTemplate.execBatchNamed` | `POST /api/ssh/exec-batch` | 联动 Core 受管线程池并发巡检多节点，返回节点映射字典 |
| **04** | 长任务流式实时按行消费 | `SshTemplate.execStream` | `POST /api/ssh/stream-log` | 实时按行回调消费远端滚动日志，返回逐行捕获行列表 |
| **05** | CMDB 纯内存资产动态直连 | `SshServerInfo` 不变值对象 | `POST /api/ssh/dynamic-connect` | 纯内存解析凭据与宽容自适应主机指纹，执行成功 |
| **06** | 受管本地端口转发隧道 | `SshTemplate.openLocalTunnel` | `POST /api/ssh/tunnel/open` | 本地端口设为 0，由操作系统动态分配可用随机端口并成功反查 |
| **07** | 受管端口转发对称释放 | `SshScenarioService.closeManagedTunnel` | `POST /api/ssh/tunnel/close` | 对称释放受管隧道，物理关闭底层 Socket 监听，防端口泄漏 |
| **08** | 线程上下文动态切源 | `SshContextHolder.runWith` | `POST /api/ssh/context-switch` | 线程级透明切源至指定节点，作用域结束后自动复位 |
| **09** | ProxyJump 两级跳板机级联穿透 | `SshServerInfo.jumpHost` | `SshScenarioService.runCascadingProxyJump` | 成功穿透两级跳板机（Jump1 -> Jump2 -> Target）并执行命令 |
| **10** | 瞬态单次任务隔离执行 | `SshTemplate.execOnce` | `SshScenarioService.runTransientTask` | 执行完毕立即确定性物理关闭断开，零残留会话与句柄 |
| **11** | 端口转发真实双向 TCP 流量 | `SshTunnelHandle` + `Socket` | `SshScenarioService.forwardAndQueryTcp` | 本地客户端向本地转发端口发起 TCP 请求，成功透传至远端 Echo 服务 |
| **12** | JSR-303 参数格式校验 Fail-Fast | Spring MVC `@Validated` + `@NotBlank` | `POST /api/ssh/exec` 传入空白命令 | 前置卫语句拦截，Fail-Fast 触发参数校验异常，统一返回业务错误报文 |

---

## 3. 本地启动与实测体验

### 1. 启动服务
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-ssh spring-boot:run
```

### 2. 同步命令执行
```bash
curl -X POST -H "Content-Type: application/json" -d '{"command":"uname -a"}' http://localhost:8080/api/ssh/exec
```

### 3. Sudo 提权执行
```bash
curl -X POST -H "Content-Type: application/json" -d '{"command":"systemctl restart nginx","sudoPassword":"YOUR_PASSWORD"}' http://localhost:8080/api/ssh/exec-sudo
```
