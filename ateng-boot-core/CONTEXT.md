# Core Context

`ateng-boot-core` 是整个框架的通用能力底座，定义与任何具体外部中间件无关的标准化契约、异常、工具与 SPI。

## Language

**Result**:
框架统一的 API 响应载体，承载业务状态码、提示文本、业务数据载荷、时间戳、成功标记与链路追踪标识。
_Avoid_: Response, ApiResponse, R, AjaxResult, ApiResult

**ResultCode**:
表征业务处理状态的规范协议，包含整数业务码与状态描述文本。
_Avoid_: ErrorCode, StatusCode, StatusEnum

**BusinessException**:
表征业务逻辑中断的受控运行时异常，携带对应的 ResultCode 供全局拦截转译为标准响应。
_Avoid_: ServiceException, AppException, BizException

**BusinessAssert**:
在条件不成立时直接抛出指定 BusinessException 的静态断言工具。
_Avoid_: Assert, ValidateUtil, CheckUtil

**CurrentUserProvider**:
供外部鉴权体系接入以提供当前操作人标识（用户 ID 或账号）的函数式 SPI 接口。
_Avoid_: PrincipalProvider, SecurityContext, UserSupplier

**UserContextHolder**:
基于 ThreadLocal 维护当前操作人身份的内存上下文持有者，作为未挂载外部鉴权时的默认回退机制。
_Avoid_: SessionContext, LocalUser, UserThreadLocal

**AsyncContext**:
抓取当前线程链路追踪标识与用户身份快照并在异步线程中恢复的上下文装饰器。
_Avoid_: ThreadLocalCopier, ContextPropagator

**ContextCopyingTaskDecorator**:
基于 Spring TaskDecorator 契约实现的上下文感知任务装饰器，内部直接委托 AsyncContext.wrap(runnable) 在异步任务执行前后抓取、还原并清理 MDC 链路追踪标识与用户身份上下文。
_Avoid_: AsyncTaskDecorator, ThreadPoolDecorator, MDCDecorator

**PageResult**:
统一与底层持久层解耦的分页响应结果载体，仅包含分页核心要素与数据集，杜绝 ORM 内部属性泄露。
_Avoid_: PageVO, PageDTO, PageData, TableDataInfo, PageResponse

**PageQuery**:
标准化分页查询参数基类，封装当前页码、单页容量与基础查询排序要素。
_Avoid_: PageParam, PageReq, PageRequest

**DesensitizeUtil**:
全场景敏感数据动态掩码工具类，提供中文姓名、手机号、固话、身份证、邮箱、银行卡、密码、国内地址、车牌号、IPv4/IPv6、统一社会信用代码及便捷首尾保留掩码算法。
_Avoid_: MaskUtil, DataMaskUtil, MaskingUtil

**Snowflake**:
纯 JDK 原生 Twitter 雪花算法发号引擎，集成时钟回拨自旋容错、自适应网卡/IP 节点推导与 41-bit 自定义纪元时间戳。
_Avoid_: SnowflakeIdWorker, SequenceGenerator, SnowflakeGenerator

**IdUtil**:
全局分布式与高性能唯一 ID 生成门面工具类，提供开箱即用的单例雪花 ID、极速无横线 UUID 与轻量安全 NanoId。
_Avoid_: IdHelper, UuidUtil, IdentifierUtil, KeyUtil

**CryptoUtil**:
纯 JDK 原生对称加解密工具类，默认支持 AES/CBC/PKCS5Padding 与 AES/GCM/NoPadding，无 Bouncy Castle 重型依赖。
_Avoid_: AesUtil, EncryptionUtil, EncryptUtil

**TreeUtil**:
通用泛型树形结构构建工具，依托纯函数式接口与哈希表两遍扫描算法，以 O(N) 线性时间复杂度实现扁平列表到嵌套树的高性能组装。
_Avoid_: TreeBuilder, NodeUtil, MenuTreeUtil

**JsonUtil**:
纯 JDK 与 Jackson 原生安全封装的静态门面工具类，内置东八区时区、JSR-310 日期时间模块与未知属性容错，彻底杜绝 @class 漏洞。
_Avoid_: JacksonUtil, JSON, FastJsonUtil, JsonHelper

**TenantContextHolder**:
基于 ThreadLocal 与栈结构维护当前线程租户标识及忽略过滤深度的持有者，提供编程式闭包与跨线程隔离能力。
_Avoid_: TenantHelper, CurrentTenant, TenantManager

**CurrentTenantProvider**:
供外部多租户接入以提供当前租户上下文标识（租户 ID）的函数式 SPI 接口。
_Avoid_: TenantSupplier, TenantResolver

**SensitiveType**:
敏感数据脱敏类型枚举，统一内聚映射 DesensitizeUtil 核心脱敏算法，供 Jackson 序列化与 Excel 导出跨模块复用。
_Avoid_: MaskType, DesensitizeType

**FieldSensitive**:
视图层字段动态脱敏注解，基于 Jackson 序列化机制在出界序列化时动态执行掩码，恪守内存真明文原则。
_Avoid_: Sensitive, MaskField, JsonMask

**DatePattern**:
标准化日期时间格式与预编译 DateTimeFormatter 常量统管类，终结各模块散落硬编码字符串。
_Avoid_: DateFormatConstants, TimePattern, DateConsts

**Pair**:
基于 JDK 17 Record 实现的轻量不可变二元组数据结构，承载两项关联数据的键值对流转。
_Avoid_: Tuple2, KeyValue, Entry

**Triple**:
基于 JDK 17 Record 实现的轻量不可变三元组数据结构，承载三项关联数据的强类型流转。
_Avoid_: Tuple3, Trio

**SpringUtil**:
基于 ApplicationContextAware 与 BeanFactoryPostProcessor 实现的 Spring 容器上下文全局静态门面，提供非受管对象编程式获取 Bean、环境配置与事件发布能力。
_Avoid_: SpringContextUtil, SpringContextHolder, BeanFactoryUtil

**LambdaUtil**:
基于纯 JDK 17 SerializedLambda 反射机制实现的强类型方法引用解析工具，将 Getter 方法引用安全推导为实体属性名。
_Avoid_: MethodRefUtil, PropertyUtil, FieldReflectUtil

**CollectionUtil**:
纯原生集合防御与切片增强工具类，提供空安全判断与大容量列表等额切片（partition）。
_Avoid_: CollUtil, ListUtil, CollectionsHelper

**MoreCollectors**:
防御性 Java 8+ Stream 收集器扩展类，提供杜绝 NPE 与重复键覆盖的纯净 toSafeMap 实现。
_Avoid_: StreamUtil, CustomCollectors, SafeCollector

**BeanUtil**:
基于 Spring 原生 BeanUtils 增强的轻量级对象拷贝门面，支持自动实例化反射拷贝、批量列表流式映射与增量非空覆盖。
_Avoid_: ObjectCopier, BeanMapper, ModelMapper

**RetryUtil**:
基于纯函数式接口与指数退避算法的轻量级重试与容错执行引擎，为网络通信与并发冲突提供无侵入高可用守护。
_Avoid_: RetryHelper, RetryTemplate, BackoffUtil

**FileUtil**:
纯 JDK 原生轻量文件与路径安全工具类，提供路径遍历穿透（Path Traversal）安全清洗、扩展名截取与人类可读文件容量格式化。
_Avoid_: FileHelper, IoHelper, PathUtil



