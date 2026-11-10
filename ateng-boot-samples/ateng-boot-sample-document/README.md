# ateng-boot-sample-document 文档治理场景体验工程

> 专注于演示与自验 `ateng-boot-document` 核心特性（Word 契约模板渲染、动态表格循环与空表折叠、浮动公章、多文档拼装、只读保护、PDF 防泄密水印、跨页骑缝章、AcroForm 动态表单填报与矢量扁平化、256 位 AES 权限锁、150 DPI 高清预览与 CJK 字体探针自省）在真实业务场景下的开箱使用。

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

- **默认/CI 模式**：内置离线模板与图片资源（`src/main/resources/templates/` 与 `src/main/resources/images/`），由 `TemplateInitializer` 在启动期自动自洽探测并创建合法 OpenXML (.docx) 与 PDF 文件，100% 离线自洽，零外部二进制文件强依赖；
- **Local 真实模式**：若需调试真实外部模板或字体，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`（已被 Git 忽略，CI 零风险），填入真实中文字体路径或模板基准路径，激活 `local` Profile 启动即可直连测试。

---

## 3. 核心 Controller 接口与一键体验

### 3.1 Word 契约模板渲染控制器 (`WordContractController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **主合同模板渲染与附件拼装下载** | `WordContractController` | `GET /sample/document/word/contract/download` | `curl -o contract.docx "http://localhost:8080/sample/document/word/contract/download?employeeName=李云龙"` |
| **空数据表格优雅折叠下载** | `WordContractController` | `GET /sample/document/word/contract/empty-table/download` | `curl -o empty_table.docx "http://localhost:8080/sample/document/word/contract/empty-table/download?employeeName=赵刚"` |
| **结构化 DTO 填报渲染下载** | `WordContractController` | `POST /sample/document/word/contract/render` | `curl -X POST "http://localhost:8080/sample/document/word/contract/render" -H "Content-Type: application/json" -d "{\"employeeName\":\"楚云飞\",\"idCard\":\"110101199001011234\",\"department\":\"技术研发中心\",\"postName\":\"资深架构师\",\"salary\":\"35000\",\"withConfidentialityAgreement\":true,\"withSeal\":true}" -o custom_contract.docx` |
| **模板 AST 变量占位符反查** | `WordContractController` | `GET /sample/document/word/contract/variables` | `curl -s "http://localhost:8080/sample/document/word/contract/variables"` |
| **主合同正文纯文本抽取** | `WordContractController` | `GET /sample/document/word/contract/extract-text` | `curl -s "http://localhost:8080/sample/document/word/contract/extract-text?employeeName=李云龙"` |
| **文档只读保护锁加密下载** | `WordContractController` | `GET /sample/document/word/contract/protect/download` | `curl -o protected_contract.docx "http://localhost:8080/sample/document/word/contract/protect/download?employeeName=李云龙&password=Pass123456"` |
| **文档只读保护密码验证** | `WordContractController` | `GET /sample/document/word/contract/protect/verify` | `curl -s "http://localhost:8080/sample/document/word/contract/protect/verify?password=Pass123456&verifyPassword=Pass123456"` |

### 3.2 PDF 安全暗水印与印章控制器 (`PdfWatermarkSealController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **对账单暗水印在线预览** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/watermark/preview` | `curl -o watermark_preview.pdf "http://localhost:8080/sample/document/pdf/stamp/watermark/preview?name=周审计&code=AUD-999&phone=13900008888"` |
| **对账单暗水印附件下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/watermark/download` | `curl -o watermark_statement.pdf "http://localhost:8080/sample/document/pdf/stamp/watermark/download?name=李小四&code=FIN-001&phone=13912345678"` |
| **多页跨页骑缝章无损压印** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/cross-page-seal/download` | `curl -o cross_page_seal.pdf "http://localhost:8080/sample/document/pdf/stamp/cross-page-seal/download"` |
| **尾页绝对坐标盖章下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/absolute-seal/download` | `curl -o absolute_seal.pdf "http://localhost:8080/sample/document/pdf/stamp/absolute-seal/download?pageIndex=-1"` |
| **跳过封面动态重算页码下载** | `PdfWatermarkSealController` | `GET /sample/document/pdf/stamp/page-number/download` | `curl -o page_number.pdf "http://localhost:8080/sample/document/pdf/stamp/page-number/download?skipPages=1"` |

### 3.3 PDF AcroForm 动态表单与安全锁控制器 (`PdfFormSecurityController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **表单域 8 维元数据自省** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/fields` | `curl -s "http://localhost:8080/sample/document/pdf/form/fields"` |
| **保留交互控件预填报下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/interactive/download` | `curl -o interactive_form.pdf "http://localhost:8080/sample/document/pdf/form/interactive/download?supplierName=未来智能网络科技(深圳)有限公司&contactPhone=0755-88889999"` |
| **矢量扁平化固化防篡改下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/form/flattened/download` | `curl -o flattened_form.pdf "http://localhost:8080/sample/document/pdf/form/flattened/download?supplierName=腾讯云计算(深圳)有限责任公司&contactPhone=0755-88889999"` |
| **结构化 DTO 扁平化流式填报** | `PdfFormSecurityController` | `POST /sample/document/pdf/form/fill` | `curl -X POST "http://localhost:8080/sample/document/pdf/form/fill" -H "Content-Type: application/json" -d "{\"supplierName\":\"腾讯云计算(北京)有限责任公司\",\"contactPhone\":\"010-88886666\",\"qualificationPassed\":true}" -o filled_form.pdf` |
| **256 位 AES 权限安全锁下载** | `PdfFormSecurityController` | `GET /sample/document/pdf/security/protect/download` | `curl -o protected_statement.pdf "http://localhost:8080/sample/document/pdf/security/protect/download?userPassword=Pass123456&ownerPassword=AdminOwnerPass#2026"` |
| **权限锁口令解密验证** | `PdfFormSecurityController` | `GET /sample/document/pdf/security/verify` | `curl -s "http://localhost:8080/sample/document/pdf/security/verify?password=Pass123456&verifyPassword=Pass123456"` |

### 3.4 PDF 页面编排、光栅渲染与探针控制器 (`PdfPageOpsController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **150 DPI 高清单页光栅化预览** | `PdfPageOpsController` | `GET /sample/document/pdf/page/render/preview` | `curl -o page_preview.png "http://localhost:8080/sample/document/pdf/page/render/preview?pageIndex=0&dpi=150.0"` |
| **72 DPI 页面缩略图附件下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/render/download` | `curl -o page_thumbnail.png "http://localhost:8080/sample/document/pdf/page/render/download?pageIndex=0&dpi=72.0"` |
| **多对账单文档物理合并下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/merge/download` | `curl -o merged.pdf "http://localhost:8080/sample/document/pdf/page/merge/download"` |
| **对账单单页物理切分切片下载** | `PdfPageOpsController` | `GET /sample/document/pdf/page/split/download` | `curl -o split_page.pdf "http://localhost:8080/sample/document/pdf/page/split/download?pageIndex=0"` |
| **对账单顺时针物理旋转校准** | `PdfPageOpsController` | `GET /sample/document/pdf/page/rotate/download` | `curl -o rotated.pdf "http://localhost:8080/sample/document/pdf/page/rotate/download?angle=90&pageIndex=0"` |
| **11 维核心元数据结构化自省** | `PdfPageOpsController` | `GET /sample/document/inspect/metadata` | `curl -s "http://localhost:8080/sample/document/inspect/metadata"` |
| **起止范围纯文本分页抽取** | `PdfPageOpsController` | `GET /sample/document/inspect/text` | `curl -s "http://localhost:8080/sample/document/inspect/text?startPage=1&endPage=2"` |
| **CJK 中文字体云原生探测自省** | `PdfPageOpsController` | `GET /sample/document/inspect/font` | `curl -s "http://localhost:8080/sample/document/inspect/font"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-document,ateng-boot-samples/ateng-boot-sample-document -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-document README](../../ateng-boot-document/README.md)。
