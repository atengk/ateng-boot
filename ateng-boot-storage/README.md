# ateng-boot-storage 基础设施 Starter

> 企业级轻量多源对象存储与安全预签名一体化 Starter，统一抽象 Local 本地磁盘存储与标准 S3 兼容协议（MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS），提供路径遍历安全防卫、文件魔数嗅探、极速流式散列计算与秒传、分片直传预签名、孤儿碎片治理守护、Spring Boot Actuator 健康探针与 Spring 原生资源深度集成，天然具备分布式幂等删除保障。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **文件上传** | 流式/字节/文件/表单上传 | `StorageTemplate` | `ateng.boot.storage.enabled` | `upload(String, InputStream)` / `upload(String, byte[])` / `upload(String, File)` / `upload(String, MultipartFile)` / `upload(String, StorageObject)`，自动推导 MIME 与安全创建父目录 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **自定义元数据** | 对象用户扩展元数据注入 | `StorageTemplate`<br>`StorageObject` | - | `upload(String, InputStream, Map<String, String>)` / `upload(String, MultipartFile, Map<String, String>)`，携带扩展元数据（S3 云端原生持久化，Local 内存即时返回）并在 `StorageObject.getUserMetadata()` 反查 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **分片上传** | 服务端大文件分片合并暂存 | `StorageTemplate` | - | `initiateChunkedUpload(String)` / `uploadChunk(String, int, InputStream, long)` / `uploadChunk(String, int, byte[])` / `completeChunkedUpload(String, String, int)` / `abortChunkedUpload(String)`，1-based 序号连续性强校验与原子删除 | `StorageTemplateChunkedTest` / `SampleStorageIntegrationTest` |
| **预签名只读** | 安全只读防盗链预签名外链 | `StorageTemplate` | `ateng.boot.storage.local.download-endpoint`<br>`ateng.boot.storage.local.secret-key` | `getPresignedUrl(String, Duration)`，返回带有效期的强类型 `PresignedUrl`，Local 驱动基于 HMAC-SHA256 签名，S3 驱动原生对接云端凭据 | `LocalDriverTest` / `S3DriverTest` / `SampleStorageIntegrationTest` |
| **客户端直传预签名** | 预签名安全直传写入外链凭据 | `StorageTemplate`<br>`PresignedUploadRequest` | `ateng.boot.storage.local.upload-endpoint` | `getPresignedUploadUrl(String, Duration)` / `getPresignedUploadUrl(PresignedUploadRequest)`，前端直接向存储端点发起 HTTP PUT 直传数据，完全绕过业务后端转发 | `LocalStorageUploadControllerTest` / `S3DriverTest` / `SampleStorageIntegrationTest` |
| **分片直传预签名** | 端侧大文件分块直传预签名 | `StorageTemplate` | `ateng.boot.storage.local.upload-endpoint` | `getPresignedUploadPartUrl(String, String, int, Duration)`，生成指定分片写入外链，前端并发 PUT 直传各分片后通知后端合并 | `LocalStorageUploadControllerTest` / `S3DriverTest` / `SampleStorageIntegrationTest` |
| **流式散列与秒传** | 原生零拷贝流式散列计算 | `StorageHashUtil`<br>`StorageTemplate`<br>`HashAlgorithm` | - | `StorageHashUtil.sha256Hex(InputStream)` / `md5Hex` / `sha1Hex` / `crc32`；`storageTemplate.calculateHash(String, HashAlgorithm)`，流指针零截断与极速秒传指纹比对 | `StorageHashUtilTest` / `SampleStorageIntegrationTest` |
| **文件下载** | 流式/字节/落盘/响应下载 | `StorageTemplate` | - | `download(String)` (返回 `InputStream`) / `downloadBytes(String)` (返回 `byte[]`) / `download(String, File)` / `download(String, OutputStream)`，严格遵循 try-with-resources 资源确定性释放 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **文件存在与元数据** | 存在性与属性探测 | `StorageTemplate` | - | `exists(String)` / `getMetadata(String)` / `getUrl(String)`，统一轻量返回 `StorageObject` 强类型元数据 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **便捷载荷存取** | 字符串/字节/JSON便捷存取 | `StorageTemplate` | - | `uploadString(String, String)` / `downloadString(String)` / `uploadBytes(String, byte[])` / `downloadBytes(String)` / `uploadJson(String, Object)` / `downloadJson(String, Class<T>)`，免除样板代码 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **对象复制与移动** | 跨路径秒传复制与原子移动 | `StorageTemplate` | - | `copy(String, String)` / `move(String, String)`，S3 服务端免流秒传，Local NIO 零拷贝转移并安全移除源文件 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **目录检索与分页** | 树状前缀列举与标准分页检索 | `StorageTemplate`<br>`StorageObject` | - | `list(String)` / `list(String, boolean)` / `listPage(String, PageQuery)` / `listPage(String, PageQuery, boolean)`，统一区分文件与虚拟目录项并自动过滤系统隐藏项 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **打包归档** | 多文件纯流式 ZIP 打包响应 | `StorageTemplate` | - | `downloadAsZip(List<String>, OutputStream)` / `downloadAsZip(Map<String, String>, OutputStream)` / `downloadAsZip(List<String>, HttpServletResponse, String)`，内存受控、零临时文件与重名自动消解 | `StorageTemplateZipTest` / `SampleStorageIntegrationTest` |
| **天然幂等删除** | 单文件/批量/目录幂等删除 | `StorageTemplate` | - | `delete(String)` / `deleteBatch(List<String>)` / `deleteDirectory(String)`，天然幂等语义，文件不存在亦平滑成功 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |
| **前缀批量删除** | 目录级与前缀级流式批量删除 | `StorageTemplate` | - | `deletePrefix(String)`，流式切片批量清除指定前缀下的所有对象，严厉熔断空前缀与根目录防误删 | `StorageTemplateTest` / `LocalDriverTest` / `S3DriverTest` / `SampleStorageIntegrationTest` |
| **碎片治理守护** | 孤儿分片碎片生命周期治理 | `StorageChunkCleaner` | `ateng.boot.storage.chunk-cleaner.enabled`<br>`ateng.boot.storage.chunk-cleaner.retention`<br>`ateng.boot.storage.chunk-cleaner.cron` | 定时物理扫描并安全回收超期滞留的 `.chunks` 孤儿碎片，彻底杜绝服务器磁盘与云端存储悬挂泄漏 | `StorageChunkCleanerTest` |
| **存储健康探针** | Actuator 存储健康状态感知 | `StorageHealthIndicator` | `management.health.storage.enabled` | 深度集成 Spring Boot Actuator `/actuator/health`，实时探测 Local 磁盘读写或云端 S3 Bucket 可用性并输出诊断指标 | `StorageHealthIndicatorTest` / `SampleStorageIntegrationTest` |
| **多源路由调度** | 动态命名存储源切换门面 | `StorageTemplate` | `ateng.boot.storage.primary`<br>`ateng.boot.storage.sources.*` | `use(String sourceName)`，返回绑定目标存储源的独立不可变操作门面，支持平铺单源与多源字典共存 | `StorageRoutingTest` / `SampleStorageIntegrationTest` |
| **声明式多源** | 声明式数据源路由注解 | `@Storage`<br>`StorageAspect` | `ateng.boot.storage.sources.*` | 可声明于 Spring Service 类或方法上，AOP 自动解析并绑定当前线程存储源上下文，执行完毕严格恢复 | `StorageRoutingTest` / `SampleStorageIntegrationTest` |
| **多源上下文** | 存储数据源上下文保持器 | `StorageContextHolder` | - | `set(String)` / `get()` / `clear()` / `wrap(Runnable)`，依托 ThreadLocal 保证多源切换无状态与跨线程安全透传 | `StorageRoutingTest` |
| **上下文透传** | 存储多源异步上下文装饰器 | `StorageTaskDecorator` | - | 将当前线程活动存储源标识快照透传至受管线程池异步任务中，执行完毕后无条件清理现场 | `StorageRoutingTest` |
| **本地受控端点** | 本地文件安全下载与直传端点 | `LocalStorageDownloadController`<br>`LocalStorageUploadController` | `ateng.boot.storage.local.download-endpoint`<br>`ateng.boot.storage.local.upload-endpoint` | 提供 `/storage/local/download`、`/storage/local/upload` 与 `/storage/local/upload-part` 端点，基于 HMAC-SHA256 防篡改与时效验证 | `LocalStorageDownloadControllerTest` / `LocalStorageUploadControllerTest` / `SampleStorageIntegrationTest` |
| **本地驱动 SPI** | 本地文件系统存储驱动 | `LocalDriver` | `ateng.boot.storage.local.base-path`<br>`ateng.boot.storage.local.domain` | 本地存储驱动，封装文件读写、父目录创建、路径穿越清洗、分片零拷贝合并与基于 HMAC 签名的安全外链 | `LocalDriverTest` / `SampleStorageIntegrationTest` |
| **S3 协议驱动 SPI**| 云原生 S3 兼容协议驱动 | `S3Driver` | `ateng.boot.storage.s3.endpoint`<br>`ateng.boot.storage.s3.bucket`<br>`ateng.boot.storage.s3.access-key`<br>`ateng.boot.storage.s3.secret-key`<br>`ateng.boot.storage.s3.region`<br>`ateng.boot.storage.s3.path-style`<br>`ateng.boot.storage.s3.domain` | 统一抽象 MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS 等标准协议，支持 Path-Style 与 CDN 域名无缝切换 | `S3DriverTest` |
| **Spring 原生集成** | 原生 WritableResource 契约 | `StorageResource` | - | 实现 Spring `Resource` 与 `WritableResource`，支持 `storageTemplate.getResource(path)` 获得受管资源与 HTTP 206 响应 | `StorageResourceTest` / `SampleStorageIntegrationTest` |
| **文件安全防卫** | 文件类型与魔数嗅探防卫器 | `StorageGuard` | `ateng.boot.storage.guard.enabled`<br>`ateng.boot.storage.guard.strict-magic-check`<br>`ateng.boot.storage.guard.allowed-extensions` | 识别 15+ 种标准魔数与高危 PE/ELF/Shell 脚本二进制特征，基于 mark/reset 零流截断损耗防御伪装上传 | `StorageGuardTest` / `SampleStorageIntegrationTest` |
| **路径清洗** | 路径穿越防御清洗工具 | `StorageSecurityUtil` | - | 清洗清理 `../`、`./`、非法特殊字符与宿主系统特定隐藏文件，HMAC 常量时间比对防时序攻击 | `StorageSecurityUtilTest` |
| **事件通知** | 存储生命周期事件总线 | `StorageEvent`<br>`StorageUploadedEvent`<br>`StorageDeletedEvent` | - | 文件上传与删除成功后通过 Spring 原生事件广播通知，Fail-Safe 异常隔离不阻断主链路 | `StorageTemplateEventTest` / `SampleStorageIntegrationTest` |
| **因果异常体系** | 语义化异常与统一错误码 | `StorageException`<br>`StorageResultCode` | - | 将底层驱动抛出的各种 IO/网络/SDK 异常转译为语义化 `StorageException` 并 100% 保留因果堆栈链 | `StorageTemplateTest` / `SampleStorageIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-storage` 是面向生产就绪的高内聚、轻量级存储基础设施模块：
- **纯净依赖**：仅必需依赖 `ateng-boot-core`，零冗余第三方工具包，Spring Web 与 Spring Boot Actuator 作为可选依赖（optional）；
- **双核驱动**：统一抽象为 `LocalDriver`（本地磁盘文件系统）与 `S3Driver`（云原生 S3 兼容协议，精简版 AWS SDK v2）；
- **显式契约**：提供 `StorageTemplate` 高阶统一门面与 Spring 原生 `StorageResource`（实现 `Resource` 与 `WritableResource`），删除天然幂等。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-storage</artifactId>
</dependency>
```

---

## 2. 基础配置说明与生产范式

### 场景痛点
在单机部署、私有化交付或混合云架构中，存储介质常常面临频繁变更或多云混用；传统方案缺乏统一的配置结构与碎片治理策略，极易导致代码与特定 SDK 深度耦合或磁盘碎片膨胀。

### 配置范式
在 `application.yml` 中配置存储基础属性与扩展治理：

#### 方案 A：本地磁盘存储（默认）
```yaml
ateng:
  boot:
    storage:
      enabled: true
      type: local
      local:
        base-path: /data/storage
        domain: http://localhost:8080
        download-endpoint: /storage/local/download
        upload-endpoint: /storage/local/upload
        secret-key: YOUR_HMAC_SECRET_KEY
      chunk-cleaner:
        enabled: true                     # 开启孤儿分片碎片清理守护（默认 false）
        retention: 24h                    # 孤儿分片最大保留时间（默认 24h）
        cron: "0 0 3 * * ?"               # 每天凌晨 3 点自动执行清理

# 启用 Spring Boot Actuator 存储健康探针（可选）
management:
  health:
    storage:
      enabled: true
  endpoint:
    health:
      show-details: always
```

#### 方案 B：标准 S3 兼容协议（MinIO / AWS S3 / 阿里云 OSS / 腾讯云 COS / 华为云 OBS）
```yaml
ateng:
  boot:
    storage:
      enabled: true
      type: s3
      s3:
        endpoint: http://127.0.0.1:9000
        bucket: my-bucket
        access-key: YOUR_ACCESS_KEY
        secret-key: YOUR_SECRET_KEY
        region: us-east-1
        path-style: true
        domain: https://cdn.example.com
```

#### 方案 C：高阶多存储源双模配置（动态多源自动升阶）
```yaml
ateng:
  boot:
    storage:
      enabled: true
      primary: local
      sources:
        local:
          type: local
          local:
            base-path: /data/files
            domain: http://localhost:8080
        oss-public:
          type: s3
          s3:
            endpoint: https://oss-cn-hangzhou.aliyuncs.com
            bucket: app-public
            access-key: YOUR_ACCESS_KEY
            secret-key: YOUR_SECRET_KEY
            path-style: false
            domain: https://cdn.example.com
        minio-secure:
          type: s3
          s3:
            endpoint: http://127.0.0.1:9000
            bucket: app-secure
            access-key: minioadmin
            secret-key: minioadmin
            path-style: true
```

---

## 3. 基础文件流式上传 (`StorageTemplate.upload`)

### 场景痛点
传统文件上传往往需要自行处理目标父级目录创建、NIO 写入、流生命周期管理以及文件大小与 MIME 类型的推导，样板代码繁琐易错。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

public void uploadDocument() {
    byte[] content = "Hello Ateng Boot Storage!".getBytes(StandardCharsets.UTF_8);
    try (InputStream in = new ByteArrayInputStream(content)) {
        // 自动清洗路径、创建父目录、推导 text/plain 并写入本地磁盘
        StorageObject object = storageTemplate.upload("documents/2026/hello.txt", in);
        
        System.out.println("存储路径: " + object.getPath());
        System.out.println("文件名称: " + object.getFilename());
        System.out.println("文件大小: " + object.getSize());
        System.out.println("MIME类型: " + object.getContentType());
        System.out.println("ETag哈希: " + object.getEtag());
    }
}
```

### 预期呈现契约
控制台输出：
```text
存储路径: documents/2026/hello.txt
文件名称: hello.txt
文件大小: 25
MIME类型: text/plain
ETag哈希: 4a27546e15904620b7ea1e3458c97dd3
```

---

## 4. Web 表单上传与自定义扩展元数据 (`MultipartFile` / `userMetadata`)

### 场景痛点
Controller 层接收前端上传的 `MultipartFile` 时，极易受客户端伪造的相对路径注入攻击（如文件名携带 `../../`）；同时在企业级归档与检索场景中，往往需要向存储对象附加自定义业务标签（如操作人 `author`、业务单号 `bizId` 或所属环境 `env`），传统 SDK 缺乏强类型收敛支持。

### 核心代码范式
```java
@RestController
@RequestMapping("/api/storage")
public class FileController {

    @Autowired
    private StorageTemplate storageTemplate;

    // 1. 基础表单文件上传（自动安全防穿越清洗文件名）
    @PostMapping("/upload")
    public Result<StorageObject> upload(@RequestParam("file") MultipartFile file) {
        StorageObject object = storageTemplate.upload("uploads/avatars/", file);
        return Result.success(object);
    }

    // 2. 上传文件并携带用户自定义元数据
    @PostMapping("/upload-with-metadata")
    public Result<StorageObject> uploadWithMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam("path") String path,
            @RequestParam Map<String, String> userMetadata) {
        Map<String, String> customMeta = new HashMap<>(userMetadata);
        customMeta.remove("path");
        StorageObject object = storageTemplate.upload(path, file, customMeta);
        return Result.success(object);
    }
}
```

### 预期呈现契约
HTTP POST `/api/storage/upload-with-metadata` 携带 `author=Ateng` 与 `env=sample-test`，返回标准 JSON 响应：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "path": "custom/meta.txt",
    "filename": "meta.txt",
    "size": 21,
    "contentType": "text/plain",
    "etag": "d41d8cd98f00b204e9800998ecf8427e",
    "userMetadata": {
      "author": "Ateng",
      "env": "sample-test"
    }
  }
}
```
> [!NOTE]
> **自定义元数据持久化介质边界说明**：
> - **S3 协议驱动**：用户自定义元数据原生映射为云端对象 Headers（`x-amz-meta-*`），由云端对象存储永久持久化，后续随时调用 `storageTemplate.getMetadata(...)` 均可完整反查用户元数据字典；
> - **Local 磁盘驱动**：因本地标准文件系统缺乏统一且跨平台的扩展元数据属性，用户元数据随当前上传操作即时返回至 `StorageObject` 内存对象中；后续调用 `getMetadata(...)` 仅读取本地文件的基础物理属性。若业务层在 Local 模式下有持久化反查元数据诉求，建议由业务数据库集中纳管。

---

## 5. 文件流式下载与存在性检查 (`StorageTemplate.download` / `exists`)

### 场景痛点
读取文件前需繁琐判空；文件不存在时缺乏标准化受控异常，影响调用方异常处理。

### 核心代码范式
```java
public void readDocument() {
    String path = "documents/2026/hello.txt";
    
    // 1. 判断文件是否存在
    if (storageTemplate.exists(path)) {
        // 2. 流式读取文件内容（严格遵循 try-with-resources 资源确定性释放）
        try (InputStream in = storageTemplate.download(path)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("文件内容: " + text);
        } catch (IOException e) {
            throw new StorageException("读取文件失败", e);
        }
    }
}
```

---

## 6. Spring 原生资源适配 (`StorageResource`)

### 场景痛点
向客户端提供文件下载时，需要手动设置响应头、流分块以及处理 HTTP 206 断点续传（HTTP Range），代码极易臃肿。

### 核心代码范式
`StorageTemplate.getResource(path)` 返回实现 Spring 原生 `Resource` 与 `WritableResource` 的 `StorageResource`，可直接作为 Spring MVC 的 `ResponseEntity` 输出：

```java
@RestController
@RequestMapping("/api/storage")
public class FileDownloadController {

    @Autowired
    private StorageTemplate storageTemplate;

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) {
        StorageResource resource = storageTemplate.getResource(path);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }
}
```
客户端发起带 `Range: bytes=0-1023` 请求头时，Spring MVC 自动结合 `StorageResource` 切片输出 HTTP 206 Partial Content，无需开发者编写任何分字节读取代码。

---

## 7. 分布式天然幂等删除与级联清理 (`delete` / `deleteBatch` / `deleteDirectory`)

### 场景痛点
微服务分布式环境中执行文件删除或重试时，若目标文件已被删除或并发移除，底层抛出 404 导致重试机制陷入死循环或业务异常中断；而在清理某一业务批次或目录时，缺乏级联清理机制。

### 核心代码范式
```java
// 1. 单文件天然幂等删除（文件不存在时静默返回 true，绝不抛出 404）
boolean success1 = storageTemplate.delete("temp/file.txt");

// 2. 批量天然幂等删除
boolean success2 = storageTemplate.deleteBatch(List.of(
        "batch/file1.txt",
        "batch/file2.txt",
        "batch/not-exist.txt"
));

// 3. 级联删除目录及其内部所有对象
boolean success3 = storageTemplate.deleteDirectory("temp-dir");
```

---

## 8. 目录级与前缀级流式批量删除与防误删安全熔断 (`deletePrefix`)

### 场景痛点
在定期归档、按租户清理历史日志或清理某一前缀下的大批文件时，若直接一次性在内存中查询全部对象再循环删除，易导致 OOM 且耗尽网络连接；更严重的是，若前端或调用方意外传入空字符串 `""`、空白符 `"   "` 或根路径 `"/"`，将导致误删全盘或整个 S3 Bucket 的灾难性后果。

### 核心能力与安全熔断
- **流式切片批删**：内部基于递归列举与分批切片（`deleteBatch`），内存受控，批量向底层存储发送批量删除指令；
- **防误删强行熔断（Fail-Fast Invariant）**：执行前置强校验，凡入参为空白、仅包含空格或经过规范化后为根路径 `/` 时，立即抛出 `StorageException(StorageResultCode.INVALID_PATH)` 强行熔断阻断，绝不放行；
- **全生命周期事件闭环**：内部删除的每一个有效对象均触发 `StorageDeletedEvent` 事件广播。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

public void purgeExpiredLogs() {
    // 1. 批量清理指定前缀下的所有对象（如按日期前缀）
    boolean deleted = storageTemplate.deletePrefix("logs/2026/01/");
    System.out.println("前缀清理结果: " + deleted);

    // 2. 误删防卫拦截：传入非法前缀直接熔断阻断
    try {
        storageTemplate.deletePrefix("   ");
    } catch (StorageException e) {
        System.out.println("成功拦截误删全盘攻击: " + e.getMessage());
    }
}
```

### 预期呈现契约
HTTP POST `/api/storage/delete-prefix?prefix=purge/2026/` 执行成功：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": true
}
```
底层 `purge/2026/01.log` 与 `purge/2026/02.log` 被彻底安全清除，而 `purge/other.txt` 保持原样完整保留。

---

## 9. 对象跨路径复制与移动 (`StorageTemplate.copy` / `move`)

### 场景痛点
业务流程中高频需要将文件从暂存目录归档到正式目录，若手动通过“下载流再重新上传并删除原文件”，不仅占用大量应用服务器带宽与内存，还存在网络中断导致的数据不一致风险。

### 核心代码范式
```java
// 1. 跨路径复制对象（保留原文件；S3 服务端免流秒传，Local NIO 零拷贝）
boolean copied = storageTemplate.copy("source/original.txt", "backup/original_bak.txt");

// 2. 复制时重写自定义元数据标签
boolean copiedWithMeta = storageTemplate.copy(
        "source/original.txt",
        "backup/tagged.txt",
        Map.of("category", "archive", "version", "v2")
);

// 3. 跨路径移动对象（源文件自动安全移除）
boolean moved = storageTemplate.move("source/original.txt", "moved/target.txt");
```

### 预期呈现契约
执行结果：
- `copied == true`：源路径与目标路径文件均存在，MD5 与数据完全一致；
- `moved == true`：源路径对象已被安全移除，目标路径成功生成对应内容。

---

## 10. 常用载荷便捷快速存取 (`uploadString` / `uploadBytes` / `uploadJson`)

### 场景痛点
日常开发中保存配置文本、JSON 快照或内存字节数组时，必须手动开辟 `ByteArrayInputStream`、设置字符集并管理流生命周期，样板代码繁琐。

### 核心代码范式
```java
// 1. 纯文本字符串便捷上传与下载（默认 UTF-8 编码，自动适配 text/plain;charset=UTF-8）
storageTemplate.uploadString("text/sample.txt", "Hello UTF-8 Content: 工业级存储 Starter");
String text = storageTemplate.downloadString("text/sample.txt");

// 2. 内存字节数组直接读写
byte[] bytes = new byte[]{10, 20, 30, 40, 50};
storageTemplate.uploadBytes("bytes/sample.bin", bytes, "application/octet-stream");
byte[] downloadedBytes = storageTemplate.downloadBytes("bytes/sample.bin");

// 3. POJO 业务对象 JSON 直接存取（深度复用 Core 模块 JsonUtil，自动适配 application/json;charset=UTF-8）
TestUser user = new TestUser("Ateng", 18);
storageTemplate.uploadJson("json/user.json", user);
TestUser downloadedUser = storageTemplate.downloadJson("json/user.json", TestUser.class);
```

### 预期呈现契约
读取到的 `text`、`downloadedBytes` 与 `downloadedUser` 与原始内存对象 100% 一致，自动完成序列化与反序列化，无需开发者编写任何 IO 流样板代码。

---

## 11. 目录前缀列举与分页对象检索 (`list` / `listPage`)

### 场景痛点
管理控制台或文件浏览器高频需要按层级浏览目录、平铺检索或分页展示文件列表。传统实现要么缺乏目录与文件的统一模型区分，要么将全部对象一次性加载导致内存溢出；此外 S3 与本地文件系统的目录模型差异（虚拟公共前缀 vs 物理文件夹）增加了业务适配难度。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

// 1. 平铺列举指定前缀下的第一层文件与子目录（非递归）
List<StorageObject> flatList = storageTemplate.list("documents");
for (StorageObject obj : flatList) {
    if (obj.isDirectory()) {
        System.out.println("子目录: " + obj.getPath());
    } else {
        System.out.println("文件: " + obj.getPath() + ", 大小: " + obj.getSize());
    }
}

// 2. 深度递归列举前缀下的所有对象（递归遍历全树，自动过滤 . 开头的系统隐藏项）
List<StorageObject> allList = storageTemplate.list("documents", true);

// 3. 契约驱动标准分页查询（深度复用 Core 模块 PageQuery / PageResult）
PageQuery pageQuery = PageQuery.of(1, 10);
PageResult<StorageObject> pageResult = storageTemplate.listPage("documents", pageQuery, true);

System.out.println("当前页条数: " + pageResult.getRecords().size());
System.out.println("总条数: " + pageResult.getTotal());
System.out.println("总页数: " + pageResult.getPages());
```

### 预期呈现契约
控制台输出：
```text
子目录: documents/2026
文件: documents/report.pdf, 大小: 1048576
当前页条数: 2
总条数: 2
总页数: 1
```
返回的 `StorageObject` 统一具备 `isDirectory()` 判别：
- **目录项**：`directory = true`，`size = 0L`，`path` 保证为去除末尾斜杠的标准相对路径；
- **隐藏文件过滤**：内部自动过滤 `.chunks` 等隐藏项，保障应用业务视图纯净；
- **分页解耦**：严格遵循 ADR-0010 传输分页契约，在 Service / 门面层完成流式切片与总数统计。

---

## 12. 多文件纯流式打包 ZIP 下载 (`downloadAsZip`)

### 场景痛点
管理后台或业务系统中常见“勾选多个文件批量打包下载”需求。传统做法通常先将所有文件下载落盘到本地物理磁盘临时目录，再执行压缩，极易在并发或大文件场景下压满服务器磁盘导致服务宕机；此外若其中某些文件丢失，生成半截损坏的 ZIP 包体验极差。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

// 1. 纯流式压缩写出至通用 OutputStream（零临时文件、零磁盘占用）
public void exportZipFile(OutputStream out) {
    List<String> paths = List.of("docs/report1.pdf", "docs/report2.pdf");
    storageTemplate.downloadAsZip(paths, out);
}

// 2. 支持显式指定 ZIP 包内相对路径与重命名
public void exportCustomZip(OutputStream out) {
    Map<String, String> pathMap = new LinkedHashMap<>();
    pathMap.put("docs/report1.pdf", "财务报告/2026年第一季度.pdf");
    pathMap.put("docs/report2.pdf", "财务报告/2026年第二季度.pdf");
    storageTemplate.downloadAsZip(pathMap, out);
}

// 3. Web 环境下一键写入 HTTP 响应（自动适配 Content-Type 与 RFC 5987 中文编码 Content-Disposition）
@GetMapping("/download/batch-zip")
public void downloadBatchZip(HttpServletResponse response) {
    List<String> paths = List.of("export/data1.xlsx", "export/data2.xlsx");
    storageTemplate.downloadAsZip(paths, response, "经营报表汇总.zip");
}
```

### 预期呈现契约
Web 客户端下载体验：
- 响应头自动注入：
  ```http
  Content-Type: application/zip
  Content-Disposition: attachment; filename="%E7%BB%8F%E8%90%A5%E6%8A%A5%E8%A1%A8%E6%B1%87%E6%80%BB.zip"; filename*=UTF-8''%E7%BB%8F%E8%90%A5%E6%8A%A5%E8%A1%A8%E6%B1%87%E6%80%BB.zip
  ```
- **内存与性能契约**：基于 JDK 原生 `ZipOutputStream` 边读边压缩写出，零物理磁盘文件产生；
- **严格防御契约**：打包前自动执行前置存在性拦截，若有任何文件缺失立即抛出 `StorageException(StorageResultCode.OBJECT_NOT_FOUND)`，杜绝输出残缺损坏的压缩包；
- **重名冲突消解**：遇到同名条目自动递增重命名（如 `report.pdf` -> `report (1).pdf`），杜绝 ZIP 规范冲突报错。

---

## 13. Spring 原生存储生命周期事件广播 (`StorageUploadedEvent` / `StorageDeletedEvent`)

### 场景痛点
在文件成功上传或删除后，业务层常常需要异步触发后续处理（如记录审计日志、刷新 CDN 缓存、生成缩略图、通知消息队列等）。如果直接在业务代码中堆砌强耦合逻辑，代码严重臃肿，且监听器异常可能导致核心文件写入操作被误回滚。

### 核心代码范式
```java
// 1. 无侵入声明式监听存储事件（支持 Spring 原生 @EventListener 与 @Async 异步监听）
@Component
public class StorageEventListener {

    // 监听文件上传成功事件
    @EventListener
    public void onStorageUploaded(StorageUploadedEvent event) {
        System.out.println("文件上传成功: 源=" + event.getSourceKey() 
                + ", 路径=" + event.getPath() 
                + ", 大小=" + event.getSize() + " 字节"
                + ", MIME=" + event.getContentType());
    }

    // 监听文件删除成功事件
    @EventListener
    public void onStorageDeleted(StorageDeletedEvent event) {
        System.out.println("文件删除成功: 源=" + event.getSourceKey() 
                + ", 路径=" + event.getPath());
    }
}
```

### 预期呈现契约
控制台日志输出（实测记录）：
```text
2026-10-19T16:44:48.871+08:00  INFO 33988 --- [ateng-boot-sample-storage] [           main] i.g.a.b.s.s.l.SampleStorageEventListener : [SampleStorageEventListener] 接收到文件上传事件: source=default, path=hello.txt, size=32, contentType=text/plain
2026-10-19T16:44:49.290+08:00  INFO 33988 --- [ateng-boot-sample-storage] [           main] i.g.a.b.s.s.l.SampleStorageEventListener : [SampleStorageEventListener] 接收到文件删除事件: source=default, path=backup/target.txt
```
- **全链路感知**：`upload`、`uploadBytes`、`uploadString`、`uploadJson`、`copy`、`move` 均会自动触发 `StorageUploadedEvent`；`delete`、`deleteBatch`、`deletePrefix` 与 `move` 源文件均会自动触发 `StorageDeletedEvent`；
- **Fail-Safe 异常隔离**：事件发布内部包裹异常防御，任何外部监听器抛出的未捕获异常均仅记录 Warn 告警日志，绝不阻断文件核心上传/删除的成功返回。

---

## 14. 安全预签名只读防盗链外链 (`getPresignedUrl`)

### 场景痛点
传统本地存储通常缺乏外链时效性控制与防盗链签名，必须手动编写下载控制器或配置静态 Nginx 映射，无法与云端 S3 的 Presigned URL 保持契约一致。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

public void generateDownloadUrl() {
    // 生成 15 分钟有效期的只读安全外链（本地驱动自动基于 HMAC-SHA256 签名）
    PresignedUrl presigned = storageTemplate.getPresignedUrl("documents/2026/report.pdf", Duration.ofMinutes(15));
    
    System.out.println("外链地址: " + presigned.getUrl());
    System.out.println("请求方法: " + presigned.getHttpMethod());
    System.out.println("过期时间: " + presigned.getExpiration());
}
```

### 预期呈现契约
控制台输出：
```text
外链地址: http://localhost:8080/storage/local/download?path=documents/2026/report.pdf&expires=1729180000&signature=63a75877c86a63d91cf0ebbf43fa58a69dcbca58d7c9a622617f6e0b7a8d56b0
请求方法: GET
过期时间: 2026-10-17T14:15:00Z
```

外部 HTTP 客户端（浏览器或移动端）直接请求该 URL 时，内置的 `LocalStorageDownloadController` 端点自动拦截校验：
- **时效过期**：返回 HTTP 403 Forbidden；
- **签名不符或参数被篡改**：返回 HTTP 403 Forbidden，控制台真实输出告警：
  ```text
  2026-10-19T16:44:49.570+08:00  WARN 33988 --- [ateng-boot-sample-storage] [           main] g.a.b.s.c.LocalStorageDownloadController : 预签名下载外链签名校验失败或已被篡改: path=secure/data.txt, expires=1792399549
  ```
- **路径穿越攻击**（如注入 `..`）：返回 HTTP 400 Bad Request；
- **校验合法**：以流式响应输出文件内容，并按文件属性设置标准 `Content-Type` 与 `Content-Disposition` 下载响应头。

---

## 15. 客户端直传预签名与本地直传端点交互 (`getPresignedUploadUrl`)

### 场景痛点
面对音视频、安装包等大文件上传，若全部经过业务应用服务器中转转发，将严重占用后端公网带宽与 JVM 内存，引发服务并发瓶颈；传统架构在私有化或本地磁盘部署时，往往无法使用云端预签名直传，导致前端代码必须针对本地和云端写两套完全不同的上传逻辑。

### 核心能力与闭环设计
- **Local 与 S3 双核直传统一抽象**：
  - **S3 协议驱动**：原生生成 AWS S3 / MinIO 预签名 PUT 直传外链凭据；
  - **Local 本地驱动**：生成指向内置受控直传端点（`/storage/local/upload`）的安全 HMAC 凭据，前端统一以 HTTP PUT 方法直传数据；
- **安全防篡改与配额约束（`PresignedUploadRequest`）**：支持在预签名阶段限定 MIME 类型（`contentType`）与最大字节上限（`maxSizeBytes`），直传端点自动执行时效校验与防篡改比对；
- **零中转极速体验**：前端拿到凭据后直接 PUT 写入存储介质，完全不占用业务接口线程。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

// 1. 获取预签名直传写入凭据
public PresignedUrl getDirectUploadUrl(String path) {
    PresignedUploadRequest request = PresignedUploadRequest.builder()
            .path(path)
            .duration(Duration.ofMinutes(10))
            .contentType("text/plain")
            .maxSizeBytes(10 * 1024 * 1024L) // 限制最大 10MB
            .build();
    return storageTemplate.getPresignedUploadUrl(request);
}
```

在 Controller 中暴露直传生成端点：
```java
@GetMapping("/presigned-upload")
public Result<PresignedUrl> getPresignedUploadUrl(
        @RequestParam("path") String path,
        @RequestParam(value = "durationSeconds", defaultValue = "300") long durationSeconds) {
    PresignedUploadRequest request = PresignedUploadRequest.builder()
            .path(path)
            .duration(Duration.ofSeconds(durationSeconds))
            .build();
    PresignedUrl presignedUrl = storageTemplate.getPresignedUploadUrl(request);
    return Result.success(presignedUrl);
}
```

### 预期呈现契约
HTTP GET `/api/storage/presigned-upload?path=direct/doc.txt` 返回预签名凭据：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "url": "http://localhost:8080/storage/local/upload?path=direct/doc.txt&expires=1792489000&signature=9c5a7...",
    "httpMethod": "PUT",
    "expiration": "2026-10-20T14:40:00Z",
    "headers": {}
  }
}
```
前端随后直接使用 HTTP PUT 向 `url` 发送二进制文件内容：
```http
PUT /storage/local/upload?path=direct/doc.txt&expires=1792489000&signature=9c5a7... HTTP/1.1
Host: localhost:8080
Content-Type: text/plain

Direct PUT Content
```
响应 HTTP 200 OK，文件直接落盘到底层存储介质并触发上传事件。

---

## 16. 端侧大文件分块直传预签名与完成合并闭环 (`getPresignedUploadPartUrl`)

### 场景痛点
面对数百 MB 或数 GB 级的特大文件，单次直传若遇到网络抖动中断代价极高。前端需要能够并发将大文件分块直传到对象存储，最后由业务后端协调合并。若底层存储为本地文件系统，传统方案缺少对应的分块直传协议。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

// 1. 初始化分块会话，获取唯一 uploadId
String uploadId = storageTemplate.initiateChunkedUpload("chunked/large.bin");

// 2. 为每一个分片生成独立的预签名 PUT 直传外链（分片序号 chunkIndex 1-based）
PresignedUrl part1Url = storageTemplate.getPresignedUploadPartUrl(
        "chunked/large.bin", uploadId, 1, Duration.ofMinutes(15));
PresignedUrl part2Url = storageTemplate.getPresignedUploadPartUrl(
        "chunked/large.bin", uploadId, 2, Duration.ofMinutes(15));

// 3. 前端拿到各分块预签名外链后，并发发起 HTTP PUT 直传各分片数据...

// 4. 前端全部直传完毕后，后端触发一键合并
StorageObject mergedObject = storageTemplate.completeChunkedUpload("chunked/large.bin", uploadId, 2);
```

### 预期呈现契约
在 Sample 控制器交互中（实测记录）：
1. 客户端请求 `GET /api/storage/presigned-upload-part?path=chunked/large.bin&uploadId=xxx&chunkIndex=1`，获取分片 1 直传 URL；
2. 客户端直接 `PUT` 写入第 1 分片数据，服务端返回 200 OK；
3. 客户端直接 `PUT` 写入第 2 分片数据，服务端返回 200 OK；
4. 客户端请求 `POST /api/storage/chunk/complete?uploadId=xxx&path=chunked/large.bin&totalChunks=2`：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "path": "chunked/large.bin",
    "filename": "large.bin",
    "size": 34,
    "contentType": "application/octet-stream"
  }
}
```
大文件无损流式合并落盘，临时碎片目录立即自动清理回收。

---

## 17. 孤儿分片碎片生命周期治理守护 (`StorageChunkCleaner`)

### 场景痛点
客户端在执行大文件切片上传过程中，常因用户刷新页面、关闭浏览器、网络彻底中断或客户端崩溃，导致大量未完成合并的分片碎片永远滞留在本地磁盘 `.chunks` 目录或云端 S3 存储桶中，持续蚕食物理磁盘空间甚至引发磁盘满宕机。

### 核心能力与自驱回收
- **自动注册守护任务**：开启 `ateng.boot.storage.chunk-cleaner.enabled: true` 后，自动注册 `StorageChunkCleaner` 定时任务组件；
- **精细化保留时间（`retention`）**：默认保留 24 小时（`Duration.ofHours(24)`），仅清理最后修改时间超过阈值的孤儿会话目录，绝不误删正在进行中的正常分片会话；
- **零泄漏安全回收**：深度遍历清理孤儿临时目录与碎片文件，清理过程异常捕获隔离，保障系统宿主长期平稳运行。

### 配置范式
```yaml
ateng:
  boot:
    storage:
      chunk-cleaner:
        enabled: true                     # 开启孤儿碎片定时治理清理（默认 false）
        retention: 24h                    # 孤儿分片会话最大滞留容忍时间（默认 24h）
        cron: "0 0 3 * * ?"               # 定时清理 Cron 表达式（默认每天凌晨 3:00 执行）
```

---

## 18. 原生零拷贝流式散列计算 (`StorageHashUtil`) 与极速秒传

### 场景痛点
在网盘、知识库或素材库系统中，“文件秒传”是提升用户体验与节约存储空间的核心特性；但在实现秒传与校验时，若使用传统工具类计算哈希，往往需先将流全部读入内存 `byte[]`（极易引发 OOM）或使用普通 InputStream 导致读取完后流指针偏移耗尽，无法再次用于上传写入。

### 核心能力与设计不变量
- **100% 纯原生 JDK 算法驱动**：无任何三方依赖，支持 MD5、SHA-1、SHA-256、CRC32 散列算法；
- **流指针零截断不变量（Zero-Stream-Truncation Invariant）**：`StorageHashUtil.hashHex(InputStream, algorithm)` 基于受控分块缓冲区（8KB buffer）流式吞吐，执行完毕后无内存堆积；
- **存储对象原生哈希探测**：门面提供 `storageTemplate.calculateHash(path, algorithm)`，免下载直接在存储端极速计算对象的唯一定位散列指纹。

### 核心代码范式
```java
// 1. 基于 StorageHashUtil 对任意输入流极速计算 SHA-256
String sha256 = StorageHashUtil.sha256Hex(inputStream);
String md5 = StorageHashUtil.md5Hex(inputStream);

// 2. 直接对已存储对象计算指纹
String remoteSha256 = storageTemplate.calculateHash("documents/2026/report.pdf", HashAlgorithm.SHA256);
```

### 极速秒传业务闭环示例
```java
@PostMapping("/fast-upload")
public Result<StorageObject> fastUpload(
        @RequestParam("targetPath") String targetPath,
        @RequestParam("hash") String hash) {
    String poolSourcePath = "hash-pool/" + hash + ".dat";
    
    // 1. 检查散列指纹池中是否存在该文件
    if (!storageTemplate.exists(poolSourcePath)) {
        // 未命中：返回 40404 引导前端降级走普通直传或分块上传
        return Result.fail(40404, "文件哈希未命中秒传池，请走普通上传流");
    }
    
    // 2. 命中：直接通过零拷贝 copy 复用对象，实现秒传
    storageTemplate.copy(poolSourcePath, targetPath);
    StorageObject metadata = storageTemplate.getMetadata(targetPath);
    return Result.success(metadata);
}
```

### 预期呈现契约
1. **秒传未命中**：
   ```json
   {
     "code": 40404,
     "msg": "文件哈希未命中秒传池，请走普通上传流",
     "data": null,
     "success": false
   }
   ```
2. **秒传命中**：
   ```json
   {
     "code": 200,
     "msg": "操作成功",
     "data": {
       "path": "user-space/report.txt",
       "filename": "report.txt",
       "size": 44,
       "contentType": "text/plain"
     }
   }
   ```
毫秒级完成虚拟落盘，无需客户端重复上传庞大物理数据。

---

## 19. Spring Boot Actuator 存储健康探针感知 (`StorageHealthIndicator`)

### 场景痛点
在云原生与微服务 Kubernetes 探针探测中，需要实时感知底层对象存储（本地磁盘是否满、目录是否存在读写权限；云端 S3 Bucket 是否存在、鉴权凭据是否过期失效），若缺乏健康探针，在存储发生故障时流量仍会被路由进来导致大量业务报错。

### 核心能力与指标输出
- **自动接入 Actuator**：当下游工程引入 `spring-boot-starter-actuator` 且开启 `management.health.storage.enabled: true` 时，自动装配 `StorageHealthIndicator`；
- **双核深度感知**：
  - **Local 驱动**：检查基准物理路径是否存在，且是否具备读（`Files.isReadable`）与写（`Files.isWritable`）权限；
  - **S3 驱动**：向目标存储桶发起轻量级 `HeadBucket` 探测，精准捕获鉴权与连通状态；
- **健康指标聚合**：在 `/actuator/health` 暴露统一 `storage` 组件状态。

### 预期呈现契约
请求 `GET /actuator/health`（开启 `management.endpoint.health.show-details: always`）：
```json
{
  "status": "UP",
  "components": {
    "storage": {
      "status": "UP",
      "details": {
        "primary": "local",
        "driver": "LocalDriver",
        "basePath": "C:\\Users\\admin\\AppData\\Local\\Temp\\ateng-boot-storage-test"
      }
    }
  }
}
```
当存储磁盘无写入权限或 S3 Bucket 异常时，状态自动降级为 `DOWN`，容器编排器可立即感知并触发自愈摘流。

---

## 20. 动态多源路由与调度 (`use` / `@Storage` / 异步透传)

### 场景痛点
大型生产系统往往同时对接多种存储后端（如将公开图片存入 CDN 加速桶、将企业私密合同存入内网私有 MinIO、临时中间数据写入本地磁盘）。若在业务代码中硬编码具体驱动或手动切换，耦合严重且极易在异步线程池中导致上下文串源污染。

### 核心代码范式

#### 1. 编程式链式切源 (`storageTemplate.use`)
返回绑定目标存储源的不可变独立句柄，局部作用域线程安全，不污染全局上下文：

```java
@Autowired
private StorageTemplate storageTemplate;

public void uploadMultiSources() {
    byte[] content = "Hello Multi-Source".getBytes(StandardCharsets.UTF_8);

    // 默认写入 primary 源
    storageTemplate.upload("data/default.txt", new ByteArrayInputStream(content));

    // 链式切换写入公开 OSS 源
    storageTemplate.use("oss-public").upload("images/banner.png", new ByteArrayInputStream(content));

    // 链式切换写入私密 MinIO 源
    storageTemplate.use("minio-secure").upload("contracts/2026.pdf", new ByteArrayInputStream(content));
}
```

#### 2. 声明式注解切源 (`@Storage`)
支持标注在 Spring Service 类或方法上（方法级优先级高于类级），由切面自动切换并在 `finally` 中严格恢复现场：

```java
@Service
public class OrderFileService {

    @Autowired
    private StorageTemplate storageTemplate;

    // 自动切换至 minio-secure 存储源
    @Storage("minio-secure")
    public void archiveSecretContract(String path, InputStream in) {
        // 无需显式切源，方法内部所有 storageTemplate 操作自动路由至 minio-secure
        storageTemplate.upload(path, in);
    }
}
```

#### 3. 跨线程异步透传 (`StorageContextHolder.wrap`)
与 Core 模块的 `AsyncContext` 四维快照深度联动，支持在 `@Async`、线程池及虚拟线程中跨线程安全透传当前活动存储源并在任务执行完毕后严格自恢复：

```java
// 主线程设定上下文
StorageContextHolder.set("oss-public");
MDC.put("traceId", "trace-12345");

// 包装异步任务
Runnable asyncTask = StorageContextHolder.wrap(() -> {
    // 异步工作子线程精准捕获当前存储源与链路标识
    System.out.println("当前存储源: " + StorageContextHolder.get());
    System.out.println("TraceId: " + MDC.get("traceId"));
});

CompletableFuture.runAsync(asyncTask);
```

### 预期呈现契约
控制台输出：
```text
当前存储源: oss-public
TraceId: trace-12345
```
异步任务结束后，工作线程上下文被自动彻底恢复至原始现场，主线程现场丝毫不受影响。

---

## 21. 安全路径防卫机制 (`StorageSecurityUtil`)

`ateng-boot-storage` 内置严格的路径遍历防卫引擎（`StorageSecurityUtil`）：
1. **自动过滤空字符与盘符**：拦截 `\0` 空字符截断攻击与 Windows 盘符（如 `C:`）；
2. **深度消解相对路径**：逐级拆解路径片段，对任何包含 `..` 的逃逸路径快速失败抛出 `StorageException(StorageResultCode.INVALID_PATH)`；
3. **路径归一化净化**：多余斜杠（`//`）与 Windows 反斜杠（`\`）自动规范化收敛为标准相对路径；
4. **常量时间签名校验**：预签名 HMAC-SHA256 校验采用 `MessageDigest.isEqual` 常量时间比对，彻底杜绝时序攻击（Timing Attack）。

---

## 22. 文件魔数特征嗅探与防伪装安全加固 (`StorageGuard`)

### 场景痛点
在文件上传业务中，恶意攻击者常通过修改文件扩展名（例如将恶意的 Windows PE `.exe`、Linux `.elf`、Shell 脚本或木马伪装为 `.png`、`.jpg` 或 `.pdf`）尝试绕过前端与表层扩展名检查直接落盘，引发远程代码执行（RCE）或恶意文件扩散；同时，传统的流探查若盲目 `read()` 原始流，会导致输入流指针偏移造成下游驱动写入被截断或空文件。

### 核心能力与零截断不变量
- **15+ 种标准特征字节比对**：内置图片（PNG、JPEG、GIF、WEBP、BMP）、文档（PDF、OLE2 DOC/XLS/PPT、XML、SVG）、压缩包（ZIP、Office OpenXML、GZ、7Z、TAR）及音视频（MP4、MP3、WAV、AVI）的标准魔数嗅探器；
- **高危恶意二进制特征识别**：精准嗅探 Windows PE（`MZ`）、Linux ELF（`.ELF`）、Shell 脚本（`#!`）、Java 字节码（`CAFEBABE`），杜绝伪装上传；
- **零流截断损耗保障（Zero-Truncation Invariant）**：基于 `BufferedInputStream.mark(512)` 与 `reset()` 机制安全嗅探特征头，嗅探完毕自动精准复位指针，确保下游本地或云端 S3 存储驱动拿到 100% 完整的原始流与 MD5 散列；
- **异常快速失败拦截**：细分抛出 `STORAGE_FILE_TYPE_MISMATCH`（40010，伪装不匹配）与 `STORAGE_FILE_NOT_ALLOWED`（40011，黑白名单拦截）。

### 配置范式
在 `application.yml` 中开启安全防卫与魔数校验：
```yaml
ateng:
  boot:
    storage:
      guard:
        enabled: true                 # 开启文件安全防卫与魔数嗅探拦截（默认 false）
        strict-magic-check: true      # 开启严格魔数强校验（默认 false，开启时已知二进制格式必须与扩展名特征绝对吻合）
        allowed-extensions:           # 允许上传的文件扩展名白名单（默认空集合，即不限制白名单）
          - png
          - jpg
          - jpeg
          - pdf
          - zip
```

### 预期呈现契约
当攻击者尝试将恶意伪装文件上传时（例如文件扩展名为 `.png` 但二进制真实内容为伪装文本），系统立即阻断并记录控制台日志：
```text
2026-10-19T16:44:49.490+08:00  INFO 33988 --- [ateng-boot-sample-storage] [           main] i.g.a.b.w.h.GlobalExceptionHandler       : 业务逻辑中断: code=40010, msg=文件魔数强校验失败：未检测到扩展名 [png] 所需的标准头部特征魔数
```
遵循显式统一响应契约（ADR-0002），HTTP 返回 200 + 业务错误码 `40010`（`STORAGE_FILE_TYPE_MISMATCH`）：
```json
{
  "code": 40010,
  "msg": "文件魔数强校验失败：未检测到扩展名 [png] 所需的标准头部特征魔数",
  "data": null,
  "success": false
}
```
恶意伪装文件被彻底拒之门外，零磁盘与 S3 污染。

---

## 23. 大文件分片合并暂存与生命周期状态机

### 场景痛点
在面对超大文件时，网络瞬断会导致单流直传全盘失败，必须支持客户端切片断点续传。但在服务端实现分片合并时，若处理不当常面临内存溢出（OOM）、跳号损坏文件入库以及未合并分片碎片持续泄漏吞噬磁盘空间等痛点。

### 核心能力与架构设计
- **100% 双核行为平替**：
  - **Local 本地驱动**：在受管 `.chunks/<uploadId>/` 临时隔离目录中暂存碎片，合并时利用 Java NIO `FileChannel.transferTo` **零拷贝流式按序追加合并**，合并完成后立即原子删除临时目录；
  - **S3 云原生驱动**：标准封装 AWS S3 Multipart Upload 原生 API（`CreateMultipartUpload` -> `UploadPart` -> `CompleteMultipartUpload` / `AbortMultipartUpload`），支持 S3 远程服务端无损合并；
- **1-based 序号与强连续性校验**：分片序号强制为 `1 ~ totalChunks`；合并时严格检测每一片的存在性与连续性，任一分片缺失立即快速失败，抛出 `STORAGE_CHUNK_MISSING`（`40012`）；
- **全生命周期清理状态机**：提供 `abortChunkedUpload(uploadId)` 主动清理取消机制，与完成合并时的自动清理形成闭环，彻底根绝碎片滞留；
- **事件广播闭环**：分片合并完成后自动广播 `StorageUploadedEvent`，打通下游消息通知与业务状态流转。

### 预期呈现契约
若客户端在上传过程中发生异常，仅上传了第 1 片（遗漏第 2 片，预期总片数: 2）即尝试触发合并，系统立即拦截并快速失败，控制台输出：
```text
2026-10-19T16:44:49.201+08:00  INFO 33988 --- [ateng-boot-sample-storage] [           main] i.g.a.b.w.h.GlobalExceptionHandler       : 业务逻辑中断: code=40012, msg=分片合并失败，缺失第 2 个分片（预期总片数: 2）
```
返回标准错误码 `40012`（`STORAGE_CHUNK_MISSING`）：
```json
{
  "code": 40012,
  "msg": "分片合并失败，缺失第 2 个分片（预期总片数: 2）",
  "data": null,
  "success": false
}
```

---

## 24. 场景样例工程与集成测试索引

`ateng-boot-sample-storage` 作为专职场景沙箱，**1:1 全真覆盖了当前模块的所有对外公开特性与边界防卫能力**。

👉 样例工程根目录：[ateng-boot-sample-storage](../ateng-boot-samples/ateng-boot-sample-storage/README.md)

### 19 个端到端全真集成测试用例索引 (`SampleStorageIntegrationTest`)
| 序号 | 测试方法名 | 对应验证特性与业务场景 |
| :---: | :--- | :--- |
| 1 | `testUploadAndDownload` | 基础文件上传与下载闭环 |
| 2 | `testExistsAndMetadata` | 存在性检查与 StorageObject 元数据提取 |
| 3 | `testCopyAndMove` | 跨路径对象零拷贝复制与移动归档 |
| 4 | `testDeleteIdempotent` | 单文件与批量天然幂等删除 |
| 5 | `testPresignedDownloadUrl` | 本地 HMAC-SHA256 预签名外链生成与安全受控下载 |
| 6 | `testPresignedUrlTampered` | 防篡改与时序攻击防御（伪造签名或篡改 URL 返回 403） |
| 7 | `testListAndListPage` | 平铺/递归前缀列举与标准 PageQuery 分页检索 |
| 8 | `testConvenientStringAndJson` | 便捷文本与 JSON POJO 序列化存取 |
| 9 | `testDownloadAsZip` | 多文件纯流式 ZIP 压缩响应与 RFC 5987 编码 |
| 10 | `testChunkedUploadLifecycleAndValidation` | 大文件分片会话生命周期、正序落盘、缺片防卫（40012）与主动中止（abort） |
| 11 | `testStorageGuardCamouflageDefense` | 文件魔数特征嗅探与恶意脚本防伪装拦截（40010） |
| 12 | `testDownloadZipWithDuplicatePaths` | ZIP 打包同名重名条目自动递增重命名消解 |
| 13 | `testValidationConstraintDefense` | Controller 传输边界 JSR-303 Fail-Fast 参数校验 |
| 14 | `testPresignedDirectUploadPut` | 客户端直传预签名生成与前端真实 HTTP PUT 直传交互闭环 |
| 15 | `testPresignedChunkedUploadPartAndComplete` | 端侧大文件分块直传预签名外链、分块 PUT 直传与最终合并 |
| 16 | `testCalculateHashAndFastUpload` | `StorageHashUtil` 散列计算端点与极速秒传（200 / 40404 引导） |
| 17 | `testDeletePrefixAndDirectory` | 前缀级流式批量删除与目录级联删除 |
| 18 | `testUploadWithUserMetadata` | 上传携带用户扩展元数据（`userMetadata`）与反查 |
| 19 | `testActuatorHealthIndicator` | Spring Boot Actuator `/actuator/health` 存储健康探针感知 |

### 本地快速定向自验准出
```bash
mvn test -pl ateng-boot-storage,ateng-boot-samples/ateng-boot-sample-storage -am -T 1C -nsu -B
```
无需依赖任何外部物理网络或真实 S3 账号，纯内存与受管临时目录闭环，秒级全绿通过。
