# HTTP Context

`ateng-boot-http` 负责基于 Spring 6 原生 `RestClient` 与 JDK 17+ 体系的高并发、生产级 HTTP 远程通信底座，兼顾命令式流畅操作与声明式接口契约，杜绝重型第三方包装框架。

## Language

**HttpTemplate**:
HTTP 模块命令式操作核心门面具体类，提供对称的动词 CRUD、流式上传下载、泛型反序列化及单次 RequestOptions 覆盖能力。
_Avoid_: RestTemplate, HttpUtil, WebClient, HttpClientHelper

**ApacheHttpClient5Factory**:
基于 Apache HttpClient 5 生产级池化连接管理器的物理连接工厂，提供后台空闲与过期连接自动驱逐、借出保活探测与快速失败租借。
_Avoid_: OkHttpFactory, PoolingClientFactory, HttpClientFactory

**JdkHttpClientFactory**:
基于 JDK 17 原生 HttpClient 的传输工厂实现，作为无 HttpClient 5 运行时环境下的零依赖优雅降级底座。
_Avoid_: SimpleClientHttpRequestFactory, FallbackClientFactory

**RequestOptions**:
单次 HTTP 请求维度的动态参数配置载荷，支持覆盖单次超时、注入自定义 Header、结构化 Query 参数以及 URI 变量。
_Avoid_: HttpOptions, RequestConfig, CallOptions

**HttpRemoteException**:
HTTP 远程通信统一语义化业务异常（继承 Core 模块 BusinessException），封装远端 HTTP 状态码、请求方法、URI、安全截断的响应体及统一错误码。
_Avoid_: RestClientException, RemoteException, HttpCallException

**HttpErrorHandler**:
Spring 6 RestClient 统一出站错误响应处理器，拦截 4xx/5xx 响应，杜绝黑盒剥壳，安全提取错误载荷并转译为 HttpRemoteException。
_Avoid_: ResponseErrorHandler, ErrorInterceptor, HttpErrorDecoder

**HttpClient5Customizer**:
底层 Apache HttpClient 5 构建过程的函数式定制器 SPI，允许下游在不覆盖自动装配的前提下深度微调连接管理与 Socket 策略。
_Avoid_: HttpClientCustomizer, HttpCustomizer

**TraceContextInterceptor**:
出站 HTTP 请求四维上下文透传与非信任域隔离拦截器，自动捕获 MDC 链路追踪标识、租户标识与用户标识注入标准请求头，并在目标为公网非信任服务时执行安全剥离。
_Avoid_: ContextInterceptor, HeaderInterceptor, TraceFilter

**HttpLoggingInterceptor**:
出站 HTTP 结构化日志拦截器，提供四级日志开关、终端可复现 cURL 脚本打印、敏感 Header 动态掩码脱敏及大响应体容量安全截断。
_Avoid_: LoggingInterceptor, HttpLogger, CurlInterceptor

**HttpRetryInterceptor**:
出站弹性重试拦截器，深度接入 Core 模块 RetryUtil 函数式引擎，针对幂等动词在遭遇网络 I/O 抖动与 502/503/504 异常时执行指数退避重试与随机抖动打散。
_Avoid_: RetryInterceptor, BackoffInterceptor, SpringRetryTemplate

**HttpResultCode**:
HTTP 远程通信专职语义化结果码枚举（实现 Core 模块 IResultCode 契约），精准区分远程下游客户端错误（40001）、下游服务端内部错误（50001）、网络调用超时（50004）及网关不可达（50002）。
_Avoid_: ResultCode, HttpResponseCode, ErrorCode
