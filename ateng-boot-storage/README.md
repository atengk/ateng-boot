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

## 8. Local HMAC 预签名防盗链下载 (`getPresignedUrl`)

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

## 9. 安全路径防卫机制

`ateng-boot-storage` 内置严格的路径遍历防卫引擎（`StorageSecurityUtil`）：
1. **自动过滤空字符与盘符**：拦截 `\0` 空字符截断攻击与 Windows 盘符（如 `C:`）；
2. **深度消解相对路径**：逐级拆解路径片段，对任何包含 `..` 的逃逸路径快速失败抛出 `StorageException(StorageResultCode.INVALID_PATH)`；
3. **路径归一化净化**：多余斜杠（`//`）与 Windows 反斜杠（`\`）自动规范化收敛为标准相对路径；
4. **常量时间签名校验**：预签名 HMAC-SHA256 校验采用 `MessageDigest.isEqual` 常量时间比对，彻底杜绝时序攻击（Timing Attack）。

