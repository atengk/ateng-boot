# ateng-boot-document 企业级 Word 模板填充与 PDF 综合治理底座 Starter

> 面向生产就绪的 Spring Boot 3 企业级办公文档全场景治理基础设施 Starter。坚持纯原生零臃肿依赖与 Apache 2.0 商业合规双核（poi-tl + Apache PDFBox 3.x）设计，提供 Word 契约模板渲染、动态表格循环追加与跨行跨列合并、浮动电子印章嵌入、多文档物理无缝拼装、文档密码保护，以及 PDF 平铺防泄密暗水印（动态宏变量解析与手机号脱敏掩码）、绝对物理坐标盖章、逐页拆分合并与旋转、跳过封面页码重算、256 位 AES 权限锁、AcroForm 动态表单填报与矢量扁平化不可逆防篡改、CJK 中文字体云原生容器三级弹性自适应探测，以及 RFC 6266 标准化浏览器安全流式直出。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.document.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Word 核心渲染** | 统一模板编译与多源渲染门面 | `WordTemplate` | `ateng.boot.document.word.template-base-dir` | `render(source, data, target)` / `render(source, data, target, configure)`；`renderToBytes(source, data)` / `renderToBytes(source, data, configure)`；`compile(source)` / `compile(source, configure)`；`configureBuilder()` / `createDefaultConfigure()`；`bindLoopRow(builder, tagNames)` / `bindFloatingPicture(builder, tagNames)`；输入源自适应支持 `Resource`、`InputStream`、`File`、`Path`、`byte[]`、`String` | `WordTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **Word 变量与抽取** | 模板语法自省与正文纯文本抽取 | `WordTemplate` | - | `listVariableNames(source)`：逆向解析 AST 变量占位符清单（去重且保序）；`extractText(source)`：提取正文段落与多维表格纯文本 | `WordTemplateTest` |
| **Word 表格折叠** | 空数据安全折叠与图元清理 | `WordTemplate`<br>`LoopRowTableRenderPolicy`<br>`FloatingPictureRenderPolicy` | - | 动态表格列表为空（`items = []`）时自动折叠模板行无语法残留；可选图元（`seal = null`）优雅清空占位符且不阻断渲染 | `WordTemplateTest`<br>`DocumentAuditExportTest` |
| **Word 物理拼装** | 多文档物理无缝拼装 | `WordTemplate` | - | `stitch(target, docxSources)` / `stitch(target, Object... docxSources)`；`stitchToBytes(docxSources)` / `stitchToBytes(Object... docxSources)`；基于 OpenXML 底层结构按序合并多份独立 Docx 文档，保持段落格式、表格与图片上下文连续无缝拼装 | `WordTemplateStitchAndProtectTest`<br>`DocumentSampleIntegrationTest` |
| **Word 密码保护** | 只读保护锁与密码验证 | `WordTemplate` | - | `protect(source, target, password)` / `protect(source, target)`；`protectToBytes(source, password)` / `protectToBytes(source)`；`unprotect(source, target)` / `unprotectToBytes(source)`；`isEnforcedReadonlyProtection(source)` / `validateProtectionPassword(source, password)`；兼容无 `settings.xml` 极简文档自愈防御初始化 | `WordTemplateStitchAndProtectTest` |
| **Word 单元格合并** | 动态表格跨行跨列合并工具 | `WordTemplate` | - | `mergeCellsHorizontal(table, row, fromCol, toCol)`（横向跨列合并）；`mergeCellsVertical(table, col, fromRow, toRow)`（纵向跨行合并）；直接基于 Apache POI `XWPFTable` 底层 XML 网格模型操作 | `WordTemplateTest` |
| **Word 浮动印章** | 独立浮动公章签名图层 | `FloatingPicture`<br>`FloatingPictureRenderPolicy` | - | `FloatingPicture.of(pictureData, dx, dy)`；`ofBytes(bytes, width, height, dx, dy)`；`ofFile(file, width, height, dx, dy)`；`ofStream(stream, width, height, dx, dy)`；基于 OpenXML `CTAnchor` 浮动锚点渲染，彻底摆脱行内文字挤压变形，自动清理 ThreadLocal 防止图层泄漏 | `WordTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 防泄密水印** | 全场景平铺暗水印压印 | `PdfTemplate` | `ateng.boot.document.watermark.*` | `addWatermark(source, target, options)` / `addWatermark(source, target, watermarkText)` / `addWatermark(source, target)`；`addWatermarkToBytes(source, options)` / `addWatermarkToBytes(source, watermarkText)` / `addWatermarkToBytes(source)`；网格倾斜平铺矩阵覆盖，自动解析动态宏与手机号掩码脱敏 | `PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 物理坐标盖章** | 绝对物理坐标电子公章压印 | `PdfTemplate` | - | `addImageStamp(source, target, options)` / `addImageStamp(source, target, image, pageIndex, x, y, width, height)`；`addImageStampToBytes(source, options)` / `addImageStampToBytes(source, image, pageIndex, x, y, width, height)`；支持指定任意页面（包含 `-1` 索引倒数盖章）与绝对物理坐标精准压印 | `PdfTemplateTest` |
| **PDF 跨页骑缝章** | 多页合同跨页骑缝章无损切片压印 | `PdfTemplate`<br>`CrossPageSealOptions`<br>`CrossPageSealEdge` | - | `stampCrossPageSeal(source, target, options)` / `stampCrossPageSeal(source, target, sealImage, options)` / `stampCrossPageSeal(source, target, sealImage)`；`stampCrossPageSealToBytes(...)`；数学无损等分垂直/水平条带切片（保留 Alpha 透明通道），支持右侧/左侧/顶部边缘贴合、skipCover、skipBackCover 及自定义 targetPages | `PdfTemplateCrossPageSealTest`<br>`DocumentAuditExportTest` |
| **PDF 页面高清预览** | 原生页面转高清预览图与防 OOM | `PdfTemplate`<br>`PdfRenderOptions`<br>`PageImageConsumer` | - | `renderPageToImage(source, pageIndex, options)` / `renderPageToPng(source, pageIndex, dpi)` / `renderPageToImageBytes(source, pageIndex, options)` / `renderAllPagesToPng(source, dpi)`；`renderPages(source, startPage, endPage, options, consumer)`；流式消费并在回调后确定性执行 `image.flush()` 释放显存，DPI 72~600 范围保护 | `PdfTemplateRenderTest`<br>`DocumentAuditExportTest` |
| **PDF 元数据自省** | 核心元数据结构化自省与文本抽取 | `PdfTemplate`<br>`PdfMetadata` | - | `inspectMetadata(source)` / `inspectMetadata(source, password)`：自省标题、作者、创建修改时间、总页数、加密状态、PDF 版本等 11 维元数据；`extractText(source, startPage, endPage, password)`：起止范围分页受管纯文本抽取 | `PdfTemplateMetadataTest`<br>`PdfTemplateTest` |
| **PDF 动态表单** | AcroForm 表单填报与扁平化 | `PdfTemplate`<br>`PdfFormFieldDescriptor` | - | `fillForm(source, target, formData, options)` / `fillForm(source, target, formData)`；`fillFormToBytes(...)`；`listFormFields(source)`（枚举 8 维表单字段元数据）；`getFormFields(source)` / `hasAcroForm(source)`；动态 XFA 结构探测与快速失败熔断；自动识别文本框与复选框，默认执行矢量扁平化（Flatten）彻底销毁交互控件防二次篡改 | `PdfTemplateFormTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 物理编排** | 多文件物理合并与拆分 | `PdfTemplate` | - | `merge(target, pdfSources)` / `merge(target, Object... pdfSources)`；`mergeToBytes(pdfSources)` / `mergeToBytes(Object... pdfSources)`；`split(source, outputDir, prefix)`（逐页拆分为单页 PDF 文件）；`splitToBytes(source, pagesPerFile)`（按页数切分为内存列表） | `PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 页面重组** | 页面精准提取与角度旋转 | `PdfTemplate` | - | `extractPages(source, target, fromPage, toPage)` / `extractPageRange(source, target, fromPage, toPage)` / `extractPages(source, target, int... pageIndexes)`；`extractPagesToBytes(...)` / `extractPageRangeToBytes(...)`；`rotatePages(source, target, angle, int... pageIndexes)` / `rotatePagesToBytes(...)`（支持 90°、180°、270° 顺时针旋转） | `PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 页码压印** | 动态页码与跳过封面重算 | `PdfTemplate` | - | `addPageNumbers(source, target, options)` / `addPageNumbers(source, target)`；`addPageNumbersToBytes(source, options)` / `addPageNumbersToBytes(source)`；支持指定 6 种对齐方位、格式模板 `{page}` / `{total}`、跳过封面页码与有效总页数重新核算 | `PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 权限加密** | 256 位 AES 权限锁与解密 | `PdfTemplate` | - | `protect(source, target, options)` / `protect(source, target, userPassword, ownerPassword)`；`protectToBytes(...)`；`unprotect(source, target, password)` / `unprotectToBytes(source, password)`；`isEncrypted(source)`；精细化控制打印、修改、文本复制、表单填报权限 | `PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **防泄密水印模型** | 水印领域参数与动态宏模型 | `WatermarkOptions`<br>`WatermarkMacroParser`<br>`WatermarkLayer` | `ateng.boot.document.watermark.default-text`<br>`ateng.boot.document.watermark.opacity`<br>`ateng.boot.document.watermark.rotation`<br>`ateng.boot.document.watermark.font-size`<br>`ateng.boot.document.watermark.color`<br>`ateng.boot.document.watermark.layer`<br>`ateng.boot.document.watermark.row-spacing`<br>`ateng.boot.document.watermark.col-spacing` | `WatermarkOptions.ofDefault()` / `ofText(text)`；链式构建 `opacity(float)`、`rotation(float)`、`fontSize(float)`、`color(hex)`、`layer(WatermarkLayer)`、`rowSpacing(float)`、`colSpacing(float)`；`addVariable(key, value)` / `setUser(nickname, username, phone)`；`WatermarkMacroParser.parse(text, vars)` 支持 `#{user.*}`、`#{traceId}`、`#{now('pattern')}` | `WatermarkMacroParserTest`<br>`PdfTemplateTest`<br>`DocumentSampleIntegrationTest` |
| **盖章与骑缝章模型** | 盖章参数与跨页骑缝章模型 | `ImageStampOptions`<br>`CrossPageSealOptions`<br>`CrossPageSealEdge` | - | `ImageStampOptions.of(image, pageIndex, x, y, width, height)`；`CrossPageSealOptions.of(sealImage)` / `of(sealImage, width, height)` / `of(sealImage, edge)`；配置贴合边缘（`RIGHT`/`LEFT`/`TOP`/`BOTTOM`）、`margin`、`offset`、`skipCover`、`skipBackCover`、`targetPages`、`opacity`、`layer` | `PdfTemplateTest`<br>`PdfTemplateCrossPageSealTest` |
| **页码与渲染模型** | 页码排版与高清渲染配置 | `PageNumberOptions`<br>`PageNumberPosition`<br>`PdfRenderOptions`<br>`PageImageConsumer` | - | `PageNumberOptions.skipCover(skipPages)` / `ofFormat(format)`；`PdfRenderOptions.of(dpi)` / `of(dpi, maxPages)` / `ofThumbnail(dpi)`；控制 `imageType`、`imageFormat`、`maxPages` 与流式消费回调 | `PdfTemplateTest`<br>`PdfTemplateRenderTest` |
| **元数据与表单模型** | 自省元数据与表单填报模型 | `PdfMetadata`<br>`PdfFormFieldDescriptor`<br>`SecurityOptions`<br>`FormOptions` | - | `PdfMetadata` 结构化自省实体；`PdfFormFieldDescriptor` 8 维字段自省；`SecurityOptions.noPrintAndNoCopy(ownerPassword)` / `withPasswords(userPassword, ownerPassword)`；`FormOptions.defaults()` / `interactive()` | `PdfTemplateMetadataTest`<br>`PdfTemplateFormTest` |
| **中文字体探测器** | CJK 云原生多级弹性自适应探测 | `DocumentFontResolver`<br>`ResolvedFont`<br>`FontResolutionTier` | `ateng.boot.document.pdf.font-path`<br>`ateng.boot.document.pdf.preferred-font-names` | `resolveFont()` / `loadFont(document)` / `loadFont(document, customPath)` / `isFontAvailable()` / `clearCache()` / `getCachedFont()`；按“1. 显式配置路径 -> 2. classpath 寻址 -> 3. 宿主机 OS 目录扫描 -> 4. 缺失时快速失败输出 Dockerfile 修复指引”多级解析 | `DocumentFontResolverTest` |
| **格式转换逃生舱** | 文档跨格式转换统一 SPI 契约 | `DocumentConverter`<br>`DefaultDocumentConverter` | - | `docxToPdf(InputStream)` / `htmlToPdf(InputStream)`；底座内置 `DefaultDocumentConverter` 优雅快速失败并提示扩展指引，应用层可无缝注册自定义 Bean 接管 Gotenberg / LibreOffice 集群 | `DocumentConverterTest` |
| **Word 浏览器直出** | Word 浏览器流式安全响应工具 | `WordWebUtil` | - | `prepareResponse(response, filename)`；`write(response, filename, ...)` / `download(response, filename, ...)`；`writeToResponse(response, filename, templateSource, data)` / `writeToResponse(response, filename, docxSource)` / `writeToResponse(response, filename, writer)`；RFC 6266 / RFC 5987 中文文件名标准化转码，自动补齐 `Content-Type`、`Content-Disposition` 与防页面缓存标头 (`Cache-Control: no-cache, no-store, must-revalidate`) | `WordWebUtilTest`<br>`DocumentSampleIntegrationTest` |
| **PDF 浏览器直出** | PDF 浏览器安全流式预览与下载 | `PdfWebUtil` | - | `prepareResponse(response, filename)` / `prepareResponse(response, filename, inline)`；`write(response, filename, ...)` / `download(response, filename, ...)` / `preview(response, filename, ...)`；`writeToResponse(response, filename, source, inline)`；函数式写入器 `PdfWebWriter` 零缓冲写出，自动补齐 `Cache-Control` 防缓存标头 | `PdfWebUtilTest`<br>`DocumentSampleIntegrationTest` |
| **可视化点检体系** | 9 大场景真实落盘与验真清单 | `DocumentAuditExportTest`<br>`audit-manifest.json` | - | 真实落盘 9 大场景交付物至 `target/test-output/`，伴生生成 SHA-256 结构化 JSON 清单与控制台 ASCII 点检表直达链接 | `DocumentAuditExportTest` |
| **自动装配与主配置** | 底座自动装配与强类型属性模型 | `AtengDocumentAutoConfiguration`<br>`DocumentProperties`<br>`DocumentWordProperties`<br>`DocumentPdfProperties`<br>`DocumentWatermarkProperties` | `ateng.boot.document.enabled`<br>`ateng.boot.document.in-memory-threshold`<br>`ateng.boot.document.temp-dir` | 装配类统配 `@AutoConfiguration` 并挂载缺省 Bean 守卫；统管内存防 OOM 阈值（默认 `10MB`，基于 `DataSize` 强类型）、受管临时交换目录、Word 模板基准路径、PDF 字体偏好与水印默认参数 | `AtengDocumentAutoConfigurationTest`<br>`DocumentPropertiesTest` |
| **因果异常体系** | 语义化异常与专用业务错误码 | `DocumentException`<br>`WordRenderException`<br>`PdfOperationException`<br>`DocumentFontException`<br>`UnsupportedConversionException`<br>`DocumentResultCode` | - | 继承 `BusinessException`，严禁吞咽异常，转译时 100% 传递原始 `cause`；统一状态码 `57000` 至 `57007`（包含 `WORD_RENDER_FAILED`、`PDF_OPERATION_FAILED`、`FONT_NOT_FOUND`、`UNSUPPORTED_CONVERSION`、`TEMPLATE_NOT_FOUND`、`PASSWORD_ERROR`、`FORM_ERROR`） | `DocumentExceptionAndResultCodeTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-document` 是面向企业级生产环境的 Word 模板渲染与 PDF 综合治理基础设施 Starter：
- **纯 Apache 2.0 商业合规双核**：全面基于 `com.deepoove:poi-tl`（Apache POI 驱动）与 `org.apache.pdfbox:pdfbox:3.x` 双核构建，**彻底排除 iText 等商业排他性与 AGPLv3 传染性协议法律风险**；
- **内存防爆与受管物理资源管理**：通过强类型 `DataSize`（默认 10MB）动态划分小文件内存缓冲与大文件磁盘临时文件交换，并在 `finally` 块中执行确定性物理销毁，杜绝堆内存 OOM 与临时文件膨胀；
- **云原生 CJK 字体自适应**：内置三级弹性探测机制，自适应容器与异构 OS 环境，提供贴心的 Dockerfile 修复指引；
- **传输契约与模型绝对解耦**：文档导出 Controller 边界严格遵循显式契约，100% 严禁直接接收或返回持久层实体。

### 引入依赖
在业务模块的 `pom.xml` 中引入（版本由 `ateng-boot-bom` 集中纳管，免配 `<version>`）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-document</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 2.1 推荐配置 (`application.yml`)
所有配置项均具备工业级生产默认值，开箱即用：

```yaml
ateng:
  boot:
    document:
      enabled: true
      # 堆内存缓冲区容量上限，超过此阈值的大文件自动溢出至磁盘临时交换目录防 OOM
      in-memory-threshold: 10MB
      # 受管临时文件交换目录（留空自动回退至系统临时目录下的 ateng-boot-document/）
      temp-dir: ""
      word:
        # Word 模板默认基准扫描路径
        template-base-dir: "classpath:/templates/word/"
      pdf:
        # 自定义中文字体物理文件路径（留空则自动触发系统目录扫描）
        font-path: ""
        # 系统中文字体探测候选偏好名称列表（按序扫描）
        preferred-font-names:
          - "SimSun"
          - "Microsoft YaHei"
          - "Source Han Sans CN"
          - "WenQuanYi Micro Hei"
          - "PingFang SC"
      watermark:
        # 默认防泄密暗水印动态宏模版
        default-text: "#{user.name} (#{user.code}) - #{user.phone} #{now('yyyy-MM-dd')}"
        opacity: 0.18
        rotation: -30.0
        font-size: 16.0
        color: "#990000"
        layer: OVERLAY
        row-spacing: 150.0
        col-spacing: 200.0
```

---

### 2.2 开箱即用五板斧代码

> [!NOTE]
> 以下代码示例均 100% 逆向提取自已全绿通过端到端集成测试的样例工程：[`ateng-boot-sample-document`](../ateng-boot-samples/ateng-boot-sample-document/)。

#### 第一斧：Word 模板动态表格循环追加、浮动印章与多文档拼装

##### 1. Word 模板占位符设计规范 (`contract-template.docx`)
- 文本插值：`{{employeeName}}`、`{{salary}}`、`{{signDate}}`
- 浮动印章占位符：`{{seal}}`
- 动态表格行循环：首列声明为 `{{items}}[projectName]`，后续列声明为 `[role]`、`[duration]`

##### 2. 服务端渲染与拼装代码 (`ContractService.java`)
```java
@Service
public class ContractService {

    private static final String CONTRACT_TEMPLATE_PATH = "classpath:templates/contract-template.docx";
    private static final String ATTACHMENT_TEMPLATE_PATH = "classpath:templates/attachment-clause.docx";
    private static final String SEAL_IMAGE_PATH = "images/company-seal.png";

    private final WordTemplate wordTemplate;

    public ContractService(WordTemplate wordTemplate) {
        this.wordTemplate = wordTemplate;
    }

    public byte[] generateContractDocx(ContractRenderRequest request) {
        // 1. 组装数据模型与浮动公章
        Map<String, Object> dataModel = new HashMap<>();
        dataModel.put("employeeName", request.getEmployeeName());
        dataModel.put("idCard", request.getIdCard());
        dataModel.put("department", request.getDepartment());
        dataModel.put("postName", request.getPostName());
        dataModel.put("salary", request.getSalary());
        dataModel.put("signDate", request.getSignDate());
        dataModel.put("items", request.getItems());

        // 注入独立浮动电子公章（设定尺寸 120x120，相对锚点横向偏移 20pt，纵向偏移 -30pt）
        ClassPathResource sealRes = new ClassPathResource(SEAL_IMAGE_PATH);
        if (sealRes.exists()) {
            try (InputStream is = sealRes.getInputStream()) {
                byte[] sealBytes = is.readAllBytes();
                FloatingPicture seal = FloatingPicture.ofBytes(sealBytes, 120, 120, 20, -30);
                dataModel.put("seal", seal);
            }
        }

        // 2. 编译渲染主合同
        byte[] mainContract = wordTemplate.renderToBytes(CONTRACT_TEMPLATE_PATH, dataModel);

        // 3. 物理无缝拼装保密协议附件
        return wordTemplate.stitchToBytes(mainContract, ATTACHMENT_TEMPLATE_PATH);
    }
}
```

---

#### 第二斧：PDF 平铺防泄密暗水印（宏解析与脱敏）与跳过封面页码重算

##### 业务场景与代码实现 (`FinanceStatementService.java`)
```java
@Service
public class FinanceStatementService {

    private static final String STATEMENT_TEMPLATE_PATH = "classpath:templates/sample-statement.pdf";
    private final PdfTemplate pdfTemplate;

    public FinanceStatementService(PdfTemplate pdfTemplate) {
        this.pdfTemplate = pdfTemplate;
    }

    public byte[] generateWatermarkedStatement(String operatorName, String operatorCode, String operatorPhone) {
        // 1. 组装用户宏上下文（手机号自动掩码脱敏为 138****8000）
        Map<String, Object> userMap = Map.of(
                "name", operatorName != null ? operatorName : "财务专员",
                "code", operatorCode != null ? operatorCode : "FIN-001",
                "phone", operatorPhone != null ? operatorPhone : "13800138000"
        );
        Map<String, Object> vars = Map.of("user", userMap);

        // 2. 压印动态操作人平铺暗水印（前置覆盖图层 OVERLAY，抗拍照与截屏）
        WatermarkOptions watermarkOptions = WatermarkOptions.builder()
                .text("#{user.name} (#{user.code}) - #{user.phone} #{now('yyyy-MM-dd')}")
                .opacity(0.18f)
                .rotation(-30.0f)
                .fontSize(16.0f)
                .color("#990000")
                .layer(WatermarkLayer.OVERLAY)
                .variables(vars)
                .build();

        byte[] watermarkedPdf = pdfTemplate.addWatermarkToBytes(STATEMENT_TEMPLATE_PATH, watermarkOptions);

        // 3. 压印页码：跳过第 1 页封面，从第 2 页起重算有效总页数（呈现为“第 1 页 / 共 1 页”）
        PageNumberOptions pageOptions = PageNumberOptions.builder()
                .format("第 {page} 页 / 共 {total} 页")
                .skipPages(1)
                .recalculateTotal(true)
                .position(PageNumberPosition.BOTTOM_CENTER)
                .fontSize(10.0f)
                .color("#666666")
                .build();

        return pdfTemplate.addPageNumbersToBytes(watermarkedPdf, pageOptions);
    }
}
```

---

#### 第三斧：PDF 256 位 AES 权限安全锁加密防篡改

##### 权限策略加锁实现 (`FinanceStatementService.java`)
```java
public byte[] protectStatement(byte[] sourcePdf, String userPassword, String ownerPassword) {
    SecurityOptions options = SecurityOptions.builder()
            .userPassword(userPassword)    // 打开密码（允许普通用户查看）
            .ownerPassword(ownerPassword)  // 管理员密码（用于解密或提升权限）
            .canPrint(true)                // 允许物理打印
            .canCopy(false)                // 严禁复制文本和图片
            .canModify(false)              // 严禁修改文档内容与图层
            .canFillInForm(false)          // 严禁交互式填写
            .build();

    return pdfTemplate.protectToBytes(sourcePdf, options);
}
```

---

#### 第四斧：AcroForm 动态表单填报与矢量扁平化（Flatten）防篡改

##### 表单填报与固化实现 (`SupplierFormService.java`)
```java
@Service
public class SupplierFormService {

    private static final String SUPPLIER_FORM_TEMPLATE_PATH = "classpath:templates/supplier-form.pdf";
    private final PdfTemplate pdfTemplate;

    public SupplierFormService(PdfTemplate pdfTemplate) {
        this.pdfTemplate = pdfTemplate;
    }

    public byte[] fillSupplierForm(SupplierFormRequest request, boolean flatten) {
        Map<String, Object> formData = new HashMap<>();
        formData.put("supplierName", request.getSupplierName());
        formData.put("contactPhone", request.getContactPhone());
        // 自动识别复选框状态（PDCheckBox.check() / unCheck()）
        formData.put("qualificationPassed", Boolean.TRUE.equals(request.getQualificationPassed()));

        // 生产铁律：flatten = true 将表单字段不可逆固化为静态矢量轮廓，彻底销毁 AcroForm 交互控件
        FormOptions options = FormOptions.builder()
                .flatten(flatten)
                .build();

        return pdfTemplate.fillFormToBytes(SUPPLIER_FORM_TEMPLATE_PATH, formData, options);
    }
}
```

---

#### 第五斧：WordWebUtil 与 PdfWebUtil 浏览器安全流式直出

##### 控制器流式下载与预览端点 (`DocumentExportController.java`)
```java
@RestController
@RequestMapping("/document")
public class DocumentExportController {

    private final ContractService contractService;
    private final FinanceStatementService financeStatementService;
    private final SupplierFormService supplierFormService;

    public DocumentExportController(ContractService contractService,
                                    FinanceStatementService financeStatementService,
                                    SupplierFormService supplierFormService) {
        this.contractService = contractService;
        this.financeStatementService = financeStatementService;
        this.supplierFormService = supplierFormService;
    }

    // 1. 劳动合同 Word 流式下载（标准 attachment 标头）
    @GetMapping("/contract/download")
    public void downloadContract(HttpServletResponse response,
                                 @RequestParam(defaultValue = "张三") String employeeName) {
        ContractRenderRequest request = ContractRenderRequest.builder()
                .employeeName(employeeName)
                .idCard("310101199001011234")
                .department("基础架构研发中心")
                .postName("云原生架构专家")
                .salary("35000")
                .signDate("2026-10-23")
                .items(List.of(
                        ProjectExperience.builder().projectName("分布式多租户 Starter 套件").role("技术负责人").duration("8 个月").build(),
                        ProjectExperience.builder().projectName("企业级 Office/PDF 治理底座").role("核心架构师").duration("3 个月").build()
                ))
                .build();

        contractService.exportContractToWeb(response, request);
    }

    // 2. 财务对账单浏览器内联在线预览（标准 inline 标头）
    @GetMapping("/statement/preview")
    public void previewStatement(HttpServletResponse response,
                                 @RequestParam(defaultValue = "李小四") String name,
                                 @RequestParam(defaultValue = "FIN-886") String code,
                                 @RequestParam(defaultValue = "13812345678") String phone) {
        financeStatementService.previewStatementWeb(response, name, code, phone);
    }

    // 3. 财务对账单 256 位 AES 权限锁加密保护下载
    @GetMapping("/statement/download")
    public void downloadProtectedStatement(HttpServletResponse response,
                                           @RequestParam(defaultValue = "Pass123456") String password) {
        financeStatementService.downloadProtectedStatementWeb(response, "王财务", password, "AdminOwnerPass#2026");
    }

    // 4. 供应商准入登记表 PDF 受控下载（已矢量扁平化）
    @GetMapping("/supplier-form/download")
    public void downloadSupplierForm(HttpServletResponse response,
                                     @RequestParam(defaultValue = "未来智能网络科技(深圳)有限公司") String name,
                                     @RequestParam(defaultValue = "0755-88889999") String phone) {
        SupplierFormRequest request = SupplierFormRequest.builder()
                .supplierName(name)
                .contactPhone(phone)
                .qualificationPassed(true)
                .build();

        supplierFormService.downloadSupplierFormWeb(response, request);
    }
}
```

##### 业务层流式输出协同实现
业务 Service 完成文档渲染与业务组装后，直接调用 `WordWebUtil` 或 `PdfWebUtil` 将流灌入响应，实现纯净的分层解耦与流式直出：
```java
// ContractService.java
public void exportContractToWeb(HttpServletResponse response, ContractRenderRequest req) {
    byte[] docx = generateContractDocument(req);
    String fileName = "劳动聘用合同_" + req.getEmployeeName();
    WordWebUtil.writeToResponse(response, fileName, docx);
}

// FinanceStatementService.java
public void previewStatementWeb(HttpServletResponse response, String operatorName, String operatorCode, String operatorPhone) {
    byte[] pdfBytes = generateWatermarkedStatement(operatorName, operatorCode, operatorPhone);
    PdfWebUtil.preview(response, "财务对账单_安全预览", pdfBytes);
}
```

---

#### 第六斧：Word 模板变量反查、正文抽取与空数据安全折叠

```java
// 1. 逆向自省模板声明的所有占位符变量（有序且去重）
List<String> variables = wordTemplate.listVariableNames("classpath:templates/contract-template.docx");
// 输出: ["employeeName", "idCard", "department", "postName", "salary", "signDate", "items", "seal"]

// 2. 从已生成的 Word 文档流式抽取正文与表格纯文本
String fullText = wordTemplate.extractText(docxBytes);

// 3. 动态表格空数据安全折叠（当列表为空时自动折叠模板行，保留表头，零语法残留）
Map<String, Object> emptyDataModel = Map.of(
    "employeeName", "赵刚",
    "items", Collections.emptyList()
);
byte[] safeDocx = wordTemplate.renderToBytes("classpath:templates/contract-template.docx", emptyDataModel);
```

---

#### 第七斧：PDF 核心元数据自省与受管纯文本抽取

```java
// 1. 结构化自省 PDF 核心元数据（支持带口令密文自省）
PdfMetadata metadata = pdfTemplate.inspectMetadata("classpath:templates/sample-statement.pdf");
System.out.printf("标题: %s, 作者: %s, 总页数: %d, 加密状态: %s, PDF版本: %.1f%n",
        metadata.getTitle(), metadata.getAuthor(), metadata.getNumberOfPages(),
        metadata.isEncrypted(), metadata.getPdfVersion());

// 2. 受管起止页码范围抽取纯文本（1-based 起止页码）
String pageText = pdfTemplate.extractText(pdfBytes, 1, 2, "optionalPassword");
```

---

#### 第八斧：PDFBox 原生页面转高清预览图与防 OOM 流式光栅释放

```java
// 1. 极简将指定页面转为 150 DPI 高清 PNG 字节数组（支持负数索引，-1 为尾页）
byte[] pagePngBytes = pdfTemplate.renderPageToPng(pdfBytes, 0, 150.0f);

// 2. 强类型配置渲染参数（DPI 防爆范围 72.0f ~ 600.0f，防无界内存膨胀）
PdfRenderOptions options = PdfRenderOptions.builder()
        .dpi(150.0f)
        .imageType(ImageType.RGB)
        .maxPages(50)
        .build();

// 3. 流式逐页渲染并在回调后确定性执行 image.flush() 释放光栅显存
pdfTemplate.renderPages(pdfBytes, 1, 5, options, (image, pageIndex) -> {
    // 业务消费每一页 BufferedImage（如上传到对象存储或生成缩略图）
    processThumbnail(image, pageIndex);
    // 回调结束后底座自动调用 image.flush() 释放内存，杜绝 FullGC 与 OOM
});
```

---

#### 第九斧：多页合同跨页骑缝章(Cross-Page Seal)自动垂直等分切片与贴合压印

```java
// 1. 配置跨页骑缝章参数（默认右侧边缘居中贴合，120pt * 120pt）
CrossPageSealOptions sealOptions = CrossPageSealOptions.builder()
        .sealImage("classpath:images/company-seal.png") // 支持 byte[], File, InputStream, BufferedImage 等
        .width(120.0f)
        .height(120.0f)
        .edge(CrossPageSealEdge.RIGHT) // 贴合右边缘（也支持 LEFT / TOP / BOTTOM）
        .margin(0.0f)                 // 0 间距紧密贴边
        .skipCover(true)              // 跳过首页封面
        .skipBackCover(true)          // 跳过末页封底
        .opacity(0.85f)               // 85% 不透明度
        .build();

// 2. 执行压印并返回字节数组（纯 AWT 保留 Alpha 透明通道无损切片）
byte[] sealedPdf = pdfTemplate.stampCrossPageSealToBytes(multiPagePdf, sealOptions);
```
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 跨格式转换逃生舱：接入 Gotenberg / LibreOffice 集群

底座默认提供 `DefaultDocumentConverter`，当未配置外部格式转换驱动时触发优雅快速失败（Fail-Fast）。

在生产需要高保真 Word 转 PDF 或 HTML 转 PDF 时，仅需声明自定义 Bean 实现 [`DocumentConverter`](./src/main/java/io/github/atengk/boot/document/spi/DocumentConverter.java) 即可平滑接管：

```java
@Configuration
public class DocumentConverterConfiguration {

    @Bean
    public DocumentConverter documentConverter(RestClient.Builder restClientBuilder) {
        RestClient restClient = restClientBuilder
                .baseUrl("http://gotenberg-service.infrastructure:3000")
                .build();

        return new DocumentConverter() {
            @Override
            public byte[] docxToPdf(InputStream docxInputStream) {
                try {
                    byte[] docxBytes = docxInputStream.readAllBytes();
                    // 调用 Gotenberg 的 /forms/libreoffice/convert 端点
                    return restClient.post()
                            .uri("/forms/libreoffice/convert")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(new LinkedMultiValueMap<String, Object>() {{
                                add("files", new ByteArrayResource(docxBytes) {
                                    @Override
                                    public String getFilename() {
                                        return "document.docx";
                                    }
                                });
                            }})
                            .retrieve()
                            .body(byte[].class);
                } catch (IOException e) {
                    throw new DocumentException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "调用 Gotenberg 转换 Word 失败", e);
                }
            }

            @Override
            public byte[] htmlToPdf(InputStream htmlInputStream) {
                try {
                    byte[] htmlBytes = htmlInputStream.readAllBytes();
                    return restClient.post()
                            .uri("/forms/chromium/convert/html")
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .body(new LinkedMultiValueMap<String, Object>() {{
                                add("files", new ByteArrayResource(htmlBytes) {
                                    @Override
                                    public String getFilename() {
                                        return "index.html";
                                    }
                                });
                            }})
                            .retrieve()
                            .body(byte[].class);
                } catch (IOException e) {
                    throw new DocumentException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "调用 Gotenberg 转换 HTML 失败", e);
                }
            }
        };
    }
}
```

---

### 3.2 CJK 中文字体云原生探测与 Docker 字体注入方案

PDFBox 渲染中文字符时必须内嵌真实物理字形数据。`DocumentFontResolver` 遵循三级阶梯解析策略：
1. **Tier 1（显式配置优先）**：读取 `ateng.boot.document.pdf.font-path`（支持绝对路径或 `classpath:`）；
2. **Tier 2（系统目录自适应扫描）**：扫描 `/usr/share/fonts`、`/System/Library/Fonts`、`C:\Windows\Fonts`，并按 `preferred-font-names`（思源黑体、微软雅黑、宋体等）匹配；
3. **Tier 3（快速失败防卫与指引）**：若宿主完全无中文字体，直接抛出 `DocumentFontException` 并输出精准的 Docker 修复命令。

#### 各主流 Linux 基础镜像 Dockerfile 修复指南：

````dockerfile
# 方案 A：Debian / Ubuntu 镜像（如 eclipse-temurin:17-jre）
RUN apt-get update && apt-get install -y --no-install-recommends \
    fonts-wqy-microhei \
    && rm -rf /var/lib/apt/lists/*

# 方案 B：Alpine Linux 极简镜像（如 eclipse-temurin:17-jre-alpine）
RUN apk add --no-cache font-noto-cjk

# 方案 C：CentOS / RHEL / Rocky Linux 镜像
RUN yum install -y wqy-microhei-fonts && yum clean all

# 方案 D：直接将字体文件打包入镜像并通过配置锚定
COPY simsun.ttc /app/fonts/simsun.ttc
# 在 application.yml 中配置:
# ateng.boot.document.pdf.font-path: /app/fonts/simsun.ttc
````

---

### 3.3 POI-TL 扩展与自定义渲染策略 (RenderPolicy) 注册

`WordTemplate` 基于成熟的 `poi-tl` 驱动，完全兼容其 `Configure` 扩展体系。应用层可根据业务定制特殊的渲染策略（如自定义动态图表、条形码生成或特殊图层浮动对象）：

```java
// 1. 构建 poi-tl 定制配置
Configure configure = Configure.builder()
        // 绑定特定标签至内置的浮动印章策略（实现浮于文字上方与绝对物理位移）
        .bind("customSeal", new FloatingPictureRenderPolicy())
        // 绑定业务自定义扩展渲染策略
        .bind("chartData", new MyCustomChartRenderPolicy())
        .build();

// 2. 传入 Configure 执行模板渲染
wordTemplate.render(templateSource, dataModel, targetOutput, configure);
```

---

### 3.4 水印动态宏变量与安全掩码规则

`WatermarkMacroParser` 内置支持丰富的动态宏语法与智能容错：

| 宏表达式 | 说明 | 示例输出 | 容错与保底机制 |
| :--- | :--- | :--- | :--- |
| `#{user.name}` / `#{user.nickname}` | 获取当前操作人姓名 | `李云龙` | 优先取传入的 variables Map；缺失时自动回退读取 `UserContextHolder.get()` |
| `#{user.code}` / `#{user.username}` | 获取当前操作人工号/账号 | `FIN-886` | 优先取 variables Map 中的 `code` 或 `username` |
| `#{user.phone}` / `#{user.mobile}` | 获取操作人手机号并自动脱敏 | `138****5678` | **强制自动掩码**：前 3 后 4 保留，中间 4 位转为 `****`，严禁明文外泄 |
| `#{now('yyyy-MM-dd HH:mm')}` | 格式化当前系统时间 | `2026-10-23 14:30` | 格式不传时默认输出 `yyyy-MM-dd`；模式串非法时优雅保底 |
| `#{traceId}` / `#{trace_id}` | 全链路跟踪号透传 | `tr-7b9c2e` | 自动依次探测：变量字典 -> MDC 上下文 |
| `#{expr!'默认值'}` | 表达式缺省回退值 | `部门未分配` | 当属性为 null、空字符串或不存在时输出指定默认值 |

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 纯 Apache 2.0 商业合规防线
- **技术选型绝对合规**：绝不依赖存在商业版权陷阱或 AGPLv3 传染性开源协议的组件（如 iText 2.x/5.x/7.x、Flying Saucer 历史包袱等）；
- **商业友好生态**：`poi-tl` 与 `Apache PDFBox 3.x` 均基于纯 Apache License 2.0 授权，企业级商业闭源系统可 100% 放心免费商用、分发与修改，杜绝法务合规审计阻断风险。

### 4.2 内存防爆 (OOM 防御) 与临时文件确定性销毁
- **动静阈值隔离**：
  - 小于 `in-memory-threshold`（默认 10MB）时，PDFBox 全程在 JVM 堆内存（`RandomAccessReadBuffer`）完成解析与重构，极速吞吐零磁盘 IO 开销；
  - 超过 10MB 的超大 PDF 文档，框架自动切换至磁盘缓存（`RandomAccessReadBufferedFile`），以内存为代价换取系统在突发大并发、大文件导出时 100% 堆内存安全不崩盘；
- **确定性物理销毁**：
  门面内部所有对 PDF 的加载均封装于 `LoadedPdfDocument`，在 `close()` 的 `finally` 块中立即关闭底层句柄并调用 `tempFile.delete()` 物理粉碎临时交换文件，杜绝磁盘空间暗中膨胀泄漏。

### 4.3 OVERLAY 前置覆盖防抹除水印图层
- 水印支持 `WatermarkLayer.OVERLAY`（默认）与 `UNDERLAY`；
- **防泄密核心机理**：`OVERLAY` 模式将水印网格置于页面最高内容流图层（Top-most Content Stream），即使 PDF 页面包含纯色实心背景色块、扫描版位图图片或复杂表格，半透明文字水印依然牢牢覆盖于内容之上，用户手机拍照、截图或局部截取时无法剔除追溯水印。

### 4.4 AcroForm 矢量扁平化 (Flattening) 不可逆防篡改
- **客户端防篡改风险**：普通的交互式表单在客户端打开时，攻击者可直接篡改文本框数值或清空复选框；
- **底层防御原理**：`PdfTemplate.fillForm` 默认配置 `flatten = true`，填报完成后调用 `PDAcroForm.flatten(fields, false)`。该操作会将所有表单控件彻底销毁，将填写的文字和勾选符号转化为页面不可逆的物理矢量路径（PDF Path Operators），使其与正文彻底融为一体，生成不可二次编辑的合规电子凭单。

### 4.5 浏览器端 RFC 6266 / RFC 5987 文件名防乱码治理
- `WordWebUtil` 与 `PdfWebUtil` 遵循国际标准化组织 RFC 6266 与 RFC 5987 协议；
- **双模自适应输出标头**：
  - 纯 ASCII 文件名直接输出 `filename="sample.docx"`；
  - 包含中文或特殊字符的文件名，自动输出兼容现代浏览器的百分号编码格式：
    ```http
    Content-Disposition: attachment; filename="____________.docx"; filename*=UTF-8''%E5%8A%B3%E5%8A%A8%E5%90%88%E5%90%8C.docx
    ```
- **自动防页面缓存**：强制追加 `Cache-Control: no-cache, no-store, must-revalidate`，防御敏感商业凭证被代理服务器或浏览器本地历史缓存窃取。

### 4.6 PDF 256 位 AES 权限锁与 Adobe Acrobat Reader 跨平台兼容性
- `PdfTemplate.protect()` 默认采用标准 256 位 AES 算法（StandardProtectionPolicy, keyLength = 256），该机制完全兼容 Adobe Acrobat Reader DC、Foxit Reader 及主流现代浏览器（Chrome / Edge / Safari / Firefox）内建 PDF 渲染引擎；
- **老旧终端兼容考量**：部分古老或非标嵌入式 PDF 查阅器（如部分旧版 Android 定制阅读器）仅支持 128 位 RC4 算法，若面向老旧特殊终端分发，需评估受众解密兼容能力；
- **无感安全阅览**：施加权限锁时，若仅设置 `ownerPassword` 而将 `userPassword` 置空，用户打开文档时无需输入密码，但在尝试打印、复制文本或修改文档时将被严格拦截，实现防爬防拷贝的无感阅览体验。

---

## 5. 真实场景样例工程 (1:1 Verification Sandbox)

本模块配备了 1:1 精确对应的场景自验沙箱工程：[`ateng-boot-sample-document`](../ateng-boot-samples/ateng-boot-sample-document/)。

### 场景工程覆盖清单
1. **劳动用工合同签署与附件拼装**：[`ContractService.java`](../ateng-boot-samples/ateng-boot-sample-document/src/main/java/io/github/atengk/boot/sample/document/service/ContractService.java)
2. **财务对账单暗水印、页码与权限锁**：[`FinanceStatementService.java`](../ateng-boot-samples/ateng-boot-sample-document/src/main/java/io/github/atengk/boot/sample/document/service/FinanceStatementService.java)
3. **供应商准入表单填报与扁平化**：[`SupplierFormService.java`](../ateng-boot-samples/ateng-boot-sample-document/src/main/java/io/github/atengk/boot/sample/document/service/SupplierFormService.java)
4. **统一 Web 浏览器流式直出控制器**：[`DocumentExportController.java`](../ateng-boot-samples/ateng-boot-sample-document/src/main/java/io/github/atengk/boot/sample/document/controller/DocumentExportController.java)
5. **端到端集成测试套件**：[`DocumentSampleIntegrationTest.java`](../ateng-boot-samples/ateng-boot-sample-document/src/test/java/io/github/atengk/boot/sample/document/DocumentSampleIntegrationTest.java)
6. **9 大企业场景端到端真实落盘与 SHA-256 验真**：[`DocumentAuditExportTest.java`](../ateng-boot-samples/ateng-boot-sample-document/src/test/java/io/github/atengk/boot/sample/document/DocumentAuditExportTest.java)（落盘至 `target/test-output/`，伴生生成 `audit-manifest.json` 与控制台 ASCII 直达链接）

### 本地极速自验验证
```bash
# 执行场景工程端到端集成测试与 9 大场景可视化落盘验证（100% 全绿）
mvn test -pl ateng-boot-samples/ateng-boot-sample-document -am -T 1C -nsu -B
```
