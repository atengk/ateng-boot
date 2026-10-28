# Storage Context

`ateng-boot-storage` 是面向企业级应用的高性能、轻量级、多源对象存储 Starter 套件，提供覆盖“Local 本地存储与 AWS S3 兼容协议统一驱动、排除 Netty 零膨胀、智能双模平铺与多源配置、内置轻量 HMAC-SHA256 预签名下载端点、三位一体动态切源与四维上下文透传、Spring 原生 Resource 桥接与 HTTP Range 206 断点续传、全路径安全防卫与删除操作天然幂等”的全场景一体化解决方案。

## Language

**StorageDriver**:
底层存储协议驱动 SPI 接口，统一定义文件落盘与云原生对象交互标准契约（如 `LocalDriver`、`S3Driver`），支持单源与多源扩展。
_Avoid_: StorageAdapter, StorageClientImpl, FileDriver

**StorageTemplate**:
面向业务开发者的受管高阶操作门面，负责参数防御清洗、MIME 自动推导、Spring Web 表单适配、多存储源路由调度与异常统一转译。
_Avoid_: FileManager, UploadHelper, StorageUtil, FileService

**StorageResource**:
实现 Spring 原生 `Resource` 与 `WritableResource` 接口的强类型资源载荷，桥接底层存储对象，支持 Spring MVC Controller 极简流式输出与 HTTP Range 206 断点续传。
_Avoid_: FileResponse, DownloadResult, StorageStream

**StorageObject**:
表示存储介质中对象的强类型元数据容器，封装对象相对路径、字节大小、Content-Type、ETag、最后修改时间等。
_Avoid_: FileInfo, FileDto, BlobData

**PresignedUrl**:
具有访问或上传时效的预签名外链凭证模型，包含授权外链 URL、Unix 秒级过期时间戳与 HTTP 动作。云原生 S3 基于 SigV4 实现，Local 驱动基于内置 HMAC-SHA256 签名端点实现 100% 行为平替。
_Avoid_: TempUrl, DownloadUrl, SignedLink

**StorageContextHolder**:
线程级当前存储源路由标识持有者，内部采用 InheritableThreadLocal 并接入 Core 模块的 `StorageTaskDecorator`，支持受管线程池、异步任务与虚拟线程的全链路上下文透传。
_Avoid_: StorageThreadLocal, DynamicStorageManager

**@Storage**:
声明式多存储源切换注解，可标注于 Spring Controller / Service 类或方法上，由 `StorageAspect` 拦截并自动管理上下文压栈与出栈清理。
_Avoid_: SwitchStorage, DS

**StorageDriverFactory**:
多存储源驱动容器与生命周期管理器，负责多命名驱动实例的预热缓存、主驱动定位与容器销毁时的优雅停机资源释放。
_Avoid_: DriverRegistry, DriverPool, StorageManager

**LocalStorageDownloadController**:
Local 存储内置的预签名防盗链只读安全下载端点，校验 HMAC-SHA256 签名合法性与有效期限，执行二次路径清洗与流式输出。
_Avoid_: FileDownloadServlet, StaticFileServer

**LocalStorageUploadController**:
Local 存储内置的预签名直传写入安全控制器，基于 HTTP PUT 接收客户端直传数据，校验 HMAC-SHA256 签名、时效、限定 MIME 与体积上限，并联动 StorageGuard 执行魔数防卫。
_Avoid_: FileUploadServlet, LocalUploadReceiver

**PresignedUploadRequest**:
构建预签名直传外链的强类型入参模型，封装目标路径、授权有效时长、严格限制的 MIME 类型、最大允许字节大小及必须携带的 HTTP 报头。
_Avoid_: UploadParam, DirectUploadDto, SignRequest

**StorageHashUtil**:
基于原生 NIO Channel 与流式分块的高性能散列计算工具，提供零内存拷贝的 MD5、SHA-256、CRC32 摘要计算与秒传判重契约支撑。
_Avoid_: HashHelper, DigestUtil, ChecksumTool

**StorageSecurityUtil**:
存储安全防卫与路径规范化工具，提供全路径归一化、剥除相对路径与前置斜杠、文件名防御清洗及 HMAC-SHA256 签名验签算法。
_Avoid_: PathSecurityHelper, SafePathUtil

**ChunkedUpload**:
大文件分片上传与分块暂存抽象，封装全局唯一的上传凭证与分片生命周期状态。
_Avoid_: MultipartContext, SliceUpload, ChunkSession

**StorageGuard**:
文件安全防卫与真实内容特征嗅探器，基于魔数比对与扩展名一致性校验拦截伪装文件。
_Avoid_: FileValidator, SecurityChecker, FileFilter

**StorageEvent**:
存储介质生命周期领域事件，在对象上传完成或删除后广播给 Spring 容器，包含上传与删除两类生命周期动作。
_Avoid_: FileEvent, StorageMessage, StorageCallback

**StorageException**:
存储模块受管业务异常根基类，继承自 Core 模块 `BusinessException`，支持标准的业务错误码与上下文溯源。
_Avoid_: FileException, S3Exception

**StorageHealthIndicator**:
基于 Spring Boot Actuator 的存储健康状态探测器，提供 Local 本地物理目录写权限探测与 S3 存储桶可达性探查。
_Avoid_: StorageChecker, HealthProbe

**StorageClass**:
云端对象存储分层类别，标定对象在云存储中的访问频次与生命周期成本策略（如 STANDARD 标准、STANDARD_IA 低频、GLACIER 归档等）。
_Avoid_: StorageTier, ObjectTier, FileClass

**ServerSideEncryption**:
对象持久化至云存储盘时的透明服务端加密策略，支持 SSE-S3 (AES256) 与 SSE-KMS 密钥托管。
_Avoid_: DiskEncrypt, S3Crypto, EncryptionMode

**ObjectTagging**:
S3 对象业务标签键值对集合，用于云端生命周期自动归档流转规则匹配与多租户成本精准分摊。
_Avoid_: FileTags, MetaTags, Labels

**ByteRange**:
对象范围读取字节区间模型，定义局部读取（Partial Download）的起始偏移量与字节长度，支撑音视频 Seek 拖拽与大文件切片拉取。
_Avoid_: ContentRange, ReadOffset, SliceRange

**BucketPolicy**:
存储桶生命周期与初始化运维策略，定义应用启动与初次调用时存储桶的存在性探测校验与按需自愈建桶规则。
_Avoid_: BucketInit, BucketLifecycle, BucketCreator

**PresignedDownloadRequest**:
构建预签名只读下载外链的强类型入参模型，封装目标路径、授权有效时长以及响应头重写配置（如强制触发附件下载的 Content-Disposition 与友好文件名）。
_Avoid_: DownloadParam, PresignGetOption, UrlSignRequest

**PublicUrl**:
面向公开存储桶（Public Bucket）或已挂载 CDN 加速域名的静态对象永久直接访问链接，无签名时间戳与凭据附带，永久有效。
_Avoid_: StaticUrl, DirectLink, ForeverUrl

**CannedAcl**:
对象存储预置访问控制策略（如 private 私有、public-read 公共读等），用于在对象上传入库时即时赋予公网直接访问授权。
_Avoid_: FilePermission, S3Acl, BucketAcl



