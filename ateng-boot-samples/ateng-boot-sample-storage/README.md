# ateng-boot-sample-storage 多源对象存储端到端自验工程

> 专一多存储源动态路由、云原生 S3 双模自适应实装、未知流长双阶自适应流式分片上传（$\le 5\,\text{MB}$ 内存缓冲防 OOM）、公开桶永久直链访问 (`getUrl`) 与公共读直传 (`uploadPublic`)、HTTP Range 206 字节范围分段切片读取、预签名友好中文文件名响应头重写（RFC 5987 / RFC 6266）、对象业务标签 Tagging 生命周期治理、存储冷热分层与服务端加密、HTTP Range 206 断点续传、HMAC-SHA256 预签名外链、客户端直传与分片直传预签名、对象复移、目录分页检索、便捷存取、纯流式 ZIP 打包、1-based 分片上传状态机、文件魔数安全嗅探、流式散列与秒传、前缀流式批删、自定义元数据与 Actuator 探针 100% 端到端实测沙箱。

---

## 1. 场景定位与功能矩阵

传统对象存储对接与业务集成通常面临以下核心痛点：
1. **云厂商 SDK 臃肿且易冲突**：各云厂商专有 SDK 体积庞大，难以统一维护；
2. **未知流长上传内存爆破 (OOM)**：导出动态报表或实时流转储时，无脑 `readAllBytes()` 全量拉入 JVM 堆内存导致服务宕机；
3. **公开资源缺乏永久直链**：公开桶或挂载 CDN 域名时被迫使用带有效期的临时签名 URL，时效一过页面图片全部裂开；
4. **大文件下载无法灵活切片**：缺乏字节范围分段拉取能力，音视频 Seek 播放与大文件断点续传成本高昂；
5. **预签名外链文件名乱码或晦涩**：云端随机哈希路径导致浏览器下载弹出无意义文件名，手工拼中文又遇浏览器乱码；
6. **对象合规归档与分账标签缺失**：缺乏原生对象标签生命周期支持，无法联动云存储生命周期归档策略；
7. **本地 Local 无法平替云端预签名外链**：本地文件下载多依赖手写临时 Controller 或 Nginx，无法与云端 S3 Presigned URL 保持一致的授权外链模型；
8. **大文件直传缺少统一本地支持**：云端支持 PUT 预签名直传与分片直传，本地磁盘部署时前端缺少对应的直接写入协议；
9. **多源多桶动态切换繁琐**：公私动静混合存储时，代码充斥 `if-else` 分流；
10. **断点续传实现繁杂**：普通文件流返回往往需要手动处理 HTTP 206 状态码与 Content-Range 报头；
11. **多文件打包引发磁盘与内存雪崩**：传统 ZIP 下载通常先落盘为临时文件再写出，高并发下极易撑爆服务器磁盘；
12. **文件伪装与高危格式渗透风险**：仅通过扩展名或 HTTP Content-Type 无法识别黑客通过改后缀伪装的高危可执行文件或脚本；
13. **散列计算与秒传易 OOM**：计算哈希读取流后导致流指针耗尽无法重用，或缓存大数组击穿堆内存；
14. **孤儿分片碎片泄漏**：客户端中断上传导致大量临时分片永久滞留磁盘。

`ateng-boot-sample-storage` 提供纯净、轻量、单套件双模自适应的企业级多源存储解决方案：
- **单测试套件双模自适应架构 (Dual-Mode Single-Suite)**：
  - **默认/CI 模式 (`!local` Profile)**：自动装配内存 MockS3StorageDriver 桩，100% 离线秒级全绿准出（26 项全真测试）；
  - **Local 真实模式 (`local` Profile)**：Mock 桩自动失效，直连物理 RustFS 真实服务（`http://103.236.97.120:47875`，桶 `ateng-test`），全真验证全链路并确定性清理测试残留数据；
- **双阶自适应流式分片缓冲防 OOM**：未知流长上传内存恒定 $\le 5\,\text{MB}$，首阶直传/次阶流式分片，异常自动熔断 abortMultipartUpload；
- **公开桶永久直链与 Canned ACL 公共读**：`getUrl` 三级拓扑自适应（CDN -> Path-Style -> Virtual-Host Style）与 `uploadPublic` 极速交付；
- **字节范围读取 (Byte-Range Read)**：S3 原生 Range 报头与 Local FileChannel 切片，实现大文件断点续传与视频流 Seek；
- **友好中文文件名响应头重写**：RFC 5987 / RFC 6266 标准编码，强制主流浏览器下载弹出人类友好中文文件名；
- **对象标签生命周期治理**：`setObjectTags` / `getObjectTags` / `deleteObjectTags`，打通合规归档与费用审计；
- **生产级连接池与超时治理**：Apache HTTP Client 并发连接池治理与三级强类型 `Duration` 超时防假死；
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

## 2. 26 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证类 / 端点 | 预期响应 / 校验点 |
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
| **14** | 客户端直传预签名与 PUT 直传 | `StorageTemplate.getPresignedUploadUrl` + `LocalStorageUploadController` | `GET /api/storage/presigned-upload`<br>`PUT /storage/local/upload` | 生成直传 URL，前端直接 PUT 上传二进制文件落盘，返回 200 成功契约 |
| **15** | 分块直传预签名与端侧合并 | `getPresignedUploadPartUrl` + `completeChunkedUpload` | `GET /api/storage/presigned-upload-part`<br>`POST /api/storage/chunk/complete` | 生成分块 PUT 直传外链，并发直传各分块后一键合并完成落盘 |
| **16** | 流式散列计算与极速秒传 | `StorageHashUtil` + `calculateHash` + `fastUpload` | `GET /api/storage/hash`<br>`POST /api/storage/fast-upload` | 流指针零截断计算 SHA-256，命中秒传返回 200 元数据，未命中返回 40404 引导直传 |
| **17** | 前缀批量删除与目录级联删除 | `StorageTemplate.deletePrefix` / `deleteDirectory` | `POST /api/storage/delete-prefix`<br>`POST /api/storage/delete-directory` | 流式批量删除指定前缀下所有对象，防误删根目录强行熔断拦截 |
| **18** | 上传携带用户扩展元数据 | `StorageTemplate.upload(..., Map<String, String>)` | `POST /api/storage/upload-with-metadata` | 表单上传同时持久化存储业务元数据（`author`, `env` 等）并在元数据中反查 |
| **19** | Spring Boot Actuator 健康探针 | `StorageHealthIndicator` + Actuator | `GET /actuator/health` | 实时探测底层存储组件健康状态，输出 `UP` 状态与详细诊断信息 |
| **20** | 未知流长自适应上传防 OOM | `S3StorageScenarioService.uploadAdaptiveStream` | `SampleS3DualModeIntegrationTest` | 双阶有界内存缓冲（$\le 5\,\text{MB}$），首阶一步直传，超阶流式分片上传，异常自动 abort 清理 |
| **21** | 公共读直传与永久直链访问 | `S3StorageScenarioService.uploadPublicRead` / `getPermanentPublicUrl` | `SampleS3DualModeIntegrationTest` | 挂载 `ObjectCannedACL.PUBLIC_READ`，三级拓扑自适应生成永久直接访问 URL（无临时签名字段） |
| **22** | 字节范围切片局部读取 | `S3StorageScenarioService.downloadByteRange` | `SampleS3DualModeIntegrationTest` | 指定 startOffset 与 length，精准拉取分段切片（用于断点续传与视频流 Seek） |
| **23** | 预签名友好中文文件名响应头 | `S3StorageScenarioService.getPresignedDownloadUrlWithFriendlyFilename` | `SampleS3DualModeIntegrationTest` | 基于 RFC 5987 / RFC 6266 标准构建 `response-content-disposition` 响应头，防乱码弹窗 |
| **24** | 对象业务标签完整生命周期 | `S3StorageScenarioService.setObjectTags` / `getObjectTags` / `deleteObjectTags` | `SampleS3DualModeIntegrationTest` | 键值对业务标签全量覆盖设置、精准反查与天然幂等清空 |
| **25** | 存储元数据与冷热分层提取 | `S3StorageScenarioService.uploadStandard` / `getObjectDetails` | `SampleS3DualModeIntegrationTest` | 对象元数据读取，校验 `storageClass` 与 `serverSideEncryption` 加密状态映射 |
| **26** | S3 连通性探测与运维诊断 | `S3StorageScenarioService.pingS3Source` / `getS3Diagnostics` | `SampleS3DualModeIntegrationTest` | 快速探测 S3 存储源健康状态，提取包含连接池、寻址模式与 Bucket 信息的诊断字典 |

---

## 3. S3StorageScenarioService 生产场景业务编排

业务组件 [`S3StorageScenarioService.java`](src/main/java/io/github/atengk/boot/sample/storage/service/S3StorageScenarioService.java) 封装了 S3 生产级加固高阶业务场景：

```java
@Autowired
private S3StorageScenarioService scenarioService;

// 1. 未知流长自适应上传（双阶缓冲防 OOM）
StorageObject obj = scenarioService.uploadAdaptiveStream("reports/dynamic.txt", in, "text/plain");

// 2. 赋予公共读权限并获取永久直连 URL
scenarioService.uploadPublicRead("assets/banner.html", in, "text/html", size);
String directUrl = scenarioService.getPermanentPublicUrl("assets/banner.html");

// 3. 字节范围切片读取 (例如截取从 10 字节开始的 5 字节数据)
byte[] slice = scenarioService.downloadByteRange("data/sample.dat", 10, 5);

// 4. 生成带友好中文下载文件名的预签名安全下载链接
PresignedUrl presignedUrl = scenarioService.getPresignedDownloadUrlWithFriendlyFilename(
        "reports/monthly.xlsx", Duration.ofMinutes(15), "2026年度业务审计报表.xlsx");

// 5. 对象业务标签 Tagging 生命周期治理
scenarioService.setObjectTags("logs/audit.log", Map.of("env", "uat", "dept", "tech"));
Map<String, String> tags = scenarioService.getObjectTags("logs/audit.log");
scenarioService.deleteObjectTags("logs/audit.log");

// 6. 健康探测与诊断提取
boolean healthy = scenarioService.pingS3Source();
Map<String, Object> diagnostics = scenarioService.getS3Diagnostics();
```

---

## 4. 本地真实 RustFS / MinIO 服务联调指南

为了兼顾 CI 自动化构建 100% 离线自洽与本地真实物理中间件联调，工程遵循 **ADR-0028** 与 **ADR-0061** 规范建立安全联调机制：

### 1. 配置资产与机密卫生保护
1. 复制模板文件为本地配置文件：
   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```
2. 在 `src/main/resources/application-local.yml` 中配置真实中间件凭据（该文件已被 `.gitignore` 规则收敛忽略，绝对严禁入库）：
   ```yaml
   ateng:
     boot:
       storage:
         sources:
           s3:
             type: s3
             s3:
               endpoint: http://103.236.97.120:47875 # 真实 RustFS / MinIO 服务地址
               bucket: ateng-test                   # 真实存储桶
               access-key: YOUR_ACCESS_KEY
               secret-key: YOUR_SECRET_KEY
               region: us-east-1
               path-style: true
               connect-timeout: 10s
               socket-timeout: 30s
               api-call-timeout: 60s
               max-connections: 50
               auto-create-bucket: false
   ```

### 2. 激活 local Profile 执行全真测试
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-storage -am "-Dtest=SampleS3DualModeIntegrationTest" "-Dspring.profiles.active=local" "-Dsurefire.failIfNoSpecifiedTests=false" -nsu -B
```
- **全真断言与现场恢复**：测试将在真实远程物理服务上全真走通上传、直链、Range 切片、标签、预签名与元数据链路，并在 `@AfterEach` 中自动清理测试残留对象，实现 100% 确定性现场恢复。

---

## 5. 定向集成测试准出验证

按照 `ateng-boot` 规范，本工程测试用例默认 100% 离线自洽，无需外置 MinIO 或 Docker 容器：

```bash
mvn test -pl ateng-boot-storage,ateng-boot-samples/ateng-boot-sample-storage -am -T 1C -nsu -B
```

**实测结果**：
```text
[INFO] Running io.github.atengk.boot.sample.storage.SampleS3DualModeIntegrationTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.156 s -- in io.github.atengk.boot.sample.storage.SampleS3DualModeIntegrationTest
[INFO] Running io.github.atengk.boot.sample.storage.SampleStorageIntegrationTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.962 s -- in io.github.atengk.boot.sample.storage.SampleStorageIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for io.github.atengk:ateng-boot 1.2.0:
[INFO] 
[INFO] io.github.atengk:ateng-boot ........................ SUCCESS
[INFO] io.github.atengk:ateng-boot-core ................... SUCCESS
[INFO] io.github.atengk:ateng-boot-web .................... SUCCESS
[INFO] io.github.atengk:ateng-boot-storage ................ SUCCESS
[INFO] io.github.atengk:ateng-boot-samples ................ SUCCESS
[INFO] io.github.atengk:ateng-boot-sample-storage ......... SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```
