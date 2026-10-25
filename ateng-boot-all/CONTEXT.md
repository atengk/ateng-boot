# All Context

`ateng-boot-all` 是面向终端应用的一键聚合全家桶模块，以传递依赖形式汇聚所有核心 Starter，提供极简的引入心智。

## Language

**StarterAggregator**:
纯 POM 依赖聚合模块，不包含编译源码，仅通过依赖传递机制向业务方透传 core、web、mybatis-plus、redis、excel、satoken、storage、http、realtime 与 statemachine 模块。专有消息中间件（RabbitMQ、Kafka、RocketMQ）遵循按需独立引入原则。
_Avoid_: UberJar, FatJar, ShadeModule

