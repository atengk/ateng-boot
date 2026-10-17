# ateng-boot-sample-storage 多源对象存储自验集成工程

> 基于 `ateng-boot-storage` 的多存储源动态路由、HTTP Range 206 断点续传与 HMAC-SHA256 预签名外链端到端验证示范。

---

## 1. 场景痛点与功能定位

传统对象存储对接通常面临四大痛点：
1. **云厂商 SDK 臃肿且易冲突**：各云厂商专有 SDK 体积庞大，难以统一维护；
2. **本地 Local 无法平替云端预签名外链**：本地文件下载多依赖手写临时 Controller 或 Nginx，无法与云端 S3 Presigned URL 保持一致的授权外链模型；
3. **多源多桶动态切换繁琐**：公私动静混合存储时，代码充斥 `if-else` 分流；
4. **断点续传实现繁杂**：普通文件流返回往往需要手动处理 HTTP 206 状态码与 Content-Range 报头。

`ateng-boot-sample-storage` 提供纯净、轻量的企业级多源存储解决方案：
- **纯内存与本地磁盘自验（Zero Docker/MinIO Dependency）**：零外部物理容器依赖，秒级全绿准出；
- **Spring 原生 Resource 深度桥接**：返回 `ResponseEntity<Resource>`，天然支持 HTTP Range 206 断点续传；
- **Local 内置 HMAC-SHA256 预签名外链**：提供时效与防篡改验证，与云端 S3 Presigned URL 行为 100% 对齐；
- **声明式与编程式三位一体动态切源**：同时支持 `@Storage("backup")` 注解与 `storageTemplate.use("backup")` 链式切换，物理隔离存储目录。

---

## 2. 最小可用配置与代码示例

### 配置文件 (`application.yml`)
```yaml
server:
  port: 8080

ateng:
  boot:
    storage:
      primary: default
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

    @PostMapping("/upload")
    public Result<StorageObject> upload(@RequestParam("file") MultipartFile file) {
        StorageObject object = storageTemplate.upload(file.getOriginalFilename(), file);
        return Result.success(object);
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) throws IOException {
        StorageResource resource = storageTemplate.getResource(path);
        String filename = resource.getFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"")
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    @GetMapping("/presigned-url")
    public Result<PresignedUrl> getPresignedUrl(@RequestParam("path") String path) {
        PresignedUrl presignedUrl = storageTemplate.getPresignedUrl(path, Duration.ofMinutes(5));
        return Result.success(presignedUrl);
    }
}
```

---

## 3. 预期呈现契约与验证

### 1. 文件上传
```bash
curl -X POST -F "file=@avatar.png" http://localhost:8080/api/storage/upload
```
**预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "path": "avatar.png",
    "size": 15360,
    "contentType": "image/png",
    "etag": "d41d8cd98f00b204e9800998ecf8427e",
    "lastModified": "2026-10-17T14:48:00Z"
  }
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

---

## 4. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-storage -am -T 1C
```
涵盖 MultipartFile 上传、流式下载、HTTP Range 206 断点续传、HMAC 外链授权防篡改及 `@Storage` 多源隔离自验，耗时约 5~8 秒。
