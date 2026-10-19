# ateng-boot-sample-storage 多源对象存储端到端自验工程

> 专一多存储源动态路由、HTTP Range 206 断点续传、HMAC-SHA256 预签名外链、对象复移、目录分页检索、便捷存取、纯流式 ZIP 打包、1-based 分片上传状态机与文件魔数安全嗅探 100% 端到端实测沙箱。

---

## 1. 场景定位与功能矩阵

传统对象存储对接与业务集成通常面临以下六大痛点：
1. **云厂商 SDK 臃肿且易冲突**：各云厂商专有 SDK 体积庞大，难以统一维护；
2. **本地 Local 无法平替云端预签名外链**：本地文件下载多依赖手写临时 Controller 或 Nginx，无法与云端 S3 Presigned URL 保持一致的授权外链模型；
3. **多源多桶动态切换繁琐**：公私动静混合存储时，代码充斥 `if-else` 分流；
4. **断点续传实现繁杂**：普通文件流返回往往需要手动处理 HTTP 206 状态码与 Content-Range 报头；
5. **多文件打包引发磁盘与内存雪崩**：传统 ZIP 下载通常先落盘为临时文件再写出，高并发下极易撑爆服务器磁盘；
6. **文件伪装与高危格式渗透风险**：仅通过扩展名或 HTTP Content-Type 无法识别黑客通过改后缀伪装的高危可执行文件或脚本。

`ateng-boot-sample-storage` 提供纯净、轻量、完全离线自洽的企业级多源存储解决方案：
- **纯内存与本地磁盘自验（Zero Docker / MinIO Dependency）**：零外部物理云服务依赖，秒级全绿准出；
- **Spring 原生 Resource 深度桥接**：返回 `ResponseEntity<Resource>`，天然支持 HTTP Range 206 断点续传；
- **Local 内置 HMAC-SHA256 预签名外链**：提供时效与防篡改验证，与云端 S3 Presigned URL 行为 100% 对齐；
- **声明式与编程式三位一体动态切源**：同时支持 `@Storage("backup")` 注解与 `storageTemplate.use("backup")` 链式切换，物理隔离存储目录；
- **纯流式 ZIP 零磁盘临时文件打包**：边从存储介质拉取边写入 `ZipOutputStream`，解耦磁盘 IO；
- **零流截断损耗的文件魔数嗅探**：`StorageGuard` 基于二进制头部魔数探查真实类型，拦截扩展名伪装与高危脚本；
- **1-based 大文件分片状态机**：本地通过 NIO `FileChannel.transferTo` 零拷贝合并，云端封装 S3 原生 Multipart Upload；
- **Spring 原生存储事件广播**：自动发布 `StorageUploadedEvent` 与 `StorageDeletedEvent`，接入事件驱动架构。

---

## 2. 13 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 表单上传与落盘 | `StorageTemplate.upload(String, MultipartFile)` | `POST /api/storage/upload` | HTTP 200 + `Result<StorageObject>` 契约，物理文件成功落盘并广播上传事件 |
| **02** | 文件流式下载与报头 | `StorageResource` + Spring MVC | `GET /api/storage/download` | 标准 `Content-Disposition` 附件下载报头、正确的 `Content-Type` 与内容一致性 |
| **03** | HTTP Range 断点续传 | `StorageResource` 范围读取 | `GET /api/storage/download` (带 `Range: bytes=0-4`) | 返回 HTTP 206 Partial Content，报头 `Content-Range: bytes 0-4/32`，内容精确切片 |
| **04** | HMAC 预签名外链与防篡改 | `StorageTemplate.getPresignedUrl` + `LocalStorageDownloadController` | `GET /api/storage/presigned-url`<br>`GET /storage/local/download` | 有效签名放行下载 200；篡改签名或过期请求立即拦截并返回 HTTP 403 Forbidden |
| **05** | 动态多源物理路径隔离 | `@Storage("backup")` 注解与 `storageTemplate.use("backup")` | `POST /api/storage/upload`<br>`POST /api/storage/upload-backup` | 验证文件分别落盘在 `default` 与 `backup` 物理子目录，相互隔离且读取正确 |
| **06** | 对象复制、移动与幂等删除 | `storageTemplate.copy` / `move` / `delete` | `POST /api/storage/copy`<br>`POST /api/storage/move`<br>`POST /api/storage/delete` | 跨路径复制保留原文件，移动移除源文件，删除已不存在的文件天然幂等返回 true |
| **07** | 目录列举与标准分页检索 | `storageTemplate.list` / `listPage` (`PageQuery`) | `GET /api/storage/list`<br>`GET /api/storage/list-page` | 非递归返回虚拟目录与文件，递归返回全扁平列表；基于 `PageResult` 分页切片无误 |
| **08** | 便捷存取 (String/JSON) | `uploadString` / `downloadString`<br>`uploadJson` / `downloadJson` | `POST /api/storage/write-string`<br>`POST /api/storage/write-json` | 纯文本与 JSON POJO 对象自动序列化与反序列化，无需样板流代码 |
| **09** | 多文件纯流式打包 ZIP | `storageTemplate.downloadAsZip` (`ZipOutputStream`) | `GET /api/storage/download-zip` | 零磁盘临时文件，ZIP 条目解压校验内容完整一致 |
| **10** | 大文件分片状态机与缺片防御 | `initiate` / `uploadChunk` / `complete` / `abort` | `POST /api/storage/chunk/*` | 1-based 分片；缺少第 2 片合并拦截 `STORAGE_CHUNK_MISSING` (40012)；合并后零拷贝正序落盘；abort 清理碎片 |
| **11** | 文件魔数防伪装安全嗅探 | `StorageGuard` 魔数嗅探 | `POST /api/storage/upload` (伪装文件) | 扩展名为 `.png` 但二进制为纯文本，拦截抛出 `40010` 业务错误契约 |
| **12** | 打包 ZIP 重名条目自动重命名 | `downloadAsZip` 内部重名检测 | `GET /api/storage/download-zip` (重复路径) | 压缩包自动重命名同名文件为 `doc (1).txt`，防御 ZIP 条目冲突 |
| **13** | 传输边界 JSR-303 参数校验 | Spring MVC `@Validated` + JSR-303 | `POST /api/storage/copy` (空参数) | 阻断空白源路径，统一返回错误码并提示 `源路径不能为空` |

---

## 3. 本地启动与实测体验

### 1. 启动服务
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-storage spring-boot:run
```

### 2. 上传文件
```bash
curl -X POST -F "file=@avatar.png" http://localhost:8080/api/storage/upload
```

**响应输出**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "path": "avatar.png",
    "filename": "avatar.png",
    "size": 10240,
    "contentType": "image/png",
    "etag": "e2fc714c4727ee9395f324cd2e7f331f",
    "lastModified": "2026-10-19T16:44:48Z"
  },
  "success": true
}
```

### 3. HTTP Range 206 断点续传下载
```bash
curl -i -H "Range: bytes=0-4" "http://localhost:8080/api/storage/download?path=range-test.txt"
```

**响应头输出**：
```http
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-4/32
Content-Length: 5
Content-Type: text/plain;charset=UTF-8
```

### 4. 获取 HMAC 预签名只读外链与访问
```bash
# 1. 获取外链
curl "http://localhost:8080/api/storage/presigned-url?path=secure/data.txt&durationSeconds=300"

# 2. 正常下载
curl -i "http://localhost:8080/storage/local/download?path=secure/data.txt&expires=1792399549&signature=..."

# 3. 篡改签名 -> HTTP 403 Forbidden
curl -i "http://localhost:8080/storage/local/download?path=secure/data.txt&expires=1792399549&signature=tampered"
```

### 5. 伪装文件魔数嗅探拦截 (40010)
```bash
curl -X POST -F "file=@fake_avatar.png" http://localhost:8080/api/storage/upload
```

**拦截响应**：
```json
{
  "code": 40010,
  "msg": "文件魔数强校验失败：未检测到扩展名 [png] 所需的标准头部特征魔数",
  "data": null,
  "success": false
}
```

### 6. 多文件纯流式打包 ZIP 下载
```bash
curl -O -J "http://localhost:8080/api/storage/download-zip?paths=bundle/doc1.txt,bundle/doc2.txt&zipFilename=export.zip"
```

---

## 4. 定向集成测试准出验证

按照 `ateng-boot` 规范，本工程测试用例 100% 离线自洽，无需外置 MinIO 或 Docker 容器：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-storage -am -T 1C -nsu -B
```

**实测结果**：
```text
[INFO] Running io.github.atengk.boot.sample.storage.SampleStorageIntegrationTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.804 s -- in io.github.atengk.boot.sample.storage.SampleStorageIntegrationTest
[INFO] BUILD SUCCESS
```
