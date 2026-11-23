# All Context

`ateng-boot-all` 是面向终端应用的一键聚合全家桶模块，以传递依赖形式汇聚所有核心 Starter，提供极简的引入心智。

## Language

**StarterAggregator**:
零编译源码、以空 JAR 为载体的传递依赖聚合模块（`<packaging>jar</packaging>`），不承载具体业务源码与自动装配逻辑，仅通过声明式 `<dependencies>` 传递机制向业务方一站式透传核心 Starter 组合；高阶或重型外部中间件严格遵循按需独立引入原则。
_Avoid_: UberJar, FatJar, ShadeModule, SourceBearingStarter

**CoreStarterBundle**:
由 `ateng-boot-all` 强锁定透传的六大核心通用底座组合：`ateng-boot-core`、`ateng-boot-web`、`ateng-boot-mybatis-plus`、`ateng-boot-redis`、`ateng-boot-excel`、`ateng-boot-satoken`。专有存储（`storage` / `ftp` / `ssh`）、HTTP/网络通信（`http` / `tcp` / `realtime` / `mqtt`）、消息引擎（`rabbitmq` / `kafka` / `rocketmq`）、调度检索支付与接口文档（`job-*` / `search` / `pay` / `openapi`）等按需引入模块不纳入此绑定组合，避免单应用无谓膨胀。
_Avoid_: EverythingStarter, KitchenSinkBundle

**SmokeVerificationGate**:
由 `ateng-boot-samples/ateng-boot-sample-smoke-test` 承担的一体化冒烟准出门禁。以 `ateng-boot-all` 为基座并叠加代表性按需 Starter（含 `ateng-boot-openapi`、`storage`、`http`、`realtime`、`encrypt`、`statemachine`、`tcp`），通过离线 `@SpringBootTest` +多领域 `MockMvc` 端到端链路断言零 Bean 冲突与跨模块协同正确性。
_Avoid_: UnitTestOnlySmoke, StaleKnife4jSmoke


