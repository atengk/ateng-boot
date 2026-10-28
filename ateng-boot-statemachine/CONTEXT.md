# StateMachine 高性能无状态业务状态机底座

面向复杂业务生命周期治理与订单履约的高性能无状态业务状态机底座，坚持纯原生无重依赖哲学，提供强类型 Fluent DSL 规则编排（支持外部主干跃迁、内部自跃迁、多源汇聚跃迁与 Choice 动态条件分支路由）、实体状态零侵入 Lambda 访问门面、CAS 乐观锁防重执行模板、三阶段切面拦截器 SPI、双通道查找与动态 Mermaid/PlantUML 状态拓扑图导出。

## Language

**StateMachine (业务状态机)**:
全局单例且严格不可变的强类型无状态状态机实例，仅维护静态流转路由拓扑，不持有任何运行时业务实体的可变状态。
_Avoid_: 有状态状态机, 流程引擎, StatefulMachine, WorkflowEngine

**State (业务状态)**:
标识业务实体在特定生命周期阶段下的离散特征枚举或强类型对象（如 WAIT_PAY, PAID, DELIVERING）。
_Avoid_: 状态标记, 阶段标志, 流程节点, Step, Stage

**Event (流转事件)**:
由外部用户交互、定时任务调度或系统调用触发的驱动状态发生改变或自跃迁的强类型信号。
_Avoid_: 动作, 指令, 操作, Action, Command, Trigger

**Context (业务上下文)**:
伴随单个事件触发全链路流转的瞬态载荷对象，用于在 Condition 卫语句判断与 Action 动作执行间透传业务参数。
_Avoid_: 环境变量, 全局变量, 共享数据, GlobalContext, DataMap

**Transition (状态跃迁)**:
由特定事件触发，在满足前置卫语句条件时，将实体从源状态驱动至目标状态（或维持原状态）的原子流转规则。
_Avoid_: 状态切换, 状态变更, 流程流转, StepFlow

**External Transition (外部状态跃迁)**:
源状态与目标状态不同（$S_1 \to S_2$）的标准外部流转拓扑，会触发完整的进入/退出与后置 Action。
_Avoid_: 普通流转, 跨状态跳转, StepJump

**Internal Transition (内部自状态跃迁)**:
源状态与目标状态保持同一（$S_1 \to S_1$）的内部自流转，用于在不改变业务主状态的前提下执行属性更新（如追加物流跟踪轨迹）。
_Avoid_: 自身循环, 死循环, 自调用, SelfLoop

**Multi-From Transition (多源汇聚跃迁)**:
支持多个不同的源状态（$[S_1, S_2, S_3]$）在响应同一事件时统一收敛流向同一目标状态（$S_n$），消除笛卡尔积样板代码。
_Avoid_: 多对一跳转, 聚合流转, AnyTo

**Choice Transition (动态条件分支路由)**:
在同一源状态和事件下，依据运行时上下文多维参数动态短路求值，分流走向不同目标状态（$S_1 \xrightarrow{E} \text{if } C_1 \implies S_2 \text{ else } S_3$）的分支跃迁。
_Avoid_: 动态跳转, 条件分支网关, ExclusiveGateway, Fork

**Condition (前置卫语句)**:
在跃迁执行前对运行时业务上下文进行无副作用真值判定的纯函数式断言，必须全部满足方可放行跃迁。
_Avoid_: 业务校验, 拦截器, 检查器, Checker, Rule

**Action (跃迁后置动作)**:
当状态跃迁被成功接受并校验通过后，按声明顺序同步执行的业务副作用动作函数，支持访问源状态、目标状态、事件与上下文。
_Avoid_: 处理器, 回调, 业务逻辑, Handler, Callback, Hook

**Transition Result (跃迁执行结果)**:
承载一次事件触发是否被成功接受、源状态、目标状态、失败原因及异常因果链的不可变结构化结果模型。
_Avoid_: 响应报文, 操作结果, ResultVO, FlowResponse

**CAS Updater (CAS 乐观锁更新器)**:
协同数据库原子条件更新（`UPDATE ... WHERE status = :from AND version = :ver`）的函数式持久化契约，用于防范并发重复跃迁。
_Avoid_: 数据库更新器, 锁管理器, DBUpdater

**StateMachine Interceptor (状态机切面拦截器)**:
提供状态机全局前置、后置与异常三阶段执行拦截的 SPI 扩展点，用于切面日志追踪与 Micrometer 度量监控。
_Avoid_: 过滤器, 状态机切面, StateFilter, MachineAspect

**StateMachine Factory (状态机单例工厂)**:
为全局应用提供基于状态机全局标识（`machineId`）快速检索单例实例的内存注册表。
_Avoid_: 状态机容器, 引擎管理器, MachineManager, StateContext
