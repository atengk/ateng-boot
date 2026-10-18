# ateng-boot

面向生产就绪的 Spring Boot 3 工业级基础设施与全能中间件 Starter 套件，坚持纯原生、零臃肿依赖与显式强类型契约设计。

## Language

### 契约与传输 (Contracts & Transport)

**Result**:
框架全生态通用的统一强类型 API 响应载荷模型，通过显式声明返回类型保障类型自洽。
_Avoid_: CommonResult, ApiResponse, R, ResponseData

**PageQuery**:
无状态分页入参防越界校验契约，负责页码、页长与排序方向的合法性约束。
_Avoid_: PageParam, PaginationRequest, QueryPage

**PageResult**:
与持久层实体绝对解耦的分页视图纯净出参模型，专职承载记录列表、总数与分页元信息。
_Avoid_: PageVO, IPage, TableDataInfo

### 审计与治理 (Audit & Governance)

**Operation Audit**:
全反应堆跨层流转的标准化操作元数据，结构化沉淀操作主体、动作分类、环境上下文、耗时与执行状态。
_Avoid_: Access Log, System Log, Audit Trail, Trace Span

**Operation Type**:
标准化的业务动作语义分类枚举，涵盖数据增删改查、吞吐流转与特权分配。
_Avoid_: ActionType, LogType, BusinessType

### 底座与协同 (Core & Coordination)

**Transaction Synchronization**:
编程式事务生命周期钩子，确保回调任务与异步事件严格在数据库物理事务提交或回滚后安全执行。
_Avoid_: Transaction Hook, DB Trigger, Event Synchronization
