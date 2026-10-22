# FTP 远程文件传输与连接池治理

基于 Apache Commons Net 与 Commons Pool2 构建的工业级 FTP/FTPS 双模远程文件传输底座，提供高并发连接池化、对称操作门面、多节点动态切源与极端网络边界防御。

## Language

**FTP Client Pool (连接池)**:
专职复用与健康保活 stateful FTP 客户端会话的受管资源池。
_Avoid_: 连接组, 会话池

**Passive Mode (被动模式)**:
客户端向服务端开辟的数据端口主动发起连接的数据传输模式（PASV）。
_Avoid_: 客户端模式, 反向模式

**Explicit FTPS (显式 FTPS)**:
连接标准 21 端口并通过 AUTH TLS 指令协商升级加密通道的 FTPS 规范。
_Avoid_: 动态 TLS, 模式 1 FTPS

**Implicit FTPS (隐式 FTPS)**:
直接连接专用 990 端口并在连接建立之初强制 TLS 握手的 FTPS 规范。
_Avoid_: 静态 TLS, 模式 2 FTPS

**Data Channel Protection (数据通道保护)**:
FTPS 会话中由 PROT P 指令控制的对数据传输通道进行全链路 TLS 加密的保护级别。
_Avoid_: 数据加密, 通道保密

**Atomic Upload (原子上传)**:
上传期间先写入临时后缀文件，待数据流完整闭合后再执行原子 rename 的防脏读机制。
_Avoid_: 事务上传, 临时上传

**Machine Listing (机器列表)**:
基于 RFC 3659 MLSD 指令返回的标准格式化、毫秒级与 UTC 时间戳的文件元数据列表。
_Avoid_: 机器目录, 系统列表

**Wire Transcoding (线缆转码)**:
将包含多字节中文字符的路径转译为 RFC 959 规范要求的 8-bit ISO-8859-1 协议线缆字节。
_Avoid_: 路径编码, 字符转换

**NAT Workaround (NAT 穿透矫正)**:
强制客户端被动模式忽略服务端响应的内网 IP 并使用控制连接目标主机的地址修正策略。
_Avoid_: NAT 欺骗, IP 替换
