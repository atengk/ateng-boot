# ateng-boot-sample-document 文档治理场景体验工程

> 专注于演示与自验 `ateng-boot-document` 核心特性（Word 契约模板渲染、动态行/列循环、空表折叠、浮动公章、分页拼装、只读编辑锁、Agile AES-256 文件加密、页眉 VML 水印、PDF 文本/图片双模防泄密水印、关键字自动盖章、跨页骑缝章、物理不可逆光栅涂黑脱敏、AcroForm 表单填报与扁平化、256 位 AES 权限锁、页面合并/拆分/删除/重排/插入、图转 PDF、元数据更新剥离、高清图像预览流、CJK 字体探针自省与双模跨格式转档）在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 DocumentSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-document
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式（`!local`）**：内置离线模板与图片资源（`src/main/resources/templates/` 与 `src/main/resources/images/`），由 `TemplateInitializer` 自动保障模板资产就绪，并注入 `@Profile("!local")` 的 `OfflineSimulatedDocumentConverter`（基于 POI 与 PDFBox 合成包含原文内容的合法 PDF），100% 离线自洽，无需启动 Gotenberg 外部容器即可跑通全部接口与回归测试；
- **Local 真实模式（`local`）**：若需接入真实本地模板、完整 CJK 字体或 Gotenberg 转换集群，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`（已被 `.gitignore` 忽略，CI 零风险）并填入真实路径与 `base-url`，激活 `local` Profile 启动即可平滑切换为 `GotenbergDocumentConverter` 直连联调：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-document -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

### 3.1 Word 契约模板渲染与安全控制 (`WordContractController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **主合同模板渲染与附件拼装下载** | `WordContractController` | `GET /sample/document/word/contract/download` | `curl -o contract.docx "http://localhost:8080/sample/document/word/contract/download?employeeName=李云龙"` |
| **空数据表格优雅折叠下载** | `WordContractController` | `GET /sample/document/word/contract/empty-table/download` | `curl -o empty_table.docx "http://localhost:8080/sample/document/word/contract/empty-table/download?employeeName=赵刚"` |
| **结构化 DTO 填报渲染下载** | `WordContractController` | `POST /sample/document/word/contract/render` | `curl -X POST "http://localhost:8080/sample/document/word/contract/render" -H "Content-Type: application/json" -d "{\"employeeName\":\"楚云飞\",\"idCard\":\"110101199001011234\",\"department\":\"技术研发中心\",\"postName\":\"资深架构师\",\"salary\":\"35000\",\"signDate\":\"2026-11-18\",\"items\":[{\"projectName\":\"核心中台重构\",\"role\":\"架构师\",\"duration\":\"6 个月\"}],\"withConfidentialityAgreement\":true,\"withSeal\":true}" -o custom_contract.docx` |
| **模板 AST 变量占位符反查** | `WordContractController` | `GET /sample/document/word/contract/variables` | `curl -s "http://localhost:8080/sample/document/word/contract/variables"` |
| **主合同正文纯文本抽取** | `WordContractController` | `GET /sample/document/word/contract/extract-text` | `curl -s "http://localhost:8080/sample/document/word/contract/extract-text?employeeName=李云龙"` |
| **文档只读编辑锁保护下载** | `WordContractController` | `GET /sample/document/word/contract/protect/download` | `curl -o protected_contract.docx "http://localhost:8080/sample/document/word/contract/protect/download?employeeName=李云龙&password=Pass#2026"` |
| **文档只读保护密码校验** | `WordContractController` | `GET /sample/document/word/contract/protect/verify` | `curl -s "http://localhost:8080/sample/document/word/contract/protect/verify?password=Pass#2026&verifyPassword=Pass#2026"` |
| **文档结构元数据自省（含加密穿透）** | `WordContractController` | `GET /sample/document/word/contract/metadata` | `curl -s "http://localhost:8080/sample/document/word/contract/metadata?employeeName=李云龙"` |
| **动态表格单元格跨列合并下载** | `WordContractController` | `GET /sample/document/word/contract/merge-cells/download` | `curl -o merged_cells.docx "http://localhost:8080/sample/document/word/contract/merge-cells/download?employeeName=李云龙"` |
| **Agile AES-256 文件加密下载** | `WordContractController` | `GET /sample/document/word/contract/encrypt/download` | `curl -o aes_encrypted.docx "http://localhost:8080/sample/document/word/contract/encrypt/download?employeeName=李云龙&password=AesOpen#2026"` |
| **Agile AES-256 解密还原下载** | `WordContractController` | `GET /sample/document/word/contract/decrypt/download` | `curl -o aes_decrypted.docx "http://localhost:8080/sample/document/word/contract/decrypt/download?employeeName=李云龙&password=AesOpen#2026"` |
| **Agile AES-256 加密状态与口令校验** | `WordContractController` | `GET /sample/document/word/contract/encrypt/verify` | `curl -s "http://localhost:8080/sample/document/word/contract/encrypt/verify?password=AesOpen#2026&verifyPassword=AesOpen#2026"` |
| **页眉 VML 对角动态宏水印下载** | `WordContractController` | `GET /sample/document/word/contract/watermark/download` | `curl -o watermarked_contract.docx "http://localhost:8080/sample/document/word/contract/watermark/download?employeeName=李云龙&operatorName=法务专员"` |
| **StitchOptions 分页拼装导出** | `WordContractController` | `GET /sample/document/word/contract/stitch/download` | `curl -o stitched_contract.docx "http://localhost:8080/sample/document/word/contract/stitch/download?employeeName=李云龙&pageBreak=true"` |
| **SpringEL 与横向动态列循环报表** | `WordContractController` | `GET /sample/document/word/report/quarterly-columns/download` | `curl -o quarterly_report.docx "http://localhost:8080/sample/document/word/report/quarterly-columns/download?department=platform-arch"` |

### 3.2 PDF 安全暗水印与印章控制 (`PdfWatermarkSealController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **对账单文本暗水印在线预览** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/watermark/preview` | `curl -o watermark_preview.pdf "http://localhost:8080/sample/document/pdf/stamp/watermark/preview?name=周审计&code=AUD-999&phone=13900008888"` |
| **对账单文本暗水印附件下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/watermark/download` | `curl -o watermark_statement.pdf "http://localhost:8080/sample/document/pdf/stamp/watermark/download?name=李小四&code=FIN-001&phone=13912345678"` |
| **对账单 Logo 图片矩阵水印下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/image-watermark/download` | `curl -o image_watermark.pdf "http://localhost:8080/sample/document/pdf/stamp/image-watermark/download?skipPages=0"` |
| **多页跨页骑缝章无损压印** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/cross-page-seal/download` | `curl -o cross_page_seal.pdf "http://localhost:8080/sample/document/pdf/stamp/cross-page-seal/download"` |
| **尾页绝对坐标盖章下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/absolute-seal/download` | `curl -o absolute_seal.pdf "http://localhost:8080/sample/document/pdf/stamp/absolute-seal/download?pageIndex=-1"` |
| **关键字左下角坐标自省查询** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/keyword-seal/positions` | `curl -s "http://localhost:8080/sample/document/pdf/stamp/keyword-seal/positions?keyword=Settled"` |
| **关键字定位自动电子公章压印** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/keyword-seal/download` | `curl -o keyword_seal.pdf "http://localhost:8080/sample/document/pdf/stamp/keyword-seal/download?keyword=Settled&occurrence=LAST"` |
| **跳过封面动态重算页码下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/page-number/download` | `curl -o page_number.pdf "http://localhost:8080/sample/document/pdf/stamp/page-number/download?skipPages=1"` |

### 3.3 PDF AcroForm 表单、物理涂黑与安全治理 (`PdfFormSecurityController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **表单域 8 维元数据自省** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/fields` | `curl -s "http://localhost:8080/sample/document/pdf/form/fields"` |
| **保留交互控件预填报下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/interactive/download` | `curl -o interactive_form.pdf "http://localhost:8080/sample/document/pdf/form/interactive/download?supplierName=未来智能网络科技(深圳)有限公司&contactPhone=0755-88889999"` |
| **矢量扁平化固化防篡改下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/flattened/download` | `curl -o flattened_form.pdf "http://localhost:8080/sample/document/pdf/form/flattened/download?supplierName=腾讯云计算(深圳)有限责任公司&contactPhone=0755-88889999"` |
| **结构化 DTO 扁平化流式填报** | `PdfFormSecurityController` | `POST /sample/document/pdf/form/fill` | `curl -X POST "http://localhost:8080/sample/document/pdf/form/fill" -H "Content-Type: application/json" -d "{\"supplierName\":\"腾讯云计算(北京)有限责任公司\",\"contactPhone\":\"010-88886666\",\"qualificationPassed\":true}" -o filled_form.pdf` |
| **256 位 AES 权限安全锁下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/security/protect/download` | `curl -o protected_statement.pdf "http://localhost:8080/sample/document/pdf/security/protect/download?userPassword=Pass123456&ownerPassword=AdminOwnerPass#2026"` |
| **权限锁口令解密验证** | `PdfFormSecurityController` | `GET /sample/document/pdf/security/verify` | `curl -s "http://localhost:8080/sample/document/pdf/security/verify?password=Pass123456&verifyPassword=Pass123456"` |
| **加密 PDF 口令穿透元数据自省** | `PdfFormSecurityController` | `GET /sample/document/pdf/security/protected-metadata` | `curl -s "http://localhost:8080/sample/document/pdf/security/protected-metadata?password=Pass123456"` |
| **DTO 驱动敏感内容物理光栅涂黑** | `PdfFormSecurityController` | `POST /sample/document/pdf/redact/download` | `curl -X POST "http://localhost:8080/sample/document/pdf/redact/download" -H "Content-Type: application/json" -d "{\"keywords\":[\"1,850,200.00\"],\"maskColor\":\"#000000\",\"dpi\":150.0}" -o redacted.pdf` |
| **GET 快捷关键字物理涂黑下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/redact/keywords/download` | `curl -o redacted_kw.pdf "http://localhost:8080/sample/document/pdf/redact/keywords/download?keyword=12,000.00"` |
| **PDF 摘要元数据增量更新下载** | `PdfFormSecurityController` | `POST /sample/document/pdf/metadata/update` | `curl -X POST "http://localhost:8080/sample/document/pdf/metadata/update" -H "Content-Type: application/json" -d "{\"title\":\"2026 Q3 审计归档对账单\",\"author\":\"阿腾内控中心\",\"subject\":\"财务结算审计\"}" -o meta_updated.pdf` |
| **PDF 摘要与 XMP 隐私元数据剥离** | `PdfFormSecurityController` | `GET /sample/document/pdf/metadata/strip/download` | `curl -o meta_stripped.pdf "http://localhost:8080/sample/document/pdf/metadata/strip/download"` |

### 3.4 PDF 页面编排、光栅渲染与探针 (`PdfPageOpsController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **150 DPI 高清单页光栅化预览** | `PdfPageOpsController` | `GET /sample/document/pdf/page/render/preview` | `curl -o page_preview.png "http://localhost:8080/sample/document/pdf/page/render/preview?pageIndex=0&dpi=150.0"` |
| **72 DPI 页面缩略图附件下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/render/download` | `curl -o page_thumbnail.png "http://localhost:8080/sample/document/pdf/page/render/download?pageIndex=0&dpi=72.0"` |
| **多对账单文档物理合并下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/merge/download` | `curl -o merged.pdf "http://localhost:8080/sample/document/pdf/page/merge/download"` |
| **对账单单页物理切分切片下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/split/download` | `curl -o split_page.pdf "http://localhost:8080/sample/document/pdf/page/split/download?pageIndex=0"` |
| **对账单顺时针物理旋转校准** | `PdfPageOpsController` | `GET /sample/document/pdf/page/rotate/download` | `curl -o rotated.pdf "http://localhost:8080/sample/document/pdf/page/rotate/download?angle=90&pageIndex=0"` |
| **企业图像资产合成 PDF 下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/images-to-pdf/download` | `curl -o images_to_pdf.pdf "http://localhost:8080/sample/document/pdf/page/images-to-pdf/download?fitToImage=true"` |
| **物理删除指定页面下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/delete/download` | `curl -o deleted_page.pdf "http://localhost:8080/sample/document/pdf/page/delete/download?pageIndex=0"` |
| **页面倒序重排重组下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/reorder/download` | `curl -o reordered.pdf "http://localhost:8080/sample/document/pdf/page/reorder/download"` |
| **指定索引位置插入附页下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/insert/download` | `curl -o inserted.pdf "http://localhost:8080/sample/document/pdf/page/insert/download?insertAtPageIndex=1"` |
| **11 维核心元数据结构化自省** | `PdfPageOpsController` | `GET /sample/document/inspect/metadata` | `curl -s "http://localhost:8080/sample/document/inspect/metadata"` |
| **起止范围纯文本分页抽取** | `PdfPageOpsController` | `GET /sample/document/inspect/text` | `curl -s "http://localhost:8080/sample/document/inspect/text?startPage=1&endPage=2"` |
| **CJK 中文字体云原生探测自省** | `PdfPageOpsController` | `GET /sample/document/inspect/font` | `curl -s "http://localhost:8080/sample/document/inspect/font"` |

### 3.5 跨格式文档转换双模体验 (`DocumentConvertController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **当前转换器 SPI 与运行模式查询** | `DocumentConvertController` | `GET /sample/document/convert/status` | `curl -s "http://localhost:8080/sample/document/convert/status"` |
| **默认 Word 合同转 PDF 下载** | `DocumentConvertController` | `GET /sample/document/convert/word-to-pdf/download` | `curl -o contract_converted.pdf "http://localhost:8080/sample/document/convert/word-to-pdf/download?employeeName=李云龙"` |
| **结构化 DTO 合同渲染转 PDF** | `DocumentConvertController` | `POST /sample/document/convert/word-to-pdf` | `curl -X POST "http://localhost:8080/sample/document/convert/word-to-pdf" -H "Content-Type: application/json" -d "{\"employeeName\":\"魏大勇\",\"idCard\":\"110101198808081234\",\"department\":\"特勤突击大队\",\"postName\":\"特勤前线专家\",\"salary\":\"52000\",\"signDate\":\"2026-11-18\",\"items\":[{\"projectName\":\"核心前线防务\",\"role\":\"前沿突击组长\",\"duration\":\"6 个月\"}]}" -o dto_converted.pdf` |
| **预设通知单 HTML 转 PDF 下载** | `DocumentConvertController` | `GET /sample/document/convert/html-to-pdf/download` | `curl -o notice.pdf "http://localhost:8080/sample/document/convert/html-to-pdf/download?title=Q3%20Settlement%20Notice"` |
| **自定义 HTML 正文 DTO 转 PDF** | `DocumentConvertController` | `POST /sample/document/convert/html-to-pdf` | `curl -X POST "http://localhost:8080/sample/document/convert/html-to-pdf" -H "Content-Type: application/json" -d "{\"title\":\"Audit Conversion Report\",\"htmlContent\":\"<h1>Audit Conversion Report</h1><p>Status: Verified</p>\"}" -o html_converted.pdf` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-document,ateng-boot-samples/ateng-boot-sample-document -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-document README](../../ateng-boot-document/README.md)。
