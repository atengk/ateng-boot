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
导出动态脱敏拦截器，配合属性级注解 `@ExcelSensitive` 或 `ateng-boot-core` 通用 `@FieldSensitive` 在单元格数据转换后（`afterCellDataConverted`）通过 `DesensitizeUtil` 执行动态掩码，并内置并发安全的反射规则元数据缓存。严格遵守 [ADR-0012](../docs/adr/0012-lifecycle-separated-data-security.md) 确立的“内存真明文”不变量，仅改写写出至 Excel 的视图字面量，内存中对象字段值始终保持原始真实明文。
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

**ExcelTheme**:
企业级 Excel 预设多模版主题枚举，封装表头背景色、表头字体颜色、正文字体及斑马纹浅底色等元数据配置。默认推荐为极简白（`MINIMAL_CLEAN`，表头无底色加粗，正文纯白，保留细灰实线网格边框）；并开箱即用支持石墨灰（`CLASSIC_GREY`，厚重工业石墨灰表头 `#B0B7C0` 搭配黑色加粗字体，正文浅灰斑马纹 `#DEE2E6` 规律交替）、商务蓝（`BUSINESS_BLUE`）、清新绿（`FRESH_GREEN`）与暖阳橙（`WARM_ORANGE`）等多模版样式。
_Avoid_: StyleTheme, ColorScheme, ExcelSkin

**SmartAutoColumnWidthWriteHandler**:
智能自适应列宽拦截器，按中文字符（2.0 字符宽）与英文字符（1.0 字符宽）精准加权计算列宽并预留安全内边距，提供 `minColumnWidth` 与 `maxColumnWidth` 上下限阈值保护，彻底避免短表头截断与长数字 `###` 溢出。
_Avoid_: AutoWidthHandler, ColumnResizeListener, BestFitWidthStrategy

**ExcelThemeWriteHandler**:
企业级主题与行级斑马纹样式写入拦截器，基于 Apache Fesod 原生 `HorizontalCellStyleStrategy` 构建，统一挂载现代字体、细灰色网格边框与居中对齐，并支持正文奇偶行浅底纹交替渲染。
_Avoid_: ThemeStyleStrategy, ZebraWriteHandler, SheetColorizer

**AsyncExportTaskService**:
异步海量导出任务中心服务 SPI，定义大报表后台受管异步排队、生成、轮询查询与过期清理契约，默认提供并发内存实现 `InMemoryAsyncExportTaskService`（支持四维上下文 `AsyncContext` 全链路透传、有界队列快速失败防爆与单线程定时文件回收），并允许上层微服务无缝桥接分布式 Redis 或文件中心。
_Avoid_: ExportTaskManager, AsyncTaskQueue, BackgroundExcelWorker

**FailSplitFeedback**:
差错数据隔离提取与精简纠错导出能力，在 JSR-303 批量校验导入时，将校验失败的记录自动剥离并生成专属纠错 Excel（包含聚合的错误原因汇总列与出错单元格粉红高亮），实现“成功数据自动入库，失败数据仅下载差错子集修正重传”。
_Avoid_: ErrorSplitter, FailedRowDumper, ErrorSubsetExporter

**SmartImageConverter**:
多媒体图片智能自适应转换器，配合属性级注解 `@ExcelImage`，零配置自动识别并加载网络 HTTP(S) 图片 URL、Base64 编码、本地绝对文件路径及原始字节流，并与 `ImageRowHeightWriteHandler` 联动自动撑高所在行，杜绝 Fesod 图片压扁变形问题。
_Avoid_: UrlPictureConverter, UniversalImageLoader, PhotoCellAdapter

**WatermarkSheetWriteHandler**:
工作表透明防拍照暗水印写入拦截器，在工作表创建时通过 Java 2D 动态生成半透明旋转工号防伪切片并注入为工作表底层背景（Background Picture），在屏幕浏览和纸质打印时呈现全屏 45° 倾斜暗水印，且 100% 不妨碍单元格点击与公式输入；协同提供只读防篡改密码锁定。
_Avoid_: BackgroundWatermarkHelper, ExcelWatermarkFilter, AntiphotoStamp

**StreamingPageExportTemplate**:
流式大数据分页导出执行模板，解耦内存爆炸风险，按步长分页拉取数据并实时刷入 Fesod 工作簿流，在行数达到单工作表上限时自动无缝跨 Sheet 轮转。
_Avoid_: CursorExcelExporter, ChunkExportHelper, BigDataExporter

**RedisAsyncExportTaskService**:
基于分布式 Redis 的集中式异步导出任务中心实现，支持任务元数据管理、状态机流转与执行进度跨多实例协同轮询，具备 TTL 自动延期与回收。
_Avoid_: RedisTaskManager, DistributedExportWorker, ClusterTaskCenter

**ExportFileStorage**:
异步海量导出文件持久化与分发 SPI 门面，解耦底层具体存储介质，提供文件暂存、流式读取、预签名直链生成与生命周期清理契约。
_Avoid_: FileRepository, ExcelBlobService, DiskStorageDriver

**ExcelHeaderValidator**:
导入表头契约前置校验监听拦截器，在流式解析初始阶段实施严格（STRICT）或宽松（LOOSE）的列名与序列一致性比对，首行失配即刻 Fail-Fast 熔断阻断。
_Avoid_: HeaderMatcher, TitleChecker, HeadSchemaValidator

**FormulaProtectionCellWriteHandler**:
Excel 公式注入（CSV/Formula Injection）安全防御拦截器，配合 `@ExcelFormulaAllowed` 注解，写出时自动对以 `=+-@` 起始的文本字面量增加防注入前缀单引号并支持合法公式豁免。
_Avoid_: CsvInjectionFilter, FormulaSanitizer, ExcelEscapeHandler, FormulaInjectionWriteHandler

**ColumnLockSheetWriteHandler**:
列级可编辑性锁定与部分只读细粒度控制拦截器，配合属性级注解 `@ExcelColumnLock`，在工作表密码锁定生效的前提下精准放行指定列的单元格编辑权限。
_Avoid_: CellLockHandler, ColumnProtectWriter, EditableColumnStrategy, ColumnLockWriteHandler

**I18nHeadWriteHandler**:
国际化多语言表头动态转译拦截器，自动识别 `{i18n:key}` 格式的表头声明，联动 Spring `MessageSource` 与当前请求区域（Locale）动态完成表头本地化翻译。
_Avoid_: LocaleHeaderWriter, MultiLangHeaderHandler, TranslationInterceptor

**ExcelReaderBuilder**:
Fluent 链式多 Sheet 流式读取构建器，允许在单次 I/O 流生命周期内为同一个工作簿编排多个不同工作表（Sheet）的读取契约，支持按索引/表名寻址、异构数据模型映射、分批回调与全量聚合双模消费，实现读写对称完备性。
_Avoid_: MultiSheetReader, SheetReaderHelper, ExcelStreamReader, ExcelReadSession

**ColumnProjection**:
强类型 VO 动态列投影能力，支持通过 `includeColumns` / `excludeColumns` 动态裁切需要输出的属性列，未选中的列物理剔除且保留列自动紧凑左移排版，100% 继承实体注解（脱敏、主题、枚举转义与列宽补偿）。
_Avoid_: ColumnFilter, FieldSelector, DynamicColumnFilter, ColumnPruner

**WorkbookEncryption**:
整工作簿工业级 AES-256 原生强加解密体系，基于 POI 原生 `EncryptionInfo` 与 Agile Encryption 算法实现，提供全文件密码加密封包与透明解密流还原，配合磁盘临时文件防 OOM 缓冲与确定性销毁。
_Avoid_: ExcelCryptor, PoiEncryptor, FilePasswordProtector, SheetEncryption

**ExcelWebUtil**:
Web HTTP 文件导出专职门面工具，封装符合 RFC 5987 / RFC 6266 标准的 UTF-8 中文文件名响应头、标准 XLSX MIME 配置与现代 Spring `ResponseEntity<StreamingResponseBody>` 强类型响应对象封装，并提供自适应客户端中断（`ClientAbortException`）异常拦截与磁盘临时文件确定性安全清理。
_Avoid_: ExcelHttpUtil, WebExportHelper, DownloadUtil, ServletExcelWriter

