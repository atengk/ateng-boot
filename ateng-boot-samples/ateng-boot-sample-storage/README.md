# ateng-boot-sample-storage 多源对象存储自验集成工程

> 基于 `ateng-boot-storage` 的多存储源动态路由、HTTP Range 206 断点续传、HMAC-SHA256 预签名外链、对象复移、目录分页、便捷存取、流式 ZIP 打包、分片上传与文件魔数安全防卫端到端自验示范工程。

---

## 1. 场景痛点与功能定位

传统对象存储对接与业务集成通常面临以下六大痛点：
1. **云厂商 SDK 臃肿且易冲突**：各云厂商专有 SDK 体积庞大，难以统一维护；
2. **本地 Local 无法平替云端预签名外链**：本地文件下载多依赖手写临时 Controller 或 Nginx，无法与云端 S3 Presigned URL 保持一致的授权外链模型；
3. **多源多桶动态切换繁琐**：公私动静混合存储时，代码充斥 `if-else` 分流；
4. **断点续传实现繁杂**：普通文件流返回往往需要手动处理 HTTP 206 状态码与 Content-Range 报头；
5. **多文件打包引发磁盘与内存雪崩**：传统 ZIP 下载通常先落盘为临时文件再写出，高并发下极易撑爆磁盘；
6. **文件伪装与高危格式渗透风险**：仅通过扩展名或 HTTP Content-Type 无法识别黑客通过改后缀伪装的高危可执行文件或脚本。

`ateng-boot-sample-storage` 提供纯净、轻量的企业级多源存储解决方案：
- **纯内存与本地磁盘自验（Zero Docker/MinIO Dependency）**：零外部物理容器依赖，秒级全绿准出；
- **Spring 原生 Resource 深度桥接**：返回 `ResponseEntity<Resource>`，天然支持 HTTP Range 206 断点续传；
- **Local 内置 HMAC-SHA256 预签名外链**：提供时效与防篡改验证，与云端 S3 Presigned URL 行为 100% 对齐；
- **声明式与编程式三位一体动态切源**：同时支持 `@Storage("backup")` 注解与 `storageTemplate.use("backup")` 链式切换，物理隔离存储目录；
- **纯流式 ZIP 零磁盘临时文件打包**：边从存储介质拉取边写入 `ZipOutputStream`，解耦磁盘 IO；
- **零流截断损耗的文件魔数嗅探**：`StorageGuard` 基于二进制头部魔数探查真实类型，拦截扩展名伪装与高危脚本；
- **1-based 大文件分片状态机**：本地通过 NIO `FileChannel.transferTo` 零拷贝合并，云端封装 S3 原生 Multipart Upload；
- **Spring 原生存储事件广播**：自动发布 `StorageUploadedEvent` 与 `StorageDeletedEvent`，接入事件驱动架构。

---

## 2. 最小可用配置与代码示例

### 配置文件 (`application.yml`)
```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-storage

ateng:
  boot:
    storage:
      primary: default
      guard:
        enabled: true
        strict-magic-check: true
      sources:
        default:
          type: local
          local:
            base-path: target/sample-storage/default
            domain: http://localhost:8080
            secret-key: sample-test-secret-key-32-bytes-minimum!
            download-endpoint: /storage/local/download
        backup:
          type: local
          local:
            base-path: target/sample-storage/backup
            domain: http://localhost:8080
            secret-key: sample-test-secret-key-32-bytes-minimum!
            download-endpoint: /storage/local/download
```

### 控制器实现 (`FileController.java`)
```java
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class FileController {

    private final StorageTemplate storageTemplate;
    private final FileStorageDemoService fileStorageDemoService;

    // 1. 文件上传
    @PostMapping("/upload")
    public Result<StorageObject> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(storageTemplate.upload(file.getOriginalFilename(), file));
    }

    // 2. 流式下载（支持 HTTP Range 206）
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) throws IOException {
        StorageResource resource = storageTemplate.getResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    // 3. HMAC 预签名外链
    @GetMapping("/presigned-url")
    public Result<PresignedUrl> getPresignedUrl(@RequestParam("path") String path) {
        return Result.success(storageTemplate.getPresignedUrl(path, Duration.ofMinutes(5)));
    }

    // 4. 对象复制与移动
    @PostMapping("/copy")
    public Result<Boolean> copy(@RequestParam("sourcePath") String sourcePath, @RequestParam("targetPath") String targetPath) {
        return Result.success(storageTemplate.copy(sourcePath, targetPath));
    }

    @PostMapping("/move")
    public Result<Boolean> move(@RequestParam("sourcePath") String sourcePath, @RequestParam("targetPath") String targetPath) {
        return Result.success(storageTemplate.move(sourcePath, targetPath));
    }

    // 5. 目录列举与分页检索
    @GetMapping("/list")
    public Result<List<StorageObject>> list(@RequestParam(value = "prefix", required = false) String prefix,
                                           @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        return Result.success(storageTemplate.list(prefix, recursive));
    }

    @GetMapping("/list-page")
    public Result<PageResult<StorageObject>> listPage(@RequestParam(value = "prefix", required = false) String prefix,
                                                     @ModelAttribute PageQuery pageQuery,
                                                     @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        return Result.success(storageTemplate.listPage(prefix, pageQuery, recursive));
    }

    // 6. 便捷读写存取 (String/JSON)
    @PostMapping("/write-string")
    public Result<StorageObject> writeString(@RequestParam("path") String path, @RequestParam("content") String content) {
        return Result.success(storageTemplate.uploadString(path, content));
    }

    @GetMapping("/read-string")
    public Result<String> readString(@RequestParam("path") String path) {
        return Result.success(storageTemplate.downloadString(path));
    }

    // 7. 多文件纯流式打包 ZIP
    @GetMapping("/download-zip")
    public void downloadZip(@RequestParam("paths") List<String> paths,
                            @RequestParam(value = "zipFilename", defaultValue = "download.zip") String zipFilename,
                            HttpServletResponse response) {
        storageTemplate.downloadAsZip(paths, response, zipFilename);
    }

    // 8. 大文件分片上传
    @PostMapping("/chunk/initiate")
    public Result<String> initiateChunk(@RequestParam("path") String path) {
        return Result.success(storageTemplate.initiateChunkedUpload(path));
    }

    @PostMapping("/chunk/upload")
    public Result<Void> uploadChunk(@RequestParam("uploadId") String uploadId,
                                    @RequestParam("chunkIndex") int chunkIndex,
                                    @RequestParam("file") MultipartFile file) throws IOException {
        storageTemplate.uploadChunk(uploadId, chunkIndex, file.getInputStream(), file.getSize());
        return Result.success();
    }

    @PostMapping("/chunk/complete")
    public Result<StorageObject> completeChunk(@RequestParam("uploadId") String uploadId,
                                              @RequestParam("path") String path,
                                              @RequestParam("totalChunks") int totalChunks) {
        return Result.success(storageTemplate.completeChunkedUpload(uploadId, path, totalChunks));
    }
}
```

### 存储领域事件监听器 (`SampleStorageEventListener.java`)
```java
@Slf4j
@Component
public class SampleStorageEventListener {

    @EventListener
    public void onUploaded(StorageUploadedEvent event) {
        log.info("文件上传完成事件: source={}, path={}, size={}",
                event.getSourceKey(), event.getPath(), event.getSize());
    }

    @EventListener
    public void onDeleted(StorageDeletedEvent event) {
        log.info("文件删除完成事件: source={}, path={}",
                event.getSourceKey(), event.getPath());
    }
}
```

---

## 3. 预期呈现契约与验证

### 1. 文件上传与魔数安全防卫
```bash
# 正常上传文件
curl -X POST -F "file=@avatar.png" http://localhost:8080/api/storage/upload

# 伪装恶意上传（改后缀的伪装文本）-> 40010 拦截
curl -X POST -F "file=@fake_avatar.png" http://localhost:8080/api/storage/upload
```
**安全拦截响应**：
```json
{
  "code": 40010,
  "msg": "文件魔数强校验失败：未检测到扩展名 [png] 所需的标准头部特征魔数",
  "data": null,
  "success": false
}
```

### 2. HTTP Range 断点续传 (HTTP 206)
```bash
curl -i -H "Range: bytes=0-4" http://localhost:8080/api/storage/download?path=range-test.txt
```
**预期响应头**：
```http
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-4/32
Content-Length: 5
Content-Type: text/plain;charset=UTF-8
```

### 3. HMAC 预签名外链访问与防篡改
```bash
# 正常签名访问 -> HTTP 200
curl -i "http://localhost:8080/storage/local/download?path=secure/data.txt&expires=1792248766&signature=..."

# 篡改签名访问 -> HTTP 403 Forbidden
curl -i "http://localhost:8080/storage/local/download?path=secure/data.txt&expires=1792248766&signature=tampered"
```

### 4. 目录分页检索契约
```bash
curl "http://localhost:8080/api/storage/list-page?prefix=reports/&current=1&size=2&recursive=true"
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "records": [
      { "path": "reports/01.txt", "size": 1024, "directory": false },
      { "path": "reports/02.txt", "size": 2048, "directory": false }
    ],
    "total": 3,
    "size": 2,
    "current": 1,
    "pages": 2
  },
  "success": true
}
```

### 5. 流式多文件 ZIP 打包下载
```bash
curl -O -J "http://localhost:8080/api/storage/download-zip?paths=reports/01.txt,reports/02.txt&zipFilename=export.zip"
```

---

## 4. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-storage -am -T 1C
```
涵盖 MultipartFile 上传、流式下载、HTTP Range 206 断点续传、HMAC 外链授权防篡改、`@Storage` 多源隔离、对象复移、目录分页、便捷存取、流式 ZIP 打包、分片上传状态机、魔数安全防卫与领域事件广播共 11 项全场景端到端门禁测试，耗时约 8~12 秒。
