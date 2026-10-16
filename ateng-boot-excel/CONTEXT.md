# Excel Context

`ateng-boot-excel` 是面向企业级应用的高性能、轻量级流式 Excel Starter，基于 Apache Fesod（ASF 孵化项目）构建，提供覆盖“流式低损耗读写、内存真明文动态脱敏、JSR-303 行级校验与红字批注错误回写、防呆模板智能生成、双向枚举与字典 SPI 转义、复杂报表模板填充、相邻行动态合并与多 Sheet 链式编排”的全场景一体化解决方案。

## Language

**ExcelUtil**:
开箱即用的静态统一门面，封装基于 Apache Fesod 的极简操作接口，支持同步集合流式读取、行级校验监听导入、普通列表/VO 导出、无实体动态导出（`exportDynamic`）、复杂报表模板填充（`fill`）、防呆模板下载（`exportTemplate`）与错误标红批注反馈文件生成（`exportErrorFeedback`）。
_Avoid_: FesodUtil, EasyExcelUtil, ExcelHelper, OfficeManager

**ExcelBuilder**:
Fluent 链式多 Sheet 流式构建器，允许通过极简链式 API 在单次写入会话中为同一个工作簿编排多个工作表（Sheet），独立指定不同的数据源、表头模型、表头样式及写入拦截器，并安全收敛底层流生命周期。
_Avoid_: MultiSheetHelper, WorkbookBuilder, ExcelSession

**SensitiveCellWriteHandler**:
导出动态脱敏拦截器，配合属性级注解 `@ExcelSensitive` 在单元格数据转换后（`afterCellDataConverted`）通过 `DesensitizeUtil` 执行动态掩码。严格遵守 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 确立的“内存真明文”不变量，仅改写写出至 Excel 的视图字面量，内存中对象字段值始终保持原始真实明文。
_Avoid_: DesensitizeInterceptor, MaskCellHandler, ExcelMaskProcessor

**BatchValidationListener**:
行级 JSR-303 数据校验读取监听器，继承 Fesod 原生 `AnalysisEventListener`，在流式逐行解析时结合 Spring `Validator` 进行行级约束检验，精准计算 Excel 物理行号（自动补偿表头偏移），支持快速失败（`ExcelValidationException`）与全量错误聚合（`ExcelReadResult`）双重模式。
_Avoid_: ValidateListener, ExcelImportListener, ValidationProcessor

**ExcelFeedbackUtil**:
校验失败单元格标红与批注回写工具，利用 POI 样式引擎将校验失败的单元格背景填充为柔和浅红（`IndexedColors.ROSE`），并挂载包含具体失败原因的悬浮批注（`Comment`），输出业务人员可直接修正并重新上传的“错题集”反馈文件。
_Avoid_: ErrorCellMarker, AnnotationWriter, ErrorFeedbackWriter

**DropdownSheetWriteHandler**:
防呆模板下拉约束拦截器，配合属性级注解 `@ExcelDropdown` 在工作表创建完毕后（`afterSheetCreate`）自动为对应列注册 Excel 原生 `DataValidation` 下拉选单，支持固定字符数组或动态 `DropdownProvider` SPI。
_Avoid_: DropdownHandler, SelectValidationHandler, ComboBoxSheetHandler

**PromptHeadWriteHandler**:
防呆模板表头批注指南拦截器，配合属性级注解 `@ExcelPrompt` 在表头单元格渲染后（`afterCellDispose`）挂载包含填写规范、示例或格式要求的悬浮批注，降低用户手工填报错误率。
_Avoid_: HeadPromptHandler, HeaderCommentHandler, TipHeadWriter

**ExcelEnumConverter**:
双向枚举转义转换器，配合属性级/类型级注解 `@ExcelEnum`，在导出时自动将枚举对象或编码（`codeField`）翻译为易读的文本描述（`textField`），在导入时自动根据中文描述还原为枚举实例或编码，内置并发安全的双向映射缓存。
_Avoid_: EnumConverter, EnumCodeConvert, FastEnumMapping

**ExcelDictConverter**:
业务字典双向转义转换器，配合属性级注解 `@ExcelDict` 与 `DictProvider` SPI，支持在 Spring 容器环境中解耦调用底层字典服务进行类型编码与展示标签的双向映射。
_Avoid_: DictConverter, DictionaryTranslator, DictBridge

**MergeCellWriteHandler**:
相邻行单元格纵向动态合并拦截器，配合属性级注解 `@ExcelMerge`，在工作表写入完毕后（`afterSheetDispose`）自动识别指定列在连续行中的相同数值并实施纵向合并，支持父子列级联约束防误合并。
_Avoid_: CellMergeHandler, AutoMergeProcessor, RowMergeInterceptor
