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
