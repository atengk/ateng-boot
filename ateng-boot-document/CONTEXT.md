# Document Context

`ateng-boot-document` 是面向企业级应用的高性能、轻量级办公文档处理 Starter，深度整合 Apache POI、poi-tl 与 Apache PDFBox 3.x，提供覆盖“Word 合同模板变量替换、动态表格扩展、图元容错与多文档拼装，PDF 平铺防泄密暗水印、多页编排、多页跨页骑缝章、AcroForm 动态表单填报与矢量扁平化防篡改、权限安全加密、页面原生高清转图片（缩略图），以及 CJK 中文字体云原生容器弹性自适应探测与 XFA 格式快速失败防卫”的全场景一体化解决方案。

## Language

**WordTemplate**:
开箱即用的 Word (.docx) 模板渲染、多文档拼装与自省提取统一操作门面，基于 poi-tl 与 Apache POI 构建，提供强类型数据模型渲染、复杂嵌套表格循环行追加与空数据自适应、单元格跨行跨列合并、多媒体签名与电子公章浮动嵌入（支持缺失图元容错）、条件区块显示/隐藏、多文档无缝拼接，以及模板占位符变量逆向自省与正文纯文本抽取。
_Avoid_: DocxTemplate, WordUtil, PoiTlUtil, DocxManager

**PdfTemplate**:
开箱即用的 PDF 综合治理与自省抽取统一操作门面，基于 Apache PDFBox 3.x 构建，提供对称完备的平铺防泄密暗水印压印、多页跨页骑缝章加盖、多 PDF 物理合并与拆分、页码及标头压印、AcroForm 动态表单域填报（支持 CJK 字体自适应装配防字形崩溃）与不可逆矢量扁平化（Flattening）、文档打开与权限密码安全加密、动态 XFA 表单快速失败防卫，以及表单域元数据巡检自省、文档核心属性探查、正文纯文本抽取与页面原生高清转图片（缩略图，标配防 OOM 安全上限与流式消费）。
_Avoid_: PdfUtil, PdfBoxTemplate, PdfManager, PdfHelper

**DocumentConverter**:
文档跨格式转换统一 SPI 扩展点与逃生舱契约，定义 Word 转 PDF、HTML 转 PDF 的标准化接口。底座默认提供轻量不可用快速失败实现，由应用层按需接入外部渲染器（如 Gotenberg / LibreOffice）实现零侵入扩展。
_Avoid_: FormatConverter, DocConverter, OfficeConverter

**WatermarkOptions**:
PDF 防泄密水印领域参数模型，支持文本平铺矩阵水印与图片/印章水印双模，封装水印内容（支持当前操作人与时间宏变量）、平铺旋转角度、字体尺寸、颜色、不透明度（Opacity）、前后图层模式（Overlay/Underlay）与行列表格间距。
_Avoid_: WatermarkConfig, MarkOptions, WatermarkSetting

**CrossPageSealOptions**:
多页合同跨页骑缝章领域参数模型，封装印章图片数据源、骑缝章压印边缘（默认右侧居中）、印章物理目标尺寸、是否跳过封面/封底、起止页码范围及边缘内缩边距。
_Avoid_: SealSplitOptions, QiFengZhangOptions, EdgeStampConfig

**DocumentFontResolver**:
CJK 中文字体三级弹性探测与加载解析器，负责在云原生容器与异构操作系统环境中自适应定位可用中文字体（外部配置路径 -> 宿主机系统字体目录枚举 -> 缺失快速失败防卫），杜绝 PDFBox 渲染中文乱码豆腐块或抛出无字形异常。
_Avoid_: FontManager, FontHelper, CjkFontLoader

**FormOptions**:
AcroForm 动态表单填报领域参数模型，封装表单键值对绑定规则、只读扁平化锁定（Flattening）、富文本与图片字段替换策略，保障合同签署与填报后数据不被篡改。
_Avoid_: FormConfig, AcroFormOptions, FormSetting

**PdfFormFieldDescriptor**:
AcroForm 动态表单字段元数据描述模型，封装字段标识名（FieldName）、字段全路径名、字段类型（Text/CheckBox/Choice/Button/Signature 等）、当前值与只读锁定状态，用于表单域逆向自省与可视化回显。
_Avoid_: FormFieldDto, AcroFormFieldInfo, FormFieldVo

**PdfMetadata**:
PDF 文档基础属性与安全元数据模型，封装总页数、PDF 规范版本、文档信息字典（标题、作者、主题、创建/修改时间、生成工具）、加密状态及用户权限掩码。
_Avoid_: PdfInfo, DocumentMetadataDto, PdfSummary

**PdfRenderOptions**:
PDF 页面转图片的高清渲染控制参数模型，封装单页渲染索引、DPI 分辨率（默认 150 DPI 高清）、图像格式（PNG/JPEG）、抗锯齿开关与防 OOM 安全上限。
_Avoid_: RenderConfig, PageToImageOptions, PdfImageOptions

**WordWebUtil**:
Word 浏览器端流式安全输出与快速响应工具，封装基于 `HttpServletResponse` 的流式下载、RFC 6266 标准中文文件名 URL 编码转义及安全标头注入，避免大文件内存堆积。
_Avoid_: WordDownloadUtil, DocxResponseUtil, WordExportUtil

**PdfWebUtil**:
PDF 浏览器端流式安全输出与预览响应工具，支持直接向 `HttpServletResponse` 写入带水印或填报后的 PDF 二进制流，支持浏览器内联预览（`inline`）与附件下载（`attachment`）双模切换，标配 RFC 6266 防乱码标头。
_Avoid_: PdfDownloadUtil, PdfResponseUtil, PdfExportUtil
