# LiteFlow Context

`ateng-boot-liteflow` 是面向复杂业务规则、营销算价、风控决断与 DAG 管道流转的高性能、生产就绪型规则编排基础设施 Starter，基于 LiteFlow 2.16.x 权威内核深度加固构建，坚持显式强契约、零臃肿依赖、受管线程池全局接管、四维上下文透明穿透、泛型化受管基类体系、多源动态热刷新 SPI 与全维云原生可观测性。

## Language

**LiteflowTemplate**:
核心操作门面模板类，提供强类型上下文执行（`execute`）、多上下文执行（`executeMultipleWithContext`）、受管异步编排（`executeAsync` / `executeAsyncWithTrace`）、决策路由表编排（`executeRoute` / `executeRouteWithTrace`）、在线 EL 语法预校验（`validateEl`）、直接 EL 表达式即时执行（`executeEl`）、规则版本快照安全回退（`rollbackChain`）与动态热重载（`reloadRule` / `reloadChain` / `reloadRouteChain`）的全生命周期对称 API。
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

**LiteflowRouteResult**:
决策路由执行结果强类型契约模型，承载指定业务命名空间下命中匹配的规则链集合（`matchedChainIds`）、聚合更新后的强类型业务上下文（`T`）、命中规则数、总耗时与时序执行轨迹。
_Avoid_: DecisionResult, RouteOutput, RoutingData, MatchResult

**Route Chain (决策路由表)**:
规则决策路由机制，通过业务命名空间（Namespace）聚合多条具备前置决策条件（`routeEl`）的规则体（`bodyEl`），在统一上下文下驱动多条规则并行/串行自适应匹配与业务累加计算。
_Avoid_: DecisionTable, RuleRouter, GatewayChain, MatchChain

**LiteflowElValidationResult**:
结构化 EL 表达式语法与引用静态校验结果模型，提供合法性标记（`valid`）、语法错误摘要、引用的未注册组件清单（`componentMissing`）以及解析出的节点与子链集合，为中后台在线配流提供零风险静态预检。
_Avoid_: SyntaxCheckResult, ElVerifyVo, RuleValidateDto, CompileResult

**Rule Snapshot Rollback (规则版本快照与安全回滚)**:
动态规则历史版本快照链与故障安全回退机制，在动态刷新或更新流程链时自动保留有界健康快照队列（`maxHistorySnapshots` 防内存膨胀），支持生产事故一键精准无缝回滚至上一稳定版本。
_Avoid_: RuleRollbackHelper, VersionManager, HistoryCache, UndoRule

**LiteflowMetricsBinder**:
生产级云原生 Micrometer 性能度量收集器，自动导出规则链与组件执行吞吐和延迟直方图指标（`liteflow.chain.executions`、`liteflow.chain.duration`、`liteflow.node.duration`），内建动态 EL 表达式与 32 位 UUID 链 ID 的归一化清洗能力（统一映射为 `DYNAMIC_EL`），严防 Prometheus 标签基数爆炸与内存泄漏。
_Avoid_: FlowMetricsCollector, FlowMonitor, LiteflowStats, RuleMetrics
