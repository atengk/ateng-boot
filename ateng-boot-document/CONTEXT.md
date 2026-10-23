# Document Context

`ateng-boot-document` 是面向企业级应用的高性能、轻量级办公文档处理 Starter，深度整合 Apache POI、poi-tl 与 Apache PDFBox 3.x，提供覆盖“Word 合同模板变量替换、动态表格扩展与多文档拼装，PDF 平铺防泄密暗水印、多页编排、AcroForm 动态表单填报与扁平化防篡改、权限安全加密，以及 CJK 中文字体云原生容器弹性自适应探测”的全场景一体化解决方案。

## Language

**WordTemplate**:
开箱即用的 Word (.docx) 模板渲染与多文档拼装统一操作门面，基于 poi-tl 构建，提供强类型数据模型渲染、复杂嵌套表格循环行追加与单元格合并、多媒体签名与电子公章浮动嵌入、条件区块显示/隐藏及多文档无缝拼接。
_Avoid_: DocxTemplate, WordUtil, PoiTlUtil, DocxManager

**PdfTemplate**:
开箱即用的 PDF 综合治理统一操作门面，基于 Apache PDFBox 3.x 构建，提供对称完备的平铺防泄密暗水印压印、多 PDF 物理合并与拆分、页码及标头压印、AcroForm 动态表单域填报与扁平化（Flattening）、文档打开与权限密码安全加密。
_Avoid_: PdfUtil, PdfBoxTemplate, PdfManager, PdfHelper

**DocumentConverter**:
文档跨格式转换统一 SPI 扩展点与逃生舱契约，定义 Word 转 PDF、HTML 转 PDF 的标准化接口。底座默认提供轻量不可用快速失败实现，由应用层按需接入外部渲染器（如 Gotenberg / LibreOffice）实现零侵入扩展。
_Avoid_: FormatConverter, DocConverter, OfficeConverter

**WatermarkOptions**:
PDF 防泄密水印领域参数模型，支持文本平铺矩阵水印与图片/印章水印双模，封装水印内容（支持当前操作人与时间宏变量）、平铺旋转角度、字体尺寸、颜色、不透明度（Opacity）、前后图层模式（Overlay/Underlay）与行列表格间距。
_Avoid_: WatermarkConfig, MarkOptions, WatermarkSetting

**DocumentFontResolver**:
CJK 中文字体三级弹性探测与加载解析器，负责在云原生容器与异构操作系统环境中自适应定位可用中文字体（外部配置路径 -> 宿主机系统字体目录枚举 -> 缺失快速失败防卫），杜绝 PDFBox 渲染中文乱码豆腐块或抛出无字形异常。
_Avoid_: FontManager, FontHelper, CjkFontLoader

**FormOptions**:
AcroForm 动态表单填报领域参数模型，封装表单键值对绑定规则、只读扁平化锁定（Flattening）、富文本与图片字段替换策略，保障合同签署与填报后数据不被篡改。
_Avoid_: FormConfig, AcroFormOptions, FormSetting

**WordWebUtil**:
Word 浏览器端流式安全输出与快速响应工具，封装基于 `HttpServletResponse` 的流式下载、RFC 6266 标准中文文件名 URL 编码转义及安全标头注入，避免大文件内存堆积。
_Avoid_: WordDownloadUtil, DocxResponseUtil, WordExportUtil

**PdfWebUtil**:
PDF 浏览器端流式安全输出与预览响应工具，支持直接向 `HttpServletResponse` 写入带水印或填报后的 PDF 二进制流，支持浏览器内联预览（`inline`）与附件下载（`attachment`）双模切换，标配 RFC 6266 防乱码标头。
_Avoid_: PdfDownloadUtil, PdfResponseUtil, PdfExportUtil
