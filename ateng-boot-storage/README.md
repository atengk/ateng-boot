# ateng-boot-storage 基础设施 Starter

> 企业级轻量多源对象存储与安全预签名一体化 Starter，统一抽象 Local 本地磁盘存储与标准 S3 兼容协议（MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS），提供路径遍历安全防卫、MIME 自动推导、Spring 原生资源深度集成与分布式天然幂等删除保障。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-storage` 是面向生产就绪的高内聚、轻量级存储基础设施模块：
- **纯净依赖**：仅必需依赖 `ateng-boot-core`，零冗余第三方工具包，Spring Web 仅作为可选依赖（optional）；
- **双核驱动**：统一抽象为 `LocalDriver`（本地磁盘）与 `S3Driver`（云原生 S3 兼容协议，精简版 AWS SDK v2）；
- **显式契约**：提供 `StorageTemplate` 高阶门面与 Spring 原生 `StorageResource`（实现 `Resource` 与 `WritableResource`），删除天然幂等。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-storage</artifactId>
</dependency>
```

---

## 2. 基础配置说明

### 场景痛点
在单机部署或私有化交付中，需要快速将文件落盘到本地磁盘，且需指定基础存储路径。

### 配置范式
在 `application.yml` 中配置存储基础属性：

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
```

#### 方案 B：标准 S3 兼容协议（MinIO / AWS S3 / 阿里云 OSS / 腾讯云 COS）
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

## 4. Web 表单文件上传 (`MultipartFile`)

### 场景痛点
Controller 层接收前端上传的 `MultipartFile` 时，容易受客户端伪造的相对路径注入攻击（如文件名携带 `../../`），需进行深度防穿越净化。

### 核心代码范式
```java
@RestController
@RequestMapping("/storage")
public class FileUploadController {

    @Autowired
    private StorageTemplate storageTemplate;

    @PostMapping("/upload")
    public Result<StorageObject> upload(@RequestParam("file") MultipartFile file) {
        // 自动提取安全文件名并保存至 uploads/avatars/ 目录下
        StorageObject object = storageTemplate.upload("uploads/avatars/", file);
        return Result.success(object);
    }
}
```

### 预期呈现契约
HTTP JSON 响应：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "path": "uploads/avatars/avatar.png",
    "filename": "avatar.png",
    "size": 10240,
    "contentType": "image/png",
    "etag": "e2fc714c4727ee9395f324cd2e7f331f",
    "lastModified": "2026-10-17T13:45:00Z"
  }
}
```

---

## 5. 文件流式下载与存在性检查 (`StorageTemplate.download`)

### 场景痛点
读取文件前需繁琐判空；文件不存在时缺乏标准化受控异常，影响调用方异常处理。

### 核心代码范式
```java
public void readDocument() {
    String path = "documents/2026/hello.txt";
    
    // 1. 判断文件是否存在
    if (storageTemplate.exists(path)) {
        // 2. 流式读取文件内容
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
@RequestMapping("/storage")
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

---

## 7. 分布式天然幂等删除 (`StorageTemplate.delete`)

### 场景痛点
微服务分布式环境中执行文件删除或重试时，若目标文件已被删除或并发移除，底层抛出 404 导致重试机制陷入死循环或业务异常中断。

### 核心代码范式
```java
// 1. 单文件天然幂等删除（文件不存在时静默返回 true）
boolean success1 = storageTemplate.delete("temp/file.txt");

// 2. 批量天然幂等删除
boolean success2 = storageTemplate.deleteBatch(List.of(
        "batch/file1.txt",
        "batch/file2.txt",
        "batch/not-exist.txt"
));
```

---

## 8. 对象跨路径复制与移动 (`StorageTemplate.copy` / `move`)

### 场景痛点
业务流程中高频需要将文件从暂存目录归档到正式目录，若手动通过“下载流再重新上传并删除原文件”，不仅占用大量应用服务器带宽与内存，还存在网络中断导致的数据不一致风险。

### 核心代码范式
```java
// 1. 跨路径复制对象（保留原文件；S3 服务端免流秒传，Local NIO 零拷贝）
boolean copied = storageTemplate.copy("source/original.txt", "backup/original_bak.txt");

// 2. 跨路径移动对象（源文件自动安全移除）
boolean moved = storageTemplate.move("source/original.txt", "moved/target.txt");
```

### 预期呈现契约
执行结果：
- `copied == true`：源路径与目标路径文件均存在，MD5 与数据完全一致；
- `moved == true`：源路径对象已被安全移除，目标路径成功生成对应内容。

---

## 9. 常用载荷便捷快速存取 (`String` / `byte[]` / `JSON`)

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

## 10. 目录前缀列举与分页对象检索 (`list` / `listPage`)

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
PageResult<StorageObject> pageResult = storageTemplate.listPage("documents", pageQuery);

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

## 11. 多文件纯流式打包 ZIP 下载 (`downloadAsZip`)

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

## 12. Spring 原生存储生命周期事件广播 (`StorageUploadedEvent` / `StorageDeletedEvent`)

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
控制台日志输出：
```text
文件上传成功: 源=primary, 路径=documents/2026/report.pdf, 大小=1048576 字节, MIME=application/pdf
文件删除成功: 源=primary, 路径=temp/cache-123.bin
```
- **全链路感知**：`upload`、`uploadBytes`、`uploadString`、`uploadJson`、`copy`、`move` 均会自动触发 `StorageUploadedEvent`；`delete`、`deleteBatch` 与 `move` 源文件均会自动触发 `StorageDeletedEvent`；
- **Fail-Safe 异常隔离**：事件发布内部包裹异常防御，任何外部监听器抛出的未捕获异常均仅记录 Warn 告警日志，绝不阻断文件核心上传/删除的成功返回。

---

## 13. Local HMAC 预签名防盗链下载 (`getPresignedUrl`)

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
- **签名不符或参数被篡改**：返回 HTTP 403 Forbidden；
- **路径穿越攻击**（如注入 `..`）：返回 HTTP 400 Bad Request；
- **校验合法**：以流式响应输出文件内容，并按文件属性设置标准 `Content-Type` 与 `Content-Disposition` 下载响应头。

---

## 14. S3 云原生预签名外链与直传 (`getPresignedUrl` / `getPresignedUploadUrl`)

### 场景痛点
面对音视频、安装包等大文件上传，若全部经过应用服务器中转，将严重占用后端带宽并消耗内存，导致服务器并发瓶颈；而在下载时，为了保护文件隐私，需要生成临时且安全的云端下载直链。

### 核心代码范式
```java
@Autowired
private StorageTemplate storageTemplate;

// 1. 生成 15 分钟有效期的 S3 只读下载外链（自动应用 CDN 域名加速）
public void generateS3DownloadUrl() {
    PresignedUrl presigned = storageTemplate.getPresignedUrl("media/video.mp4", Duration.ofMinutes(15));
    System.out.println("S3下载外链: " + presigned.getUrl());
    System.out.println("HTTP方法: " + presigned.getHttpMethod());
}

// 2. 生成 30 分钟有效期的前端大文件直传 PUT 外链
public void generateS3UploadUrl() {
    PresignedUrl presigned = storageTemplate.getPresignedUploadUrl("uploads/raw-data.bin", Duration.ofMinutes(30));
    System.out.println("S3直传外链: " + presigned.getUrl());
    System.out.println("HTTP方法: " + presigned.getHttpMethod());
}
```

### 预期呈现契约
控制台输出：
```text
S3下载外链: https://cdn.example.com/test-bucket/media/video.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261017T141500Z&X-Amz-SignedHeaders=host&X-Amz-Expires=900&X-Amz-Credential=...&X-Amz-Signature=sig123
HTTP方法: GET
S3直传外链: https://cdn.example.com/test-bucket/uploads/raw-data.bin?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261017T141500Z&X-Amz-SignedHeaders=host&X-Amz-Expires=1800&X-Amz-Credential=...&X-Amz-Signature=sig456
HTTP方法: PUT
```
前端收到直传 URL 后，即可直接发起标准的 `PUT` 请求将文件推送到对象存储，完全绕过后端应用服务器。

---

## 15. 动态多源路由与调度 (`use` / `@Storage` / 异步透传)

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

## 16. 安全路径防卫机制

`ateng-boot-storage` 内置严格的路径遍历防卫引擎（`StorageSecurityUtil`）：
1. **自动过滤空字符与盘符**：拦截 `\0` 空字符截断攻击与 Windows 盘符（如 `C:`）；
2. **深度消解相对路径**：逐级拆解路径片段，对任何包含 `..` 的逃逸路径快速失败抛出 `StorageException(StorageResultCode.INVALID_PATH)`；
3. **路径归一化净化**：多余斜杠（`//`）与 Windows 反斜杠（`\`）自动规范化收敛为标准相对路径；
4. **常量时间签名校验**：预签名 HMAC-SHA256 校验采用 `MessageDigest.isEqual` 常量时间比对，彻底杜绝时序攻击（Timing Attack）。

---

## 17. 文件魔数特征嗅探与防伪装安全加固 (`StorageGuard`)

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

### 核心代码范式

#### 1. 统一门面自动防卫拦截 (`StorageTemplate.upload`)
无需修改业务层代码，只要在配置中启用 `guard.enabled: true`，所有经由 `StorageTemplate.upload` 的文件流与 MultipartFile 自动受到安全守卫与特征嗅探保护：

```java
@Autowired
private StorageTemplate storageTemplate;

public void uploadAvatar(InputStream in) {
    // 若上传的文件声明为 avatar.png，但真实二进制内容为可执行脚本或 PE 二进制，将立即被拦截阻断
    storageTemplate.upload("uploads/avatar.png", in);
}
```

#### 2. 编程式独立特征嗅探 (`StorageGuard.sniff`)
支持直接调用 `StorageGuard.sniff(in)` 或 `StorageGuard.sniff(headerBytes)` 零损耗推导真实文件类型与 MIME：

```java
// 基于输入流零损耗嗅探真实类型
StorageFileType fileType = StorageGuard.sniff(in);
if (fileType != null) {
    System.out.println("真实文件类型: " + fileType.getName());
    System.out.println("标准 MIME 类型: " + fileType.getMimeType());
    System.out.println("是否为高危文件: " + fileType.isDangerous());
}
```

### 预期呈现契约
当攻击者尝试将可执行脚本伪装为 `avatar.png` 上传时，系统立即阻断并抛出清晰的业务异常：
```text
io.github.atengk.boot.storage.exception.StorageException: 检测到文件伪装攻击：文件声明扩展名为 [png]，但实际内容特征为高危文件 [sh]
	at io.github.atengk.boot.storage.guard.StorageGuard.inspect(StorageGuard.java:232)
	at io.github.atengk.boot.storage.template.StorageTemplate.upload(StorageTemplate.java:185)
```
HTTP 返回状态码 `40010`（`STORAGE_FILE_TYPE_MISMATCH`），恶意文件被彻底拒之门外，零磁盘/S3污染。

---

## 18. 大文件分片合并暂存与生命周期状态机

### 场景痛点
在面对数百 MB 甚至数十 GB 的超大文件（如高清音视频母带、系统镜像包、离线数据集或数据库全量备份）时，网络瞬断会导致单流直传全盘失败，必须支持客户端切片断点续传。但在服务端实现分片合并时，若处理不当常面临三大痛点：
1. **内存溢出（OOM）**：在内存中缓存拼接大数据包极易击穿堆内存；
2. **损坏文件入库**：跳号、乱序或缺失分片导致合并出不可用的损坏残片；
3. **磁盘与云端碎片泄漏**：异常中断后大量未合并的临时分片残留，吞噬服务器磁盘空间或持续产生云端存储悬挂计费。

### 核心能力与架构设计
- **100% 双核行为平替**：
  - **Local 本地驱动**：在受管 `.chunks/<uploadId>/` 临时隔离目录中暂存碎片，合并时利用 Java NIO `FileChannel.transferTo` **零拷贝流式按序追加合并**，合并完成后立即原子删除临时目录；
  - **S3 云原生驱动**：标准封装 AWS S3 Multipart Upload 原生 API（`CreateMultipartUpload` -> `UploadPart` -> `CompleteMultipartUpload` / `AbortMultipartUpload`），支持 S3 远程服务端无损合并；
- **1-based 序号与强连续性校验**：分片序号强制为 `1 ~ totalChunks`；合并时严格检测每一片的存在性与连续性，任一分片缺失立即快速失败，抛出 `STORAGE_CHUNK_MISSING`（`40012`）；
- **全生命周期清理状态机**：提供 `abortChunkedUpload(uploadId)` 主动清理取消机制，与完成合并时的自动清理形成闭环，彻底根绝碎片滞留；
- **事件广播闭环**：分片合并完成后自动广播 `StorageUploadedEvent`，打通下游消息通知与业务状态流转。

### 核心代码范式

```java
@Autowired
private StorageTemplate storageTemplate;

public void uploadLargeFile() {
    String targetPath = "large/backup.tar.gz";

    // 1. 初始化分片上传会话，获取全局唯一 uploadId
    String uploadId = storageTemplate.initiateChunkedUpload(targetPath);
    System.out.println("分片上传会话初始化成功, uploadId=" + uploadId);

    // 2. 逐片上传（支持并发或按序上传，序号 1-based）
    // 支持直接传入 byte[] 或 InputStream
    storageTemplate.uploadChunk(uploadId, 1, chunk1Bytes);
    storageTemplate.uploadChunk(uploadId, 2, chunk2Bytes);
    storageTemplate.uploadChunk(uploadId, 3, chunk3Bytes);

    // 3. 校验并完成最终合并（自动验证 1..3 分片完整性，合并后即时清理临时碎片）
    StorageObject object = storageTemplate.completeChunkedUpload(uploadId, targetPath, 3);
    System.out.println("分片合并落盘成功: path=" + object.getPath() + ", size=" + object.getSize() + ", etag=" + object.getEtag());

    // 4. 若客户端主动取消或异常中断，可安全中止并释放已上传碎片
    // storageTemplate.abortChunkedUpload(uploadId);
}
```

### 预期呈现契约
控制台输出：
```text
分片上传会话初始化成功, uploadId=4f5e7a9b0c1d2e3f4a5b6c7d8e9f0a1b
分片合并落盘成功: path=large/backup.tar.gz, size=31457280, etag=e59ff97941044f85df5297e1c302d260
```
若客户端在上传过程中发生异常，仅上传了第 1 片和第 3 片（遗漏第 2 片）即尝试触发合并，系统立即拦截并快速失败：
```text
io.github.atengk.boot.storage.exception.StorageException: 分片合并失败，缺失第 2 个分片（预期总片数: 3）
	at io.github.atengk.boot.storage.driver.LocalDriver.completeChunkedUpload(LocalDriver.java:493)
	at io.github.atengk.boot.storage.template.StorageTemplate.completeChunkedUpload(StorageTemplate.java:569)
```
错误码响应 `40012`（`STORAGE_CHUNK_MISSING`），临时碎片保留在会话中等待重试补传或由客户端调度 `abortChunkedUpload` 安全回收。

---

## 19. 场景样例工程与集成测试

完整端到端 RESTful 控制器、断点续传（HTTP Range 206）与 HMAC 预签名外链交互示范，参见场景样例模块：
👉 [ateng-boot-sample-storage](../ateng-boot-samples/ateng-boot-sample-storage/README.md)

### 本地快速定向自验
```bash
mvn test -pl ateng-boot-storage,ateng-boot-samples/ateng-boot-sample-storage -am -T 1C
```
无需启动任何外部 Docker / MinIO 容器，纯内存与临时目录闭环，秒级全绿准出。
