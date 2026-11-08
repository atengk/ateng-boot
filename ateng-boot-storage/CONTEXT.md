# Storage Context

`ateng-boot-storage` 是面向企业级应用的多源对象存储与安全预签名限界上下文，统一抽象本地磁盘（Local）与标准 S3 兼容协议驱动，为业务系统提供文件存取、断点续传、分片合并、预签名直传与安全防卫的一致性领域契约。

## Language

**StorageDriver**:
底层存储协议驱动 SPI 接口，统一定义本地磁盘与云端 S3 对象交互标准契约（如 `LocalDriver`、`S3Driver`）。
_Avoid_: StorageAdapter, StorageClientImpl, FileDriver

**StorageOperations**:
存储操作高阶门面契约接口，定义上传、下载、预签名、分片、标签与多源路由的对称操作语义。
_Avoid_: FileService, StorageService, BlobOperations

**StorageTemplate**:
面向业务开发者的受管高阶操作门面，负责参数防卫清洗、安全嗅探联动、多存储源路由调度、领域事件广播与异常统一转译。
_Avoid_: FileManager, UploadHelper, StorageUtil

**StorageResource**:
实现 Spring 原生 `Resource` 与 `WritableResource` 接口的强类型资源载荷，桥接底层存储对象与 HTTP Range 206 断点续传响应。
_Avoid_: FileResponse, DownloadResult, StorageStream

**StorageObject**:
表示存储介质中对象或虚拟目录的强类型元数据容器，封装相对路径、字节大小、Content-Type、ETag、用户扩展元数据与冷热分层加密属性。
_Avoid_: FileInfo, FileDto, BlobData

**PresignedUrl**:
具有访问或直传时效的预签名外链凭证模型，包含授权外链 URL、过期时间戳、HTTP 动作与必带请求头。
_Avoid_: TempUrl, DownloadUrl, SignedLink

**PresignedUploadRequest**:
构建预签名直传写入外链的强类型请求模型，定义目标路径、有效时长、严格限制的 MIME 类型、最大允许字节数及必带报头。
_Avoid_: UploadParam, DirectUploadDto, SignRequest

**PresignedDownloadRequest**:
构建预签名只读下载外链的强类型请求模型，定义目标路径、有效时长以及响应头重写规则（如友好中文下载文件名）。
_Avoid_: DownloadParam, PresignGetOption, UrlSignRequest

**StorageDriverFactory**:
多存储源驱动注册表与生命周期管理器，负责多命名驱动实例的预热检索、主驱动定位与容器停机时的资源释放。
_Avoid_: DriverRegistry, DriverPool, StorageManager

**StorageContextHolder**:
线程级当前存储源路由标识持有者，配合 `StorageTaskDecorator` 支撑异步线程池与虚拟线程的存储源上下文透传。
_Avoid_: StorageThreadLocal, DynamicStorageManager

**@Storage**:
声明式多存储源切换注解，标注于类或方法上以在调用周期内绑定指定命名存储源。
_Avoid_: SwitchStorage, DS

**LocalStorageDownloadController**:
Local 存储内置的预签名防盗链只读安全下载端点，校验签名合法性与有效期限并执行流式输出。
_Avoid_: FileDownloadServlet, StaticFileServer

**LocalStorageUploadController**:
Local 存储内置的预签名直传写入安全端点，接收客户端 HTTP PUT 直传或分片直传数据并执行签名、配额与魔数校验。
_Avoid_: FileUploadServlet, LocalUploadReceiver

**StorageGuard**:
文件安全防卫与真实内容特征嗅探器，基于头部魔数比对与扩展名白名单校验拦截伪装文件。
_Avoid_: FileValidator, SecurityChecker, FileFilter

**StorageFileType**:
预置文件二进制魔数特征枚举，定义常见文档、多媒体、压缩包及高危可执行脚本的头部字节签名与标准 MIME 映射。
_Avoid_: FileFormatEnum, MimeTypeEnum, MagicNumber

**StorageChunkCleaner**:
孤儿分片碎片生命周期守护组件，定期扫描并物理回收超时未完成合并的临时分片会话。
_Avoid_: OrphanCleaner, MultipartGarbageCollector, ChunkSweeper

**StorageHashUtil**:
流式零拷贝散列计算工具，提供 MD5、SHA-1、SHA-256、CRC32 摘要计算与秒传指纹抗时序比对。
_Avoid_: HashHelper, DigestUtil, ChecksumTool

**HashAlgorithm**:
存储散列摘要算法枚举，统一标识 MD5、SHA-1、SHA-256 与 CRC32 指纹计算策略。
_Avoid_: DigestType, ChecksumMode

**StorageSecurityUtil**:
存储安全防卫与路径规范化工具，提供全路径归一化、防目录穿越清洗与 HMAC-SHA256 常量时间签名验签。
_Avoid_: PathSecurityHelper, SafePathUtil

**StorageEvent**:
存储介质生命周期领域事件基类，派生出 `StorageUploadedEvent` 与 `StorageDeletedEvent` 在对象写入或删除完成后广播。
_Avoid_: FileEvent, StorageMessage, StorageCallback

**StorageHealthIndicator**:
基于 Spring Boot Actuator 的存储健康状态探针，提供本地物理目录读写权限探测与云端存储桶可达性探查。
_Avoid_: StorageChecker, HealthProbe

**StorageException**:
存储模块受管业务异常，继承自 `BusinessException` 并绑定 `StorageResultCode` 承载存储错误码与异常因果链。
_Avoid_: FileException, S3Exception

**ContractAuditSnapshot**:
企业电子合同归档审计快照强类型 DTO/VO 模型，承载合同编号、标题、签署人、归属部门与审计状态，挂载 JSR-303 格式校验注解。
_Avoid_: ContractSnapshotDto, TestUser, AuditData

**StorageObjectController**:
样例工程核心对象存取领域控制器（`/sample/storage/object/*`），负责电子合同上传、自定义元数据、批注与审计快照便捷读写、零拷贝复制与归档移动、目录列举与分页检索及天然幂等删除。
_Avoid_: FileController, ObjectApi, StorageController

**StorageTransferController**:
样例工程流式传输与秒传领域控制器（`/sample/storage/transfer/*`），负责 Spring StorageResource HTTP Range 206 断点续传、Byte-Range 字节范围读取、未知流长双阶自适应流式分片上传防 OOM、多凭证流式打包 ZIP 与知识库极速秒传。
_Avoid_: FileTransferController, DownloadController, TransferApi

**StoragePresignedController**:
样例工程预签名外链与直传领域控制器（`/sample/storage/presigned/*`），负责 Local 与 S3 预签名只读外链、友好中文文件名重写、客户端 HTTP PUT 预签名直传、公开素材公共读授权直传与永久直接访问直链。
_Avoid_: PresignController, DirectUploadController, LinkController

**StorageChunkController**:
样例工程大文件分片状态机领域控制器（`/sample/storage/chunk/*`），负责服务端分片会话生命周期（初始化、逐片上传、正序合并与缺片 40012 防卫）、端侧分块直传预签名外链、主动中止清理与后台孤儿碎片守护巡检。
_Avoid_: MultipartController, ChunkUploadController, SliceController

**StorageGovernanceController**:
样例工程存储多源与云原生治理领域控制器（`/sample/storage/governance/*`），负责声明式 @Storage 与编程式 use(...) 跨源双活备份、对象业务标签（Tagging）生命周期治理、冷热分层与加密探查、连通性探针 (ping) 与连接池/配置诊断字典提取。
_Avoid_: MultiSourceController, S3AdminController, StorageManageController
