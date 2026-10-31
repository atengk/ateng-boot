# SSH 远程命令执行与安全文件传输基础设施

基于 Apache MINA SSHD 构建的工业级 SSH/SFTP 双模通信与远程治理底座，提供高并发会话池化、命令安全执行、流式文件传输、本地端口转发、Web 终端会话与动态多主机路由治理。

## Language

**SshServerInfo (目标主机描述符)**:
封装远程主机网络坐标、认证凭据、字符集与跳板机拓扑的不可变连接描述对象。
_Avoid_: SshConfig, SshNode, SshConnectionParam

**SshSessionPool (受管会话缓存池)**:
按目标主机坐标与凭据指纹缓存并复用高开销 SSH 会话连接的受管连接池设施。
_Avoid_: SshPool, 连接仓库, 会话缓存器

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
对外提供流式上传下载、目录递归同步与文件权限元数据治理的对称文件操作门面。
_Avoid_: SftpUtil, SftpManager, SftpClientTemplate

**SshExecResult (命令执行结果载荷)**:
封装远程命令执行退出码、标准输出、标准错误及耗时指标的强类型结果对象。
_Avoid_: CommandOutput, ExecResponse, SshResult

**SshExecOptions (命令执行选项配置)**:
封装命令执行工作目录、环境变量、超时阈值及解码字符集的富选项声明对象。
_Avoid_: CommandConfig, ExecParam, SshOptions

**SshTunnelHandle (受管隧道句柄)**:
封装本地端口转发长连接会话并提供确定性资源释放与探活能力的受管句柄。
_Avoid_: SshTunnel, PortForwarder, TunnelSession

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
