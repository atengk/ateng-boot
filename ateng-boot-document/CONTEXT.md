# Document Context

`ateng-boot-document` 是面向企业级应用的高性能、轻量级办公文档处理 Starter，深度整合 Apache POI、poi-tl 与 Apache PDFBox 3.x，提供覆盖“Word 合同模板变量替换、行/列动态表格扩展、空表优雅折叠、图元容错、分页拼装、只读编辑锁（`ReadonlyProtection`）、Agile AES-256 文件打开级加密、页眉 VML 水印与结构元数据自省，PDF 文本/图片双模防泄密暗水印（含多行与旋转坐标系校准）、关键字定位自动盖章、多页跨页骑缝章无损切片、物理不可逆光栅涂黑脱敏（Redaction）、页面增删插排与图转 PDF、AcroForm 表单填报与不可逆矢量扁平化、256 位 AES 权限锁与全量口令穿透、元数据更新与剥离、页面原生高清转图片与 Web 图像流直出、CJK 中文字体云原生容器弹性探测（`ReentrantLock` 防虚拟线程 Pinning）及条件装配的 `GotenbergDocumentConverter` 跨格式转换逃生舱”全场景一体化解决方案。

## Language

**WordTemplate**:
开箱即用的 Word (.docx) 模板渲染、多文档拼装、安全保护与自省提取统一操作门面，基于 poi-tl 与 Apache POI 构建，提供强类型数据模型渲染（默认关闭 SpringEL 防注入，支持 `configureBuilder(boolean)` 按需开启）、行/列动态循环与空数据自适应、单元格跨行跨列合并、浮动电子公章与外层 `finally` ThreadLocal 确定性清理、`StitchOptions` 分页拼装、`ReadonlyProtection` 只读编辑锁（`protect/unprotect`）、Agile AES-256 文件打开级加密（`encrypt/decrypt/isEncrypted`）、页眉 VML 对角动态宏水印（`addWatermark`）、`WordMetadata` 结构元数据自省，以及经由 `DocumentConverter` SPI 的 `renderToPdf` 转档桥接。
_Avoid_: DocxTemplate, WordUtil, PoiTlUtil, DocxManager

**ReadonlyProtection**:
基于 OOXML `settings.xml` 编辑限制标记（`w:documentProtection`）的 Word 只读保护语义，对应 `WordTemplate.protect/unprotect/isEnforcedReadonlyProtection/validateProtectionPassword`，用于防止桌面编辑软件误改排版，文件载体仍为明文 ZIP 可直接打开；与文件打开级加密信封（`encrypt/decrypt`）严格正交区分。
_Avoid_: WordFileEncryption, DocxAesLock, ReadOnlyEncryption

**WordMetadata**:
Word (.docx) 文档结构计数、安全保护状态与核心摘要属性自省模型，封装段落数、表格数、内嵌图片数、页眉页脚标志、只读编辑锁标志（`readonlyProtected`）、AES-256 加密信封标志（`encrypted`）及标题/作者/创建时间等核心属性。
_Avoid_: DocxInfo, WordSummary, DocxMetadataDto

**StitchOptions**:
Word 多文档物理拼装控制参数模型，控制子文档拼接边界是否强制插入分页符（`pageBreakBetween`），满足主合同与附件协议从连续拼接向独立起页拼装的灵活切换。
_Avoid_: MergeWordConfig, DocxCombineOptions, AppendOptions

**PdfTemplate**:
开箱即用的 PDF 综合治理与自省抽取统一操作门面，基于 Apache PDFBox 3.x 构建，提供文本与图片/Logo 双模倾斜平铺暗水印（支持多行换行、页面旋转与 CropBox 校准、`skipPages`/`targetPages`）、绝对物理坐标与关键字定位自动盖章（`stampAtKeyword`）、多页跨页骑缝章无损切片、物理不可逆光栅涂黑脱敏（`redact`）、页面合并/拆分/提取/删除/重排/插入/旋转与多图合成 PDF（`imagesToPdf`）、页码压印、AcroForm 填报与不可逆矢量扁平化、256 位 AES 权限锁（随机默认管理密码）及全量操作口令穿透、元数据更新与隐私剥离、高清光栅渲染及 `fromDocx`/`fromHtml` 转档桥接。
_Avoid_: PdfUtil, PdfBoxTemplate, PdfManager, PdfHelper

**DocumentConverter**:
文档跨格式转换统一 SPI 扩展点与逃生舱契约，定义 Word 转 PDF、HTML 转 PDF 的标准化接口。默认提供快速失败实现 `DefaultDocumentConverter`，同时支持通过配置激活开箱即用的 `GotenbergDocumentConverter` 适配器或应用层自定义 Bean 零侵入接管。
_Avoid_: FormatConverter, DocConverter, OfficeConverter

**GotenbergDocumentConverter**:
基于 Spring 6 `RestClient` 的 Gotenberg HTTP API 远程文档跨格式转换适配器，受 `@ConditionalOnClass(RestClient.class)` 与配置属性开关双条件守卫隔离装配，内置输入流容量熔断、连接/读取 `Duration` 超时防御及 `CONVERSION_FAILED` 因果异常链转译。
_Avoid_: GotenbergClient, RemotePdfConverter, LibreOfficeRestAdapter

**WatermarkOptions**:
PDF 与 Word 共用的防泄密水印领域参数模型，支持文本平铺矩阵水印、图片/Logo 矩阵平铺水印双模以及 Word 页眉 VML 对角文字水印，封装动态宏模板（支持 `#{user.name}`、`#{user.code}`、`#{tenantId}`、`#{traceId}`、`#{now(...)}`）、旋转角度、字号、颜色、不透明度、覆盖图层、网格间距及 `skipPages`/`targetPages`/`password` 控制。
_Avoid_: WatermarkConfig, MarkOptions, WatermarkSetting

**CrossPageSealOptions**:
多页合同跨页骑缝章领域参数模型，封装印章图片数据源、骑缝章压印边缘、印章物理目标尺寸、跳过封面/封底、起止页码范围、边缘内缩边距及打开密码。
_Avoid_: SealSplitOptions, QiFengZhangOptions, EdgeStampConfig

**KeywordStampOptions**:
PDF 基于关键字文本自动定位压印电子公章领域参数模型，封装锚点关键字、印章图片源、目标宽高、相对关键字左下角坐标的 X/Y 偏移量、命中策略（`KeywordStampOccurrence`：`FIRST`/`LAST`/`ALL`）、目标页码集合、`failIfNotFound` 快速失败保护及打开密码。
_Avoid_: TextAnchorStampConfig, AutoSealOptions, KeywordSealRequest

**RedactOptions**:
PDF 敏感内容物理不可逆涂黑脱敏（Redaction）领域参数模型，支持按关键字清单与物理坐标矩形（`RedactArea`）双轨定位，仅对命中页按指定 DPI 执行光栅化遮罩重封装（未命中页保留 100% 原生矢量），彻底抹除底层可选/可复制文本字节与矢量路径。
_Avoid_: MaskOptions, PdfEraseConfig, DesensitizePdfOptions

**PdfMetadataUpdate**:
PDF 文档核心摘要信息增量更新领域参数模型，仅覆写非 `null` 字段（置空字符串可用于单项擦除），与 `stripMetadata` 共同形成与 `inspectMetadata` 对称的元数据治理闭环。
_Avoid_: PdfInfoPatch, MetadataModifyDto, PdfHeaderUpdate

**ImageToPdfOptions**:
多图合并编排导出 PDF 领域参数模型，支持标准纸张尺寸（默认 A4）等比居中适配与按每张输入图片原始像素尺寸精确成页（`fitToImage`）双模式及四向页边距控制。
_Avoid_: Img2PdfConfig, PicturePdfOptions, PhotoMergeOptions

**DocumentFontResolver**:
CJK 中文字体三级弹性探测与加载解析器，基于 `ReentrantLock` 消除虚拟线程 Carrier Pinning，负责自适应定位可用中文字体（外部配置路径 -> 宿主机系统字体目录扫描 -> 缺失快速失败输出 Dockerfile 指引），杜绝非 ASCII 中文在字体缺失时发生字符损坏。
_Avoid_: FontManager, FontHelper, CjkFontLoader

**FormOptions**:
AcroForm 动态表单填报领域参数模型，封装表单键值对绑定规则、只读矢量扁平化锁定（Flattening）与打开密码。
_Avoid_: FormConfig, AcroFormOptions, FormSetting

**PdfFormFieldDescriptor**:
AcroForm 动态表单字段 8 维元数据描述模型，封装字段短名、全限定路径名、字段类别枚举（`FieldCategory`）、当前值、默认值、只读/必填/多选状态。
_Avoid_: FormFieldDto, AcroFormFieldInfo, FormFieldVo

**PdfMetadata**:
PDF 文档基础属性与安全元数据模型，封装总页数、PDF 规范版本、文档信息字典、加密状态、用户权限掩码及 AcroForm/XFA 探测标志。
_Avoid_: PdfInfo, DocumentMetadataDto, PdfSummary

**PdfRenderOptions**:
PDF 页面转图片的高清渲染控制参数模型，封装 DPI 分辨率、图像格式（PNG/JPEG）、渲染页数安全上限及打开密码。
_Avoid_: RenderConfig, PageToImageOptions, PdfImageOptions

**WordWebUtil**:
Word 浏览器端流式安全输出工具，封装基于 `HttpServletResponse` 的流式下载、核心 `FileUtil` 文件名安全清洗、RFC 6266 / RFC 5987 中文文件名编码及防缓存标头注入。
_Avoid_: WordDownloadUtil, DocxResponseUtil, WordExportUtil

**PdfWebUtil**:
PDF 与光栅渲染图像浏览器端流式安全输出工具，同时支持 PDF 流式预览/下载（`preview`/`download`/`writeToResponse`）及单页渲染图像流式预览/下载（`previewImage`/`downloadImage`/`writeImageToResponse`，自动识别 PNG/JPEG 等 MIME）。
_Avoid_: PdfDownloadUtil, PdfResponseUtil, PdfExportUtil

**FloatingPicture**:
OpenXML `CTAnchor` 浮动图片与电子印章领域渲染模型，配合 `FloatingPictureRenderPolicy` 及 `WordTemplate` 外层 `finally` 清理机制实现非挤压浮动盖章与零线程上下文泄漏。
_Avoid_: InlinePicture, StampImageModel, WordSealVo, FloatingImage

**SecurityOptions**:
PDF 256 位 AES 权限加密安全锁领域模型，支持用户打开密码与管理员密码（未传入时底层以 `SecureRandom` 生成高强度随机口令），精细控制打印、复制、修改与表单填报权限。
_Avoid_: EncryptOptions, PdfPasswordConfig, SecuritySettings, PermissionOptions

**PageNumberOptions**:
PDF 动态页码排版领域模型，支持 6 种对齐方位（`PageNumberPosition`）、`{page}` / `{total}` 模板、跳过封面页数与有效总页数重算（`recalculateTotal`）及打开密码。
_Avoid_: PageNumberConfig, HeaderFooterOptions, PaginationSetting, PageStampOptions

**ImageStampOptions**:
PDF 绝对物理坐标印章压印领域模型，封装印章图片输入源、目标页码索引（支持负数倒数索引）、物理 X/Y 坐标、宽高尺寸、图层与打开密码。
_Avoid_: StampPositionConfig, AbsoluteSealOptions, CoordinateStampVo, FixedStampModel

**PageImageConsumer**:
PDF 页面高清光栅化渲染流式消费函数式接口，在逐页回调后由框架确定性调用 `image.flush()` 释放离屏显存，杜绝大文档 OOM。
_Avoid_: ImageCallback, PageConsumer, PdfImageHandler, FrameConsumer

**FontResolutionTier**:
CJK 中文字体解析来源阶梯枚举（实现 `IBaseEnum<String>`），标识生效层级：`CONFIG`（外部显式配置路径）与 `SYSTEM`（宿主机操作系统字体目录扫描）。
_Avoid_: FontSourceLevel, FontTierEnum, ResolutionLevel, FontTier
