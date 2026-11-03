# LiteFlow Context

`ateng-boot-liteflow` 是面向复杂业务规则、营销算价、风控决断与 DAG 管道流转的高性能、生产就绪型规则编排基础设施 Starter，基于 LiteFlow 2.16.x 权威内核深度加固构建，坚持显式强契约、零臃肿依赖、受管线程池全局接管、四维上下文透明穿透、泛型化受管基类体系、多源动态热刷新 SPI 与全维云原生可观测性。

## Language

**LiteflowTemplate**:
核心操作门面模板类，提供强类型上下文执行（`execute`）、多上下文执行、受管异步编排（`executeAsync`）、直接 EL 表达式即时执行（`executeEl`）与规则动态热重载（`reloadRule` / `reloadChain`）的全生命周期对称 API。
_Avoid_: RuleEngineService, FlowUtil, LiteflowManager, RuleHelper

**BaseNodeComponent**:
强类型普通业务节点基类，泛型绑定业务 Context 模型（`T`），提供纯净强类型的 `getContext()`、入参提取（`getTag()` / `getCmpData(Class<D>)`）、前置卫语句防卫与执行审计。
_Avoid_: AbstractComponent, CommonNode, RuleNode, SimpleComponent

**BaseSwitchComponent**:
强类型选择路由节点基类，泛型绑定业务 Context 模型，根据业务上下文状态与决策规则计算并返回目标路由分支节点 ID。
_Avoid_: SwitchNode, RouterComponent, BranchNode, SelectComponent

**BaseIfComponent**:
强类型二元条件判断节点基类，泛型绑定业务 Context 模型，计算并返回 boolean 决策结果，支撑 `IF(x, a, b)` 分支流转。
_Avoid_: IfNode, ConditionComponent, DecisionNode, BooleanNode

**BaseForComponent / BaseWhileComponent / BaseIteratorComponent / BaseBreakComponent**:
强类型循环与迭代控制基类全谱系，分别覆盖计次循环（`FOR`）、条件循环（`WHILE`）、集合元素迭代（`ITERATOR`）与循环中断判定（`BREAK`），统一提供泛型上下文与超时防护。
_Avoid_: LoopNode, IterationComponent, CycleNode, RepeatComponent

**LiteflowGlobalExecutorBuilder**:
受管全局异步线程池构建器，实现 LiteFlow 官方 `ExecutorBuilder` SPI，底层桥接 `ateng-boot-core` 的受管线程池设施（`ThreadPoolTaskExecutor`），自动注入 `AsyncContext` 四维跨线程上下文快照，确保并行 `WHEN` 组件中的 MDC `traceId` 与多租户/用户上下文绝对不丢失并严格清理现场。
_Avoid_: CustomThreadPoolBuilder, ThreadBuilder, LiteflowAsyncConfig, ThreadPoolBridge

**LiteflowRuleSourceProvider**:
规则源动态扩展 SPI，统一抽象本地文件、数据库（JDBC / MyBatis-Plus）、Redis 及配置中心等外部介质的规则文本拉取契约，打破仅支持本地静态配置文件的局限。
_Avoid_: RuleLoader, RuleDao, FlowConfigProvider, RuleStorageService

**LiteflowRuleRefreshEvent**:
Spring 原生规则热更新领域事件，在数据库、Redis 或配置中心发生规则变更时触发广播，驱动 `LiteflowTemplate` 自动执行增量或全量规则热加载。
_Avoid_: RuleChangeEvent, ReloadMessage, FlowUpdateNotice, RuleSyncEvent

**LiteflowExecutionTrace**:
结构化单次规则执行链路轨迹模型，记录时序节点清单、标签（Tag）、执行耗时（TimeSpentMs）、状态（SUCCESS/FAIL/SKIPPED）与异常摘要。
_Avoid_: FlowHistory, StepLog, ExecutionPath, TraceAudit

**LiteflowExecutionException**:
统一规则执行受管业务异常，继承自 `BusinessException`，在组件执行失败或超时熔断时抛出，完整保留底层因果链（Cause）与规则链元数据（ChainId、FailedNodeId）。
_Avoid_: RuleException, FlowRuntimeException, LiteflowError, FlowFailException
