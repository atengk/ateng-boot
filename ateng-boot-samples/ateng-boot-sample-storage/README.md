# ateng-boot-sample-storage 多源对象存储端到端自验工程

> 专一多存储源动态路由、HTTP Range 206 断点续传、HMAC-SHA256 预签名外链、客户端直传与分片直传预签名、对象复移、目录分页检索、便捷存取、纯流式 ZIP 打包、1-based 分片上传状态机、文件魔数安全嗅探、流式散列与秒传、前缀流式批删、自定义元数据与 Actuator 探针 100% 端到端实测沙箱。

---

## 1. 场景定位与功能矩阵

传统对象存储对接与业务集成通常面临以下核心痛点：
1. **云厂商 SDK 臃肿且易冲突**：各云厂商专有 SDK 体积庞大，难以统一维护；
2. **本地 Local 无法平替云端预签名外链**：本地文件下载多依赖手写临时 Controller 或 Nginx，无法与云端 S3 Presigned URL 保持一致的授权外链模型；
3. **大文件直传缺少统一本地支持**：云端支持 PUT 预签名直传与分片直传，本地磁盘部署时前端缺少对应的直接写入协议；
4. **多源多桶动态切换繁琐**：公私动静混合存储时，代码充斥 `if-else` 分流；
5. **断点续传实现繁杂**：普通文件流返回往往需要手动处理 HTTP 206 状态码与 Content-Range 报头；
6. **多文件打包引发磁盘与内存雪崩**：传统 ZIP 下载通常先落盘为临时文件再写出，高并发下极易撑爆服务器磁盘；
7. **文件伪装与高危格式渗透风险**：仅通过扩展名或 HTTP Content-Type 无法识别黑客通过改后缀伪装的高危可执行文件或脚本；
8. **散列计算与秒传易 OOM**：计算哈希读取流后导致流指针耗尽无法重用，或缓存大数组击穿堆内存；
9. **孤儿分片碎片泄漏**：客户端中断上传导致大量临时分片永久滞留磁盘。

`ateng-boot-sample-storage` 提供纯净、轻量、完全离线自洽的企业级多源存储解决方案：
- **纯内存与本地磁盘自验（Zero Docker / MinIO Dependency）**：零外部物理云服务依赖，秒级全绿准出；
- **Spring 原生 Resource 深度桥接**：返回 `ResponseEntity<Resource>`，天然支持 HTTP Range 206 断点续传；
- **Local 内置 HMAC-SHA256 预签名只读与直传外链**：提供时效与防篡改验证，与云端 S3 Presigned URL 行为 100% 对齐；
- **声明式与编程式三位一体动态切源**：同时支持 `@Storage("backup")` 注解与 `storageTemplate.use("backup")` 链式切换，物理隔离存储目录；
- **纯流式 ZIP 零磁盘临时文件打包**：边从存储介质拉取边写入 `ZipOutputStream`，解耦磁盘 IO；
- **零流截断损耗的文件魔数嗅探**：`StorageGuard` 基于二进制头部魔数探查真实类型，拦截扩展名伪装与高危脚本；
- **1-based 大文件分片状态机**：本地通过 NIO `FileChannel.transferTo` 零拷贝合并，云端封装 S3 原生 Multipart Upload；
- **流式散列计算与极速秒传**：`StorageHashUtil` 纯原生支持 MD5、SHA-256、SHA-1、CRC32，秒传指纹比对；
- **前缀级流式批量删除**：支持流式切片批删与根目录防误删强行熔断；
- **Spring Boot Actuator 存储健康探针**：自动接入 `/actuator/health` 实时感知底层存储健康度；
- **Spring 原生存储事件广播**：自动发布 `StorageUploadedEvent` 与 `StorageDeletedEvent`，接入事件驱动架构。

---

## 2. 19 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- | :--- |
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
| **14** | 客户端直传预签名与 PUT 直传 | `StorageTemplate.getPresignedUploadUrl` + `LocalStorageUploadController` | `GET /api/storage/presigned-upload`<br>`PUT /storage/local/upload` | 生成直传 URL，前端直接 PUT 上传二进制文件落盘，返回 200 成功契约 |
| **15** | 分块直传预签名与端侧合并 | `getPresignedUploadPartUrl` + `completeChunkedUpload` | `GET /api/storage/presigned-upload-part`<br>`POST /api/storage/chunk/complete` | 生成分块 PUT 直传外链，并发直传各分块后一键合并完成落盘 |
| **16** | 流式散列计算与极速秒传 | `StorageHashUtil` + `calculateHash` + `fastUpload` | `GET /api/storage/hash`<br>`POST /api/storage/fast-upload` | 流指针零截断计算 SHA-256，命中秒传返回 200 元数据，未命中返回 40404 引导直传 |
| **17** | 前缀批量删除与目录级联删除 | `StorageTemplate.deletePrefix` / `deleteDirectory` | `POST /api/storage/delete-prefix`<br>`POST /api/storage/delete-directory` | 流式批量删除指定前缀下所有对象，防误删根目录强行熔断拦截 |
| **18** | 上传携带用户扩展元数据 | `StorageTemplate.upload(..., Map<String, String>)` | `POST /api/storage/upload-with-metadata` | 表单上传同时持久化存储业务元数据（`author`, `env` 等）并在元数据中反查 |
| **19** | Spring Boot Actuator 健康探针 | `StorageHealthIndicator` + Actuator | `GET /actuator/health` | 实时探测底层存储组件健康状态，输出 `UP` 状态与详细诊断信息 |

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

### 3. 获取预签名直传外链并直接 PUT 直传
```bash
# 1. 获取预签名直传外链
curl "http://localhost:8080/api/storage/presigned-upload?path=direct/doc.txt&durationSeconds=300"

# 2. 模拟前端直接 PUT 上传
curl -X PUT -H "Content-Type: text/plain" -d "Direct Content" "http://localhost:8080/storage/local/upload?path=direct/doc.txt&expires=...&signature=..."
```

### 4. 计算哈希与秒传体验
```bash
# 1. 计算对象哈希
curl "http://localhost:8080/api/storage/hash?path=documents/report.pdf&algorithm=SHA256"

# 2. 尝试秒传
curl -X POST "http://localhost:8080/api/storage/fast-upload?targetPath=user/report.pdf&hash=..."
```

### 5. 查看 Actuator 存储健康探针
```bash
curl http://localhost:8080/actuator/health
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
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.125 s -- in io.github.atengk.boot.sample.storage.SampleStorageIntegrationTest
[INFO] BUILD SUCCESS
```
