# ateng-boot-sample-storage 多源对象存储场景体验工程

> 专注于演示与自验 `ateng-boot-storage` 核心特性在真实企业级业务场景（电子合同安全归档、财务凭证流式 ZIP 打包、培训视频断点续传与切片 Seek、设备固件分片直传、知识库 SHA-256 极速秒传与云原生 S3 FinOps 标签治理）下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleStorageApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-storage
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**（未激活 `local` Profile）：`default` 与 `backup` 存储源自动挂载本地 `target/sample-storage/` 隔离沙箱目录，`s3` 存储源自动装配内存自洽桩，无需启动任何外部依赖即可启动运行与跑通全套自动化测试；
- **Local 真实模式**（激活 `local` Profile）：若需连接真实物理 S3 / RustFS / MinIO 服务，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串（如 `http://YOUR_S3_ENDPOINT:9000`），启动应用或执行测试时添加 `--spring.profiles.active=local` 自动生效（该文件已被 `.gitignore` 严格忽略，保证 CI 零风险与凭据安全）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能领域 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电子合同表单上传** | `StorageObjectController` | `POST /sample/storage/object/upload` | `curl -X POST "http://localhost:8080/sample/storage/object/upload" -F "file=@README.md" -F "path=contracts/2026/procurement-ht001.txt"` |
| **携带自定义元数据上传合同** | `StorageObjectController` | `POST /sample/storage/object/upload-with-metadata` | `curl -X POST "http://localhost:8080/sample/storage/object/upload-with-metadata" -F "file=@README.md" -F "path=contracts/2026/strategic-agreement.txt" -F "contractNo=HT-2026-088" -F "signer=Ateng" -F "dept=procurement"` |
| **写入合同审批批注纯文本** | `StorageObjectController` | `POST /sample/storage/object/write-string` | `curl -X POST "http://localhost:8080/sample/storage/object/write-string" --data-urlencode "path=contracts/remarks/ht-2026-001-remark.txt" --data-urlencode "content=法务部已完成合规审查，准予归档"` |
| **读取合同审批批注纯文本** | `StorageObjectController` | `GET /sample/storage/object/read-string` | `curl "http://localhost:8080/sample/storage/object/read-string?path=contracts/remarks/ht-2026-001-remark.txt"` |
| **写入合同归档审计快照 JSON** | `StorageObjectController` | `POST /sample/storage/object/write-json` | `curl -X POST "http://localhost:8080/sample/storage/object/write-json?path=contracts/snapshots/ht-2026-001.json" -H "Content-Type: application/json" -d "{\"contractNo\":\"HT-2026-001\",\"title\":\"2026年度核心云基础设施采购合同\",\"signer\":\"Ateng\",\"department\":\"procurement\",\"auditStatus\":\"ARCHIVED\"}"` |
| **读取合同归档审计快照 JSON** | `StorageObjectController` | `GET /sample/storage/object/read-json` | `curl "http://localhost:8080/sample/storage/object/read-json?path=contracts/snapshots/ht-2026-001.json"` |
| **合同跨路径零拷贝复制** | `StorageObjectController` | `POST /sample/storage/object/copy` | `curl -X POST "http://localhost:8080/sample/storage/object/copy?sourcePath=contracts/2026/procurement-ht001.txt&targetPath=contracts/copy/procurement-ht001-bak.txt"` |
| **合同正式归档移动** | `StorageObjectController` | `POST /sample/storage/object/move` | `curl -X POST "http://localhost:8080/sample/storage/object/move?sourcePath=contracts/copy/procurement-ht001-bak.txt&targetPath=contracts/archive/2026/procurement-ht001.txt"` |
| **归档目录平铺/递归列举** | `StorageObjectController` | `GET /sample/storage/object/list` | `curl "http://localhost:8080/sample/storage/object/list?prefix=contracts/2026/&recursive=false"` |
| **归档目录标准分页检索** | `StorageObjectController` | `GET /sample/storage/object/list-page` | `curl "http://localhost:8080/sample/storage/object/list-page?prefix=contracts/&current=1&size=10&recursive=true"` |
| **单对象天然幂等删除** | `StorageObjectController` | `POST /sample/storage/object/delete` | `curl -X POST "http://localhost:8080/sample/storage/object/delete?path=contracts/archive/2026/procurement-ht001.txt"` |
| **按前缀流式批删 (含防误删熔断)** | `StorageObjectController` | `POST /sample/storage/object/delete-prefix` | `curl -X POST "http://localhost:8080/sample/storage/object/delete-prefix?prefix=logs/audit/2026/"` |
| **级联删除整个目录** | `StorageObjectController` | `POST /sample/storage/object/delete-directory` | `curl -X POST "http://localhost:8080/sample/storage/object/delete-directory?dirPath=logs/audit"` |
| **流式下载与 HTTP 206 断点续传** | `StorageTransferController` | `GET /sample/storage/transfer/download` | `curl -i -H "Range: bytes=0-15" "http://localhost:8080/sample/storage/transfer/download?path=contracts/2026/procurement-ht001.txt"` |
| **分段字节范围读取 (Byte-Range)** | `StorageTransferController` | `GET /sample/storage/transfer/download-range` | `curl "http://localhost:8080/sample/storage/transfer/download-range?path=contracts/2026/procurement-ht001.txt&startOffset=0&length=12&source=default"` |
| **未知流长双阶自适应上传防 OOM** | `StorageTransferController` | `POST /sample/storage/transfer/upload-adaptive` | `curl -X POST "http://localhost:8080/sample/storage/transfer/upload-adaptive" --data-urlencode "path=reports/2026-dynamic-report.txt" --data-urlencode "content=2026年度经营大报表实时动态生成流数据" --data-urlencode "source=s3"` |
| **多份凭证纯流式打包 ZIP 下载** | `StorageTransferController` | `GET /sample/storage/transfer/download-zip` | `curl -i "http://localhost:8080/sample/storage/transfer/download-zip?paths=contracts/2026/procurement-ht001.txt&zipFilename=2026Q1_vouchers.zip" -o 2026Q1_vouchers.zip` |
| **流式零拷贝计算 SHA-256 指纹** | `StorageTransferController` | `GET /sample/storage/transfer/hash` | `curl "http://localhost:8080/sample/storage/transfer/hash?path=contracts/2026/procurement-ht001.txt&algorithm=SHA256"` |
| **知识库文档极速秒传** | `StorageTransferController` | `POST /sample/storage/transfer/fast-upload` | `curl -X POST "http://localhost:8080/sample/storage/transfer/fast-upload?targetPath=knowledge-base/whitepaper.txt&hash=YOUR_SHA256_HASH"` |

---

## 4. 全真自动化回归命令

```bash
# 默认离线自洽回归
mvn test -pl ateng-boot-storage,ateng-boot-samples/ateng-boot-sample-storage -am -T 1C -nsu -B

# 激活 local Profile 直连真实 S3/RustFS/MinIO 联调回归（需配置 application-local.yml）
mvn test -pl ateng-boot-samples/ateng-boot-sample-storage -am "-Dtest=SampleS3DualModeIntegrationTest" "-Dspring.profiles.active=local" "-Dsurefire.failIfNoSpecifiedTests=false" -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-storage README](../../ateng-boot-storage/README.md)。
