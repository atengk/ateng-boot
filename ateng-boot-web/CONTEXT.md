# Web Context

`ateng-boot-web` 负责 Web 运行时的非侵入性增强，涵盖请求链路追踪流转、全局异常安全拦截、JSON 序列化标准与跨域策略。

## Language

**TraceFilter**:
在 HTTP 请求入口处提取或生成链路追踪标识、写入日志 MDC、回写响应头并在出口安全清理的高优先级 Web 过滤器。
_Avoid_: LogFilter, TraceInterceptor, MDCFilter

**GlobalExceptionHandler**:
捕获控制器抛出的各类已知业务异常与未知系统异常，将其安全转译为统一 Result 结构并对内部敏感错误做生产脱敏的切面处理器。
_Avoid_: ExceptionAdvice, ErrorHandler, WebExceptionHandler

**JacksonCustomizer**:
基于 Spring 标准构建器定制器挂载的长整型转字符串（防雪花失真）与 Java 8 全量日期东八区标准化配置。
_Avoid_: ObjectMapperConfig, JsonConfig, JacksonConfig

**CorsConfiguration**:
基于属性开关动态启闭与配置允许跨域源、HTTP 方法及请求头的 Web 安全规则。
_Avoid_: CrossFilter, WebCorsConfig

**AtengAsyncAutoConfiguration**:
上下文感知受管异步线程池自动装配类，抢先于 Spring Boot 默认任务执行配置导出预装配 ContextCopyingTaskDecorator 的 ThreadPoolTaskExecutor（支持 CallerRunsPolicy 与优雅停机），并实现 AsyncConfigurer 为 Spring 异步体系统一提供调度与未捕获异常处理。
_Avoid_: AsyncConfig, ThreadPoolConfiguration, AsyncThreadPoolAutoConfiguration

**AsyncProperties**:
异步线程池运行时增强配置属性，以 ateng.boot.async 为前缀，提供基于 CPU 核心数自适应的线程池容量、队列缓冲与优雅停机超时控制。
_Avoid_: ThreadPoolProperties, AsyncConfigProperties
