# SSH 远程命令执行与安全文件传输基础设施

基于 Apache MINA SSHD 构建的工业级 SSH/SFTP 双模通信与远程治理底座，提供高并发会话池化、命令安全执行、流式文件传输、本地端口转发、Web 终端会话与动态多主机路由治理。

## Language

**SshServerInfo (目标主机描述符)**:
封装远程主机网络坐标、认证凭据、字符集与跳板机拓扑的不可变连接描述对象。
_Avoid_: SshConfig, SshNode, SshConnectionParam

**SshSessionPool (受管会话缓存池)**:
按目标主机坐标与凭据指纹缓存并复用高开销 SSH 会话连接的受管连接池设施。
_Avoid_: SshPool, 连接仓库, 会话缓存器

**SshPoolStats (会话池状态快照)**:
封装受管会话缓存池最大容量、当前条目数、活跃连接数、累计新建数、缓存命中数、淘汰数与建连失败数的只读诊断值对象。
_Avoid_: PoolMetrics, 连接池监控器, SessionCounter

**SshClientRegistry (命名主机注册表)**:
纳管静态声明的多命名 SSH 客户端会话资源并在运行时按需分发的受管注册中心。
_Avoid_: SshHostManager, SshServerMap, SshPoolRegistry

**SshContextHolder (线程切源上下文)**:
基于 ThreadLocal 存储当前线程默认目标主机命名标识的上下文持有者。
_Avoid_: SshRoutingContext, SshSessionHolder

**SshTemplate (SSH 核心门面)**:
对外提供命令执行、提权调度、跳板机穿透、端口转发隧道及交互式终端管理的对称执行门面。
_Avoid_: SshExecutor, SshManager, SshClientHelper

**SftpTemplate (SFTP 传输门面)**:
对外提供全量具名节点路由、流式上传下载、断点续传下载、符号链接治理、目录递归同步与文件权限元数据治理的对称文件操作门面。
_Avoid_: SftpUtil, SftpManager, SftpClientTemplate

**SshCommandInterceptor (命令执行拦截器)**:
在远程命令执行前后及异常阶段介入以实施高危指令阻断、安全审计与指标采集的扩展接口。
_Avoid_: 命令过滤器, SshFilter, ShellHook

**HighRiskCommandInterceptor (高危命令安全守卫)**:
基于内置毁灭性指令规则与自定义正则黑名单在命令下发前执行快速失败阻断的防御组件。
_Avoid_: 命令黑名单, 敏感词过滤, DangerousFilter

**BoundedByteArrayOutputStream (有界输出缓冲流)**:
限制单次同步命令标准输出与错误流最大内存驻留字节数并支持静默排空与截断标记的防 OOM 缓冲流。
_Avoid_: 截断流, 限流缓冲, MaxOutputBuffer

**SshExecResult (命令执行结果载荷)**:
封装远程命令执行退出码、标准输出、标准错误及耗时指标的强类型结果对象。
_Avoid_: CommandOutput, ExecResponse, SshResult

**SshExecOptions (命令执行选项配置)**:
封装命令执行工作目录、环境变量、超时阈值及解码字符集的富选项声明对象。
_Avoid_: CommandConfig, ExecParam, SshOptions

**SshTunnelHandle (受管隧道句柄)**:
封装本地或远程端口转发长连接会话，暴露 `TunnelMode` 方向模式并提供确定性资源释放与探活能力的受管句柄。
_Avoid_: SshTunnel, PortForwarder, TunnelSession

**TunnelMode (端口转发隧道方向模式)**:
声明 SSH 端口转发隧道方向语义的枚举（`LOCAL` 本地正向转发与 `REMOTE` 远程反向转发）。
_Avoid_: ForwardType, TunnelDirection, TunnelType

**SshShellHandle (受管终端会话句柄)**:
封装交互式 PTY 伪终端会话并支持窗口重设、流式收发字节帧的受管终端交互句柄。
_Avoid_: WebSshSession, TerminalHandle, PtySession

**SshShellOptions (终端规格选项配置)**:
封装 PTY 终端类型（如 xterm-256color）、行列尺寸、像素尺寸及环境变量的强类型配置对象。
_Avoid_: TerminalConfig, ShellParam, PtyOptions

**SftpFileInfo (SFTP 文件元数据)**:
封装远程 SFTP 文件名称、路径、大小、修改时间与 POSIX 权限位的只读元数据对象。
_Avoid_: SftpEntry, RemoteFile, SftpNode

**SftpProgressListener (传输进度监听器)**:
提供已传输字节数与总字节数实时回调的函数式传输进度观测接口。
_Avoid_: FileProgressCallback, SftpWatcher, TransferMonitor

**HostKeyPolicy (主机指纹校验策略)**:
控制连接服务端时对主机公钥指纹进行严格校验、宽容自适应接受或全局忽略的安全策略。
_Avoid_: HostKeyMode, StrictCheckingLevel

**Exec Channel (命令执行通道)**:
在 SSH 物理会话上开辟的用于单次非交互式远程命令或脚本执行的逻辑隔离通道。
_Avoid_: 命令会话, Shell通道, 进程通道

**SFTP Subsystem (SFTP 子系统)**:
在 SSH 物理会话上协商启动的用于文件系统抽象交互的二进制协议子系统通道。
_Avoid_: SFTP服务, 文件管道, 传输通道

**Atomic Upload (原子上传)**:
上传期间先写入临时后缀文件，待数据流完整闭合后再执行原子 rename 的防脏读机制。
_Avoid_: 临时上传, 事务写入, 安全覆盖

**Local Port Forwarding (本地端口转发)**:
将本地客户端指定端口的 TCP 流量经由安全 SSH 会话隧道透明代理至远程内网主机的机制。
_Avoid_: 本地映射, 端口穿透, 内部代理

**Remote Port Forwarding (远程端口转发)**:
在远端 SSH 服务端监听绑定端口并将入站 TCP 流量经由安全 SSH 会话隧道反向转发至本地目标服务的穿透机制。
_Avoid_: 反向代理, 远端内网穿透, ReverseTunnel

**ProxyJump (跳板机穿透)**:
经由中间堡垒机/跳板机建立级联 SSH 会话并直达隔离内网目标主机的多跳代理机制。
_Avoid_: 堡垒机代理, 多跳中继, 级联转发

**SshBatchExecResult (批量巡检聚合回执)**:
封装集群批量命令执行结果强类型聚合载荷，提供节点成功/失败计数、明细映射、整体耗时与快速判断断言。
_Avoid_: BatchResult, CommandBatchResponse, SshClusterOutput

**SshBatchOptions (批量执行控制选项)**:
封装批量巡检并发度限制、快速失败策略及节点超时行为的选项模型。
_Avoid_: BatchConfig, SshBatchParam

**SftpProgressThrottler (流式传输进度节流防抖器)**:
基于传输百分比步长与最小时间间隔进行防抖节流的传输进度监听装饰器。
_Avoid_: ProgressFilter, SftpRateLimiter

**Connectivity Probe (连通性探针)**:
仅完成 TCP 建连与 SSH 凭据身份验证协商而不开启业务通道的无副作用健康探测机制。
_Avoid_: Ping, SshCheck, HeartbeatChannel

**RFC 4254 Signal Guard (孤儿进程信号防卫)**:
命令超时熔断时向远端 Session 依次发送 RFC 4254 `TERM` 与 `INT` 信号主动终止子进程并随后物理销毁通道的防卫机制。
_Avoid_: SIGKILL暴力切断, 进程挂死, 孤儿进程

**In-Memory Server Key Verifier (云原生纯内存主机指纹校验器)**:
专为 Kubernetes 等只读根文件系统环境设计、100% 内存驻留主机公钥指纹、绝不尝试写盘 `known_hosts` 的主机密钥校验器。
_Avoid_: 磁盘指纹库, known_hosts写入, 免检穿透

**Three-Step Graceful Shutdown (确定性三步优雅停机)**:
应用销毁阶段由 `SshLifecycleManager` 驱动的【1. 释放活跃 PTY 终端与端口转发隧道 -> 2. 安全注销 SshSessionPool 全部池化会话 -> 3. 停止底层 MINA SshClient NIO 内核】确定性资源回收时序。
_Avoid_: 暴力停机, 随机注销, 端口悬挂

**SshCommandController (命令执行领域控制器)**:
纳管单机短命令同步/异步下发、原生 Sudo 提权口令抹零、多行脚本管道规范化与日志按行流式消费的接入层控制器。
_Avoid_: 胖控制器, 命令执行端点, SshWebController

**SshClusterController (集群巡检领域控制器)**:
纳管多主机受控批量并发巡检、CMDB 纯内存资产直连与线程上下文动态切源的接入层控制器。
_Avoid_: 批量操作端点, 集群管理器, ClusterWebController

**SshTunnelController (网络穿透领域控制器)**:
纳管本地与远程端口转发受管句柄生命周期建立释放、两级跳板机级联穿透与正反向真实 TCP 流量交互的接入层控制器。
_Avoid_: 端口映射接口, 跳板机控制器, TunnelWebController

**SftpDocumentController (SFTP 文档领域控制器)**:
纳管文件表单原子上传、对称流式下载、纯文本快捷读写、尾部增量追加、跨目录移动、符号链接创建读取与文件元数据生命周期的接入层控制器。
_Avoid_: 文件上传控制器, SftpWebController, 文档网关

**SftpSyncController (SFTP 目录同步控制器)**:
纳管目录树整包双向镜像上传与下载同步、DFS 递归删除非空目录、大文件进度节流防抖与断点续传下载的接入层控制器。
_Avoid_: 目录批量控制器, SyncWebController, 同步接口

**Resume Download (断点续传下载)**:
基于本地已存在文件字节偏移量向远程 SFTP 发起定点续读并追加写入的容错传输机制（支持满额幂等跳过与本地字节超限快速失败）。
_Avoid_: 增量下载, 分片拉取, OffsetDownload

**SshTerminalController (WebSSH 终端诊断控制器)**:
提供 WebSSH 终端规格、默认行列尺寸与 WebSocket 端点运行状态只读诊断的接入层控制器。
_Avoid_: WebSshWebController, 终端控制器, 终端网关

**SSH Typed DTO Matrix (SSH 强类型入参 DTO 矩阵)**:
遵循 ADR-0010 与 ADR-0027，在网络接入层对所有 SSH/SFTP 操作请求进行 JSR-303 Fail-Fast 校验的 24 大专用请求数据对象集合。
_Avoid_: 裸参数透传, Entity 越界, 泛型 Map 传参

**SSH Typed VO Matrix (SSH 强类型业务 VO 矩阵)**:
对外提供安全脱敏、消除底层 MINA SSHD SDK 内部引用与连接泄露的 8 大业务响应数据对象集合（含 `SshPoolStatsVO`、`SftpDownloadResumeResultVO`）。
_Avoid_: Entity 泄漏, 原生 Session 暴露, 模糊响应
