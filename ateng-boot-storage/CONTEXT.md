# Storage Context

`ateng-boot-storage` 是面向企业级应用的高性能、轻量级多源对象存储与安全预签名一体化 Starter，统一抽象 Local 本地磁盘存储与 S3 兼容协议，提供覆盖“统一双核协议抽象、扁平与动态多源智能双模配置、Local 预签名防盗链下载端点对齐、三位一体切源与四维上下文透传、Spring 原生资源与断点续传集成、全路径归一化安全防卫以及天然幂等删除”的全场景工业级对象存储底座。

## Language

**StorageDriver**:
底层存储协议驱动 SPI，统一抽象物理落盘与网络对象存储交互协议（如 `LocalDriver`、`S3Driver`），定义原子存储操作集（上传、流读取、元数据获取、存在性检查、幂等删除与批量删除）。
_Avoid_: StorageAdapter, StorageClientImpl, FileDriver

**StorageTemplate**:
面向业务开发者的受管高阶操作门面，负责参数防御清洗、Web 文件适配（`MultipartFile`）、MIME 自动推导、多源路由分发与业务异常转译。
_Avoid_: FileManager, UploadHelper, StorageUtil, FileService

**StorageResource**:
实现 Spring 原生 `Resource` 与 `WritableResource` 接口的强类型资源载荷，封装底层流、大小与 Content-Type，支持 Spring MVC Controller 极简流式输出与 HTTP Range 断点续传。
_Avoid_: FileResponse, DownloadResult, StorageStream

**StorageObject**:
表示存储介质中对象的强类型元数据容器，封装对象路径、文件名、字节大小、Content-Type、ETag 与最后修改时间等。
_Avoid_: FileInfo, FileDto, BlobData

**PresignedUrl**:
具有访问或上传时效的预签名凭证 URL 载荷，云端 S3 基于 V4 签名实现，Local 基于内置轻量 HMAC-SHA256 签名端点 100% 行为对齐。
_Avoid_: TempUrl, DownloadUrl, SignedLink

**LocalDriver**:
基于 Java NIO 的本地磁盘存储驱动，深度整合路径穿越防卫清洗、基于文件扩展名的标准 MIME 推导与分布式天然幂等删除保障。
_Avoid_: DiskDriver, FileSystemDriver, NativeFileAdapter

**S3Driver**:
基于官方精简版 AWS SDK for Java 2.x 的云原生对象存储驱动，彻底剔除庞大的 Netty NIO 传输层，通吃 MinIO、AWS S3、阿里云 OSS、腾讯云 COS 与华为云 OBS。
_Avoid_: MinioDriver, OssDriver, AwsClient

**StorageContextHolder**:
线程级当前存储源切换上下文持有者，原生接入 Core 模块的受管异步线程池四维快照透传。
_Avoid_: StorageThreadLocal, DynamicStorageManager

**@Storage**:
声明式多存储源切换注解，支持标注于 Spring Service / Controller 类或方法上。
_Avoid_: SwitchStorage, DS

**StorageException**:
存储模块受管业务异常根基类，继承自 Core 模块 `BusinessException`，配套标准 `StorageResultCode`。
_Avoid_: FileException, S3Exception
