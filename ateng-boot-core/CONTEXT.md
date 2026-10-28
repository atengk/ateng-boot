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
基于 ThreadLocal 维护当前操作人身份的内存上下文持有者，并支持 CurrentUserProvider 动态回退兜底，与 TenantContextHolder 保持双维镜像对称。
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
基于纯函数式接口、指数退避与 Equal Jitter 平滑随机抖动算法的轻量级重试与容错执行引擎，支持 onRetry 度量监听与 Fallback 降级兜底，为网络通信与并发冲突提供无侵入高可用守护。
_Avoid_: RetryHelper, RetryTemplate, BackoffUtil

**FileUtil**:
纯 JDK 原生轻量文件与路径安全工具类，提供路径遍历穿透（Path Traversal）安全清洗、扩展名截取与人类可读文件容量格式化。
_Avoid_: FileHelper, IoHelper, PathUtil

**AtengAsyncAutoConfiguration**:
位于 core 核心底座的自适应双模受管异步线程执行器自动装配类，根据运行环境线程模型（`@ConditionalOnThreading`）自适应装配主虚拟线程执行器（`SimpleAsyncTaskExecutor`）或平台线程池（`ThreadPoolTaskExecutor`），并统一挂载四维上下文快照装饰器与未捕获异常处理器。
_Avoid_: AsyncConfig, ThreadPoolAutoConfiguration, VirtualThreadAutoConfiguration

**AsyncProperties**:
受管异步执行器根级配置属性类，继承自 AsyncPoolProperties 并作为默认主线程池参数，同时通过 `pools` 字典统管下游多业务独立线程池配置。
_Avoid_: ThreadPoolProperties, AsyncConfigProperties, MultiAsyncProperties

**AsyncPoolProperties**:
单线程池运行时增强配置属性实体，定义核心/最大线程数、队列缓冲、存活时间、优雅停机、并发阈值、执行模式与拒绝策略。
_Avoid_: PoolConfig, ThreadPoolConfig, ExecutorProperties

**DynamicAsyncPoolRegistrar**:
基于 Spring 原生 ImportBeanDefinitionRegistrar 实现的多业务受管线程池动态注册器，解析 `ateng.boot.async.pools` 字典并向容器动态注入各命名的受管执行器与别名。
_Avoid_: DynamicExecutorRegistrar, MultiThreadPoolRegistrar, AsyncBeanRegistrar

**AsyncExecutionMode**:
异步执行器线程运行模式枚举，包含跟随全局环境自适应（AUTO）、强制平台线程池（PLATFORM）与强制虚拟线程执行器（VIRTUAL）。
_Avoid_: ThreadMode, ExecutorMode, ExecutionType

**AsyncRejectedPolicy**:
平台线程池任务饱和拒绝策略枚举，映射 JDK 标准拒绝策略（CALLER_RUNS、ABORT、DISCARD、DISCARD_OLDEST）。
_Avoid_: RejectPolicy, RejectionHandlerType, RejectedStrategy

**CarrierPinning**:
在 JDK 21+ 虚拟线程运行中，因在同步代码块（`synchronized`）内部执行阻塞调用或休眠导致虚拟线程锁定底层操作系统载体线程引发调度饥饿的现象，框架内通过 `ReentrantLock` 彻底杜绝。
_Avoid_: ThreadPinning, WorkerPinning, VTPinning


**NamedThreadFactory**:
纯 JDK 17 原生命名线程工厂标准件，为线程池提供语义化前缀、序号自增、守护线程属性与未捕获异常防护，彻底杜绝线上线程转储中无意义命名。
_Avoid_: CustomThreadFactory, SimpleThreadFactory

**ValidatorUtil**:
纯原生业务数据格式校验与整对象 JSR-303 编程式断言门面，提供手机号、带模11加权校验码的中国身份证、邮箱、IP、车牌与统信代码高精度校验，并支持 POJO 快速失败对象校验与属性校验。
_Avoid_: CheckUtil, ValidateHelper, RegexUtil

**ValidationGroups**:
通用 JSR-303 / Jakarta Validation 校验分组契约标准件，集中收敛 Create、Update、Query、Status 高频 Marker 接口，避免各子系统私建空分组类。
_Avoid_: Groups, ValidateGroup, CheckGroup

**ValidList**:
支持 JSR-303 级联校验的轻量级泛型 List 包装容器，彻底解决 Spring MVC 中 Controller 接收 `@RequestBody @Valid List<DTO>` 时默认忽略列表元素内字段校验的缺陷。
_Avoid_: ValidatedList, ValidationList, DTOList

**TransactionUtil**:
基于 Spring 事务同步器（TransactionSynchronization）实现的事务生命周期全局静态门面，支持在数据库物理事务提交后执行同步/异步任务或发布领域事件，在事务回滚后执行补偿清理逻辑；具备类探测与无事务判定机制，无活跃事务或缺依赖时平滑降级就地同步执行，异步调度自动透传四维上下文快照（MDC、User、TenantId、IgnoreTenant）并隔离次生异常。
_Avoid_: TxUtil, TransactionHelper, TxHelper, TransactionalUtil

**OperateTypeEnum**:
通用操作审计动作类型枚举，集中收敛涵盖 11 种通用操作动作类型（OTHER、INSERT、UPDATE、DELETE、QUERY、EXPORT、IMPORT、LOGIN、LOGOUT、GRANT、CLEAN），为全反应堆跨层审计流转提供强类型契约与多源安全解析能力。
_Avoid_: AuditActionEnum, LogTypeEnum, ActionType, OperationType, BusinessType

**OperateLogModel**:
结构化操作审计日志元数据模型 POJO，承载跨层（Web 切面、事件总线、持久层落库）流转的 18 项全要素审计元数据契约，包含全链路追踪、租户、操作人、模块端点、出入参、状态耗时、扩展字典与时间戳。
_Avoid_: AuditLog, SysLog, OperationLogVO, AuditRecord, OperationAudit, AccessLog, SystemLog, AuditTrail


**DateTimeUtil**:
纯 JDK 17 原生日期时间互转与跨度计算静态门面，提供 LocalDateTime、LocalDate、Date 与毫秒时间戳多向无损互转，绑定系统时区，支持天首天尾起止与任意 ChronoUnit / Duration / Period 时间跨度计算。
_Avoid_: DateUtil, TimeUtil, JodaTimeUtil

**RandomUtil**:
纯 JDK 17 原生高性能无锁安全随机工具类，基于 ThreadLocalRandom 提供定长纯数字验证码、数字字母混合随机串、闭区间随机数与列表安全抽样洗牌。
_Avoid_: RandomHelper, SecureRandomUtil, MathRandom

**MessageHeaderConstants**:
收敛跨中间件（RabbitMQ、Kafka、RocketMQ、HTTP）的出入站消息头/用户属性标准键名常量件，统一定义全链路追踪标识（`X-Trace-Id`）、租户标识（`X-Tenant-Id`）、操作人标识（`X-User-Id`）与发送时间戳等核心元数据键名，杜绝各中间件子模块自立门户与命名漂移。
_Avoid_: MqHeaders, MqConstants, HeaderConsts, MessageHeaders

**MessageContextPropagator**:
跨进程与异步消息边界的四维上下文传播纯原生工具类，提供出站四维上下文快照抓取与统一注入（Headers/UserProperties）、入站消息上下文自动提取还原以及消费完成后保证确定性安全清理的生命周期防卫。
_Avoid_: MqContextHelper, MessageTraceUtil, MqTraceFilter, ContextExtractor

**FatalExceptionClassifier**:
面向全反应堆异步消息消费的不可自愈“毒丸”异常分类与判定器，集中收敛反序列化异常、JSR-303 参数校验异常及显式致命逻辑错误，统一供各消息队列消费端拦截器判定并快速失败转入死信队列或丢弃，杜绝无脑无限重试。
_Avoid_: MqErrorClassifier, PoisonPillHandler, FatalExceptionChecker, ErrorJudge

**MessageDeliveryException**:
表征消息出站投递或发送阶段物理中断的受控业务异常，继承自 `BusinessException`，完整封装原始底层驱动异常（cause）与目标 Topic、业务主键及追踪上下文，实现上层调用与底层驱动解耦。
_Avoid_: MqSendException, MqException, ProducerException, DeliveryException

**ThreadPoolStatus**:
受管线程池运行时核心度量指标快照不可变 Record，精确表征核心/最大线程数、当前存活数、活跃执行数、排队积压数、剩余队列容量、已完成任务数、是否饱和标记以及虚拟线程自适应参数。
_Avoid_: PoolMetrics, ThreadPoolInfo, PoolStat

**AsyncUtil**:
受管异步执行器全景治理与函数式异步任务执行门面，提供多业务线程池动态参数调优（热调整）、运行监控指标提取、全链路上下文安全传播异步执行及任务批量并行协调（allMatches / allOf）。
_Avoid_: AsyncHelper, ThreadPoolHelper, TaskExecutorUtil

**SnowflakeIdInfo**:
雪花算法 64 位长整型 ID 逆向反解结构化不可变信息载体 Record，提取包含发号毫秒时间戳、Instant、LocalDateTime、数据中心 ID、工作节点 ID、毫秒内自增序列号与所采用的基准纪元。
_Avoid_: SnowflakeParsed, SnowflakeMeta, IdParsedInfo

**Base62**:
纯原生零依赖无锁 Base62 双向短码编解码门面，支持 64 位长整型数值与 10~11 位紧凑 URL 安全短字符串的双向无损互转，内置完整负数与边界值溢出防卫。
_Avoid_: ShortIdUtil, Base62Codec, NumberCompressor

**SlidingWindowCounter**:
基于环形时间切片（Ring Slices）与原子累加器实现的进程内高性能单机内存滑动窗口计数器，无需外部中间件即可实现精准平滑限流、防刷与频次统计。
_Avoid_: WindowLimiter, SlidingCounter, RateLimiterUtil

**StopWatch**:
纳秒级高精度多分段任务耗时秒表与函数剖析门面，支持分段任务阶梯耗时统计、格式化可视化报告输出与函数闭包执行耗时精准测算。
_Avoid_: TimeWatcher, CostTimer, ProfilerUtil

**NetUtil**:
网络与内网通信地址识别通用工具类，提供多物理网卡内网非回环 IPv4 智能探测、本地空闲端口安全校验与远程服务端口连通性探针。
_Avoid_: NetworkUtil, IpUtil, PortUtil

**ConvertUtil**:
纯原生强类型安全类型转换门面工具类，提供任意基础类型（Long、Integer、Boolean、BigDecimal、Double）与集合的安全强转，提供保底默认值防御，彻底杜绝 NPE 与 ClassCastException。
_Avoid_: TypeConverter, ConvertHelper, CastUtil

**ExceptionUtil**:
异常分析与堆栈序列化通用工具类，提供多层嵌套异常链最深层根本原因（Root Cause）回溯、循环因果检测与带长度截断防御的安全堆栈字符串序列化。
_Avoid_: ExceptionHelper, ThrowableUtil, StackTraceUtil

**HexUtil**:
基于 JDK 17 原生 HexFormat 的高性能纯原生十六进制编解码工具类，提供字节数组与 16 进制字符串双向转换，支持大小写指定与奇偶长度校验。
_Avoid_: HexCodec, HexHelper, ByteHexUtil

**StringUtil**:
纯原生高性能字符串通用算法门面，提供下划线与驼峰命名互转、文本安全截断、敏感子串包含探测与首尾掩码。
_Avoid_: StrUtil, StringHelper, StringUtils

**SpelUtil**:
基于 Spring 表达式引擎与 AST 语法树有界缓存构建的静态门面，提供线程安全、方法入参自适应绑定与防注入安全求值。
_Avoid_: SpelHelper, ExpressionUtil, SpelParser

**ConcurrentLruCache**:
纯 JDK 17 原生零依赖的线程安全有界 LRU 缓存容器，杜绝无界内存泄露风险。
_Avoid_: LruCache, CacheUtil, SimpleLruMap

**ResultCodeUtil**:
全局状态码号段分配校验与元数据注册门面，规范微服务与各 Starter 的错误码分段契约。
_Avoid_: ErrorCodeUtil, ResultCodeHelper, StatusCodeManager

**ResultCodeSegment**:
框架标准业务状态码号段枚举与划分规范，定义系统、鉴权、文件、通信与持久层的号段区间契约。
_Avoid_: ErrorCodeRange, CodeSegment, StatusRange

**Pipeline**:
纯 JDK 17 原生无状态强类型管道与责任链编排原语，支持 Fluent 阶段式链式装配、优雅短路与强类型上下文安全流转。
_Avoid_: ChainExecutor, ResponsibilityChain, HandlerChain

**HmacUtil**:
纯 JDK 原生安全散列消息认证码（HMAC）工具类，提供 HMAC-SHA256、HMAC-SHA1 密钥加签与恒定时间安全比对防侧信道攻击。
_Avoid_: HmacHelper, SignUtil, HmacSignature

**ChecksumUtil**:
纯原生数据传输与硬件报文完整性校验和计算工具类，提供 CRC32、CRC32C 与 Adler32 算法门面。
_Avoid_: CrcUtil, HashCheckUtil, ChecksumHelper

**TokenBucketLimiter**:
基于无锁 CAS 与纳秒级时间推进的高性能纯内存平滑令牌桶限流原语，支持突发流量缓冲与单机平滑限速整形。
_Avoid_: RateLimiter, TokenLimiter, LeakyBucket

**CoreProperties**:
核心通用底座统一外置化配置属性类，统管雪花发号器、缓存等强类型配置与生产默认值。
_Avoid_: AtengProperties, BaseProperties, FrameworkProperties


