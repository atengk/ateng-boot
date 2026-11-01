# ateng-boot-datasource 基础设施 Starter

> 面向 SaaS 物理多租户分库、多从库集群读写分离自适应路由、后台主动探活退避自愈、写后读主防延迟、物理连接池并发预热、Micrometer 指标透视、外部数据源差量同步 SPI、编程式对称门面 `DynamicDatasourceTemplate`、事务假切源刚性守卫 `DynamicDatasourceGuard` 与零明文国密密码解密的生产就绪多数据源基础设施 Starter。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.datasource.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **租户分库** | SaaS 物理多租户透明切源 | `AtengDynamicRoutingDataSource` | `ateng.boot.datasource.multi-tenancy.enabled`<br>`ateng.boot.datasource.multi-tenancy.mode`<br>`ateng.boot.datasource.multi-tenancy.data-source-prefix`<br>`ateng.boot.datasource.multi-tenancy.default-data-source` | 自动感知 `TenantContextHolder.getTenantId()` 映射目标物理库或从库分组；支持 `STRICT`（严格分库，未映射抛 `TenantDataSourceNotFoundException`）、`FALLBACK_MASTER`（回退主库）与 `IGNORE`（免租户路由主库）三级模式及 `setIgnoreTenant(true)` 免租户穿透 | `MultiTenantRoutingIntegrationTest` / `OrderService` |
| **租户分库** | 租户数据源解析扩展 SPI | `TenantDataSourceProvider` | `ateng.boot.datasource.multi-tenancy.custom-provider-enabled` | `String resolveDataSourceKey(String tenantId)` 与 `Map<String, String> getTenantDataSourceMap()`，支持自定义外部租户库映射字典或外部元数据注册中心动态解析 | `CustomTenantDataSourceProvider` / `MultiTenantRoutingIntegrationTest` |
| **租户分库** | 多租户隔离模式枚举 | `MultiTenancyMode` | `ateng.boot.datasource.multi-tenancy.mode` | `STRICT`（严格分库，找不到即抛异常）、`FALLBACK_MASTER`（找不到平滑回退主库）、`IGNORE`（强制主库忽略租户） | `MultiTenantRoutingIntegrationTest` |
| **读写分离** | 主从读写分离自适应路由 | `AtengDynamicRoutingDataSource` | `ateng.boot.datasource.read-write-split.enabled`<br>`ateng.boot.datasource.read-write-split.master-data-source`<br>`ateng.boot.datasource.read-write-split.slave-data-source` | 默认写操作绑定主库（`master`）；标记 `@DS("slave")` 或处于只读事务 `@Transactional(readOnly = true)` 时，自动负载均衡路由至从库集群（`slave` 或租户专属 `GroupDataSource`） | `ReadWriteSplittingIntegrationTest` / `DatasourceTenantReadWriteScenarioService` |
| **读写分离** | 租户级读写从库集群识别 | `AtengDynamicRoutingDataSource` | `ateng.boot.datasource.read-write-split.enabled` | 识别租户映射键为 `GroupDataSource`（如 `tenant_1001_slave`），只读事务下自动分发至该租户专属从库群，受写后读主防延迟机制保护 | `DatasourceHardenedScenariosIntegrationTest` / `DatasourceTenantReadWriteScenarioService` |
| **读写分离** | 写后即读防延迟引擎 | `ReadAfterWriteManager` | `ateng.boot.datasource.read-write-split.read-after-write.enabled`<br>`ateng.boot.datasource.read-write-split.read-after-write.scope`<br>`ateng.boot.datasource.read-write-split.read-after-write.window-ttl` | `markWriteOccurred()` 记录写操作；`isWriteOccurred()` 判定防延迟；支持单请求（`REQUEST`）与时间窗口（`WINDOW`，默认 3s）模式，窗口期内强制回退主库读取 | `ReadWriteSplittingIntegrationTest` / `DatasourceTenantReadWriteScenarioService` |
| **读写分离** | 请求作用域防延迟上下文 | `ReadAfterWriteContextHolder` | - | `markWriteOccurred()` / `isWriteOccurred()` / `clear()`，线程级单请求防延迟上下文持有者 | `OrderControllerMockMvcTest` |
| **读写分离** | 请求结束上下文清理过滤器 | `ReadAfterWriteFilter` | - | 实现 Servlet `Filter`，在 HTTP 请求生命周期结束时于 `finally` 块确定性执行 `ReadAfterWriteContextHolder.clear()` 防线程现场污染 | `OrderControllerMockMvcTest` |
| **读写分离** | 时间窗口写操作感知 SPI | `WriteOccurredResolver` | - | `markWrite(Duration)` / `isWriteOccurred(Duration)` / `clear()`，支持本地内存（`DefaultWriteOccurredResolver`）或分布式集中缓存扩展 | `ReadWriteSplittingIntegrationTest` / `DefaultWriteOccurredResolver` |
| **读写分离** | 防延迟作用域枚举 | `ReadAfterWriteScope` | `ateng.boot.datasource.read-write-split.read-after-write.scope` | `REQUEST`（单次 HTTP 请求内防延迟）、`WINDOW`（滑动时间窗口跨请求防延迟） | `ReadWriteSplittingIntegrationTest` / `OrderControllerMockMvcTest` |
| **自愈熔断** | 从库故障熔断与主动探活自愈 | `HealthAwareDynamicDataSourceStrategy` | `ateng.boot.datasource.read-write-split.failover.enabled`<br>`ateng.boot.datasource.read-write-split.failover.cooldown-window`<br>`ateng.boot.datasource.read-write-split.failover.fallback-to-master`<br>`ateng.boot.datasource.read-write-split.failover.active-probe-enabled`<br>`ateng.boot.datasource.read-write-split.failover.probe-initial-interval`<br>`ateng.boot.datasource.read-write-split.failover.probe-max-interval`<br>`ateng.boot.datasource.read-write-split.failover.probe-timeout` | 感知从库异常并熔断隔离（黑名单）；后台异步以轻量 `isValid(1)` 主动探活；验证成功立即解封放行，探测失败指数退避（5s ~ 30s）；全从库故障保底降级回退主库；对外提供 `probeAndRecover()` / `getBlacklistedDataSources()` / `isBlacklisted()` / `recordFailure()` / `unblacklist()` | `HealthAwareDynamicDataSourceActiveProbeTest` / `DatasourceActiveProbingScenarioService` |
| **连接预热** | 连接池并发预热防冷抖动引擎 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.dynamic-load.pre-warm`<br>`ateng.boot.datasource.dynamic-load.pre-warm-connections` | `int preWarmDataSource(String dsKey)` 与 `int preWarmDataSource(String dsKey, int count)`，动态挂载后并发建立物理连接并放回池中，平滑应对首批突发流量；提供 `addDataSource(dsKey, property, boolean preWarm)` 显式开启；遵循 Fail-Safe 容错不阻断挂载；自动安全解包 `ItemDataSource`（对外开放 `DataSource unwrapDataSource(DataSource)`） | `DynamicDatasourcePreWarmTest` / `DatasourcePreWarmScenarioService` |
| **度量观测** | Micrometer 指标透视与自动绑定 | `DynamicDatasourceMetricsBinder` | `ateng.boot.datasource.metrics.enabled` | 自动感知动态数据源注册与注销，实时将 HikariPool 连接池指标绑定至 Micrometer（`datasource.connections.active`, `idle`, `total`, `threads_awaiting`），注销时自动清理 Gauges；提供 `bindMetrics(dsKey, ds)` 与 `unbindMetrics(dsKey)` | `DynamicDatasourceMetricsTest` / `DatasourceMetricsScenarioService` |
| **度量观测** | 连接池运行指标快照契约 | `DataSourcePoolStats` | - | 强类型承载 `dsKey`, `poolName`, `activeConnections`, `idleConnections`, `totalConnections`, `threadsAwaitingConnection`, `maximumPoolSize`, `minimumIdle`，具备零外部依赖与 NPE 防御 | `DynamicDatasourceMetricsTest` / `DatasourceMetricsScenarioService` |
| **度量观测** | 编程式指标快照查询门面 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.metrics.enabled` | `DataSourcePoolStats getPoolStats(String dsKey)` 与 `Map<String, DataSourcePoolStats> getAllPoolStats()`，支持单个或遍历全部分组物理库实时快照查询 | `DynamicDatasourceMetricsTest` / `DatasourceMetricsScenarioService` |
| **生命周期** | 数据源生命周期监听扩展 SPI | `DynamicDatasourceLifecycleListener` | - | `onDataSourceAdded(String dsKey, DataSource ds)` 与 `onDataSourceRemoved(String dsKey)`，监听动态挂载与安全销毁事件，支持指标注册与资源清理扩展 | `DynamicDatasourceTemplateLifecycleTest` |
| **生命周期** | 生命周期监听器注册管理 | `DynamicDatasourceTemplate` | - | `addLifecycleListener(DynamicDatasourceLifecycleListener)` 与 `removeLifecycleListener(...)`，线程安全维护生命周期监听器链表 | `DynamicDatasourceTemplateLifecycleTest` |
| **差量同步** | 外部数据源动态加载 SPI | `DynamicDataSourceLoader` | `ateng.boot.datasource.dynamic-load.enabled` | `Map<String, DataSourceProperty> loadDataSources()`，从外部数据库表、Nacos/Apollo 配置中心加载最新数据源字典 | `DynamicDataSourceSyncManagerTest` / `DatasourceDynamicLoaderScenarioService` |
| **差量同步** | 智能三向差量比对同步管理器 | `DynamicDataSourceSyncManager` | `ateng.boot.datasource.dynamic-load.enabled`<br>`ateng.boot.datasource.dynamic-load.core-whitelist`<br>`ateng.boot.datasource.dynamic-load.auto-sync.enabled`<br>`ateng.boot.datasource.dynamic-load.auto-sync.interval` | `SyncResult sync()` 触发智能三向差量同步（新增、热重载、优雅排空注销）；具备核心库绝对白名单防护（`isProtectedCoreKey` 默认锁死 `master`, `slave` 防误删）；支持定时后台轮询自动同步与 `unregisterTrackedProperty(String)` 重置追踪 | `DynamicDataSourceSyncManagerTest` / `DatasourceDynamicLoaderScenarioService` |
| **事务守卫** | 假切源刚性拦截防卫 | `DynamicDatasourceGuard` | `ateng.boot.datasource.guard.enabled`<br>`ateng.boot.datasource.guard.fail-on-false-switch` | `validateSwitch(String, Method, Class<?>)`，在 Spring 单连接事务中拦截跨物理连接切源，杜绝静默串库（抛出 `DataSourceSwitchException`） | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourceTransactionGuardScenarioService` |
| **事务守卫** | 声明式切源 AOP 拦截守卫 | `AtengDynamicDataSourceAnnotationInterceptor` | `ateng.boot.datasource.guard.enabled` | 拦截 `@DS` 切源注解，在入栈前委托 `DynamicDatasourceGuard` 执行安全前置校验 | `DatasourceAsyncAndTransactionGuardIntegrationTest` |
| **对称门面** | 编程式切源执行门面 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.enabled` | `callInDataSource(String, Supplier<T>)` / `runInDataSource(String, Runnable)`，基于显式数据源 Key 切换执行并保证栈现场安全清理 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceLifecycleScenarioService` |
| **对称门面** | 租户级编程式切源门面 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.multi-tenancy.enabled` | `callInTenantDataSource(String, Supplier<T>)` / `runInTenantDataSource(String, Runnable)`，根据租户标识自动映射并切换租户库 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 全库批量巡检容错沙箱 | `DynamicDatasourceTemplate` | - | `forEachDataSource(Consumer<String>)` / `forEachDataSource(BatchExecutionPolicy, ...)`，支持全物理库安全遍历，自动汇总 `BatchExecutionResult` | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 全租户批量巡检容错沙箱 | `DynamicDatasourceTemplate` | - | `forEachTenant(BiConsumer<String, String>)` / `forEachTenant(BatchExecutionPolicy, ...)`，支持跨所有已注册租户执行统计与数据巡检 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 数据源元数据与键集查询 | `DynamicDatasourceTemplate` | - | `getDataSourceKeys()` / `getGroupKeys()` / `getDataSource(String)` / `containsDataSource(String)` / `getCurrentDataSourceKey()` / `resolveActiveTenants()` | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **分组路由** | 分组数据源管理与集群挂载门面 | `DynamicDatasourceTemplate` | - | `registerGroupDataSource(String, GroupDataSource)` / `removeGroupDataSource(String)` / `addGroupMember(String, String)`，编程式安全注册、注销从库分组及动态追加物理成员节点 | `DatasourceHardenedScenariosIntegrationTest` / `DatasourceTenantReadWriteScenarioService` |
| **对称门面** | 批处理容错策略枚举 | `BatchExecutionPolicy` | - | `FAIL_SAFE`（单库异常隔离并汇总，继续遍历其余库）、`FAIL_FAST`（遇首个异常立即熔断中断） | `DatasourceBatchAndLifecycleIntegrationTest` |
| **对称门面** | 批处理执行结果与失败项契约 | `BatchExecutionResult` / `BatchFailure` | - | `isSuccess()` / `hasFailures()` / `getSuccessCount()` / `getFailureCount()` / `getFailures()`，承载批量巡检结构化统计结果 | `DatasourceBatchAndLifecycleIntegrationTest` |
| **生命周期** | 运行时动态挂载数据源 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.dynamic-load.enabled`<br>`ateng.boot.datasource.dynamic-load.probe-timeout` | `addDataSource(String, DataSourceProperty)` 与 `addDataSource(String, DataSource)`，前置网络探活校验后动态挂载新物理连接池 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceLifecycleScenarioService` |
| **生命周期** | 运行时优雅排空注销数据源 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.dynamic-load.drain-timeout` | `removeDataSource(String, boolean)`，支持等待在途事务连接归还的优雅排空（默认 5s），避免连接悬挂与暴力销毁报错 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceLifecycleScenarioService` |
| **密码安全** | 国密/国际算法零明文解密 | `DataSourcePasswordDecryptor` | `ateng.boot.datasource.security.password-decryption-enabled`<br>`ateng.boot.datasource.security.secret-key`<br>`ateng.boot.datasource.security.iv` | `supports(String)` 与 `decrypt(String)`，自动识别国密 SM4（`enc:sm4:`）与国际 AES（`enc:aes:`）密文密码并瞬态解密，杜绝配置中心明文凭据 | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourcePasswordSecurityScenarioService` |
| **密码安全** | 凭据解密事件驱动还原 | `AtengDataSourceInitEvent` | - | 在连接池物理创建前瞬态注入明文密码，连接池创建完成后立即还原为原始密文，防止内存堆转储泄露 | `DatasourceBatchAndLifecycleIntegrationTest` |
| **异步传播** | 五维上下文全链路跨线程透传 | `DataSourceAsyncContextSnapshotProvider` | - | 自动集成至 Core 模块 `AsyncContext` 基础设施，全自动透传 `MDC TraceId`、`User`、`TenantId`、`IgnoreTenant` 与 `DataSourceKey` | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourceAsyncContextScenarioService` |
| **声明切源** | Baomidou 核心声明式注解 | `@DS` | `spring.datasource.dynamic.datasource.*` | 标于类或方法声明目标数据源 Key 或 SpEL 表达式（如 `@DS("master")`、`@DS("slave")`、`@DS("#header.tenant")`） | `OrderService` / `DatasourceTransactionGuardScenarioService` |
| **多源事务** | 本地多源协调事务注解 | `@DSTransactional` | - | Baomidou 原生多源本地事务协调注解，支持多数据源按序提交与回滚，自动被 `DynamicDatasourceGuard` 放行 | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourceTransactionGuardScenarioService` |
| **多源事务** | Seata 分布式事务集成开关 | 自动装配扩展 | `ateng.boot.datasource.seata.enabled` | 开启后适配 Seata 分布式事务协调器，自动对齐假切源守卫放行逻辑 | `AtengDatasourceAutoConfiguration` |
| **异常契约** | 统一数据源业务错误码 | `DatasourceResultCode` | - | 规范化声明数据源切换失败、租户库不存在、动态加载失败、优雅排空超时与密码解密异常错误码 | `DatasourceResultCode` |

---

## 1. 模块定位与技术基线

`ateng-boot-datasource` 是面向高并发、微服务与多租户云原生架构的工业级多数据源基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring 6、HikariCP 与 Baomidou Dynamic-Datasource 官方核心扩展，坚决杜绝引入重型第三方全量工具包；
- **全自动多租户物理分库与从库集群**：深度整合 Core 模块 `TenantContextHolder`，开箱支持 `STRICT`（严格分库）、`FALLBACK_MASTER`（回退主库行级隔离）与 `IGNORE` 三级隔离策略，且支持租户分发至独立的 `GroupDataSource` 从库集群；
- **从库故障后台主动探活自愈**：摒弃传统“让线上用户流量当探活小白鼠”的被动重试方案，采用后台受管定时探活与指数退避（5s ~ 30s），自愈后瞬时解封放行；
- **写后即读防从库延迟 (Read-After-Write Consistency)**：原生解决主从复制物理毫秒级延迟导致的脏读痛点，支持单请求（`REQUEST`）与时间窗口（`WINDOW`）模式，写后自动平滑回退主库；
- **连接池并发预热防冷抖动**：支持动态挂载后并发预热建立物理连接，穿透解包 `ItemDataSource`，消除首批突发流量连接排队毛刺；
- **Actuator & Micrometer 指标透视**：自动将动态挂载/注销的数据源连接池状态（Active, Idle, Total, Awaiting）绑定至度量注册表，注销时自动清理；
- **外部数据源动态加载与差量同步 (Smart Diff)**：提供 `DynamicDataSourceLoader` SPI 与 `DynamicDataSourceSyncManager`，支持从配置中心/数据库三向差量同步，严密保护核心库白名单；
- **Spring 事务假切源刚性拦截防卫 (Zero False Switch)**：通过 `DynamicDatasourceGuard` 探针拦截事务内非法切源，杜绝因本地事务物理连接固定引发的静默数据串库资损事故；
- **完备对称的高阶操作门面 (`DynamicDatasourceTemplate`)**：提供全库巡检、全租户遍历、异常受控收集与动态加库/优雅排空注销功能；
- **零明文凭据安全与全链路追踪**：支持国密 SM4 / 国际 AES 数据库密码瞬态安全解密，异步线程池自动透传五维上下文。

### Maven 坐标引入

在已导入 `ateng-boot-bom` 的工程中，无需声明版本号即可直接引入：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-datasource</artifactId>
</dependency>
```

---

## 2. 生产基础配置与极简起步体验 (Happy Path & Out-of-the-Box)

### 2.1 生产标准配置范式 (`application.yml`)

以下配置逐字对齐 `DatasourceProperties` 字段，内置生产级高可用默认值：

```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-datasource
  datasource:
    dynamic:
      primary: master
      strict: false
      datasource:
        master:
          url: jdbc:h2:mem:master_db;DB_CLOSE_DELAY=-1;MODE=MySQL
          username: sa
          password: ""
          driver-class-name: org.h2.Driver
        slave:
          url: jdbc:h2:mem:slave_db;DB_CLOSE_DELAY=-1;MODE=MySQL
          username: sa
          password: ""
          driver-class-name: org.h2.Driver
        tenant_1001:
          url: jdbc:h2:mem:tenant_1001_db;DB_CLOSE_DELAY=-1;MODE=MySQL
          username: sa
          password: ""
          driver-class-name: org.h2.Driver
        tenant_1002:
          url: jdbc:h2:mem:tenant_1002_db;DB_CLOSE_DELAY=-1;MODE=MySQL
          username: sa
          password: ""
          driver-class-name: org.h2.Driver

ateng:
  boot:
    datasource:
      enabled: true
      # SaaS 物理多租户透明切库配置
      multi-tenancy:
        enabled: true
        mode: FALLBACK_MASTER
        default-data-source: master
        data-source-prefix: "tenant_"
      # 主从读写分离、从库主动探活与写后即读防延迟配置
      read-write-split:
        enabled: true
        master-data-source: master
        slave-data-source: slave
        read-after-write:
          enabled: true
          scope: WINDOW
          window-ttl: 3s
        failover:
          enabled: true
          cooldown-window: 30s
          fallback-to-master: true
          active-probe-enabled: true
          probe-initial-interval: 5s
          probe-max-interval: 30s
          probe-timeout: 1s
      # 事务切源刚性守卫
      guard:
        enabled: true
        fail-on-false-switch: true
      # 动态加载、预热与受管生命周期治理
      dynamic-load:
        enabled: true
        probe-timeout: 2s
        drain-timeout: 5s
        pre-warm: false
        pre-warm-connections: 5
        core-whitelist:
          - master
          - slave
        auto-sync:
          enabled: false
          interval: 1m
      # Micrometer 指标透视
      metrics:
        enabled: true
      # 凭据安全解密
      security:
        password-decryption-enabled: true
        secret-key: "0123456789abcdeffedcba9876543210"
```

### 2.2 极简开箱即用代码示例

#### 场景 1：多租户透明切库与读写分离业务服务 (`OrderService.java`)

```java
package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final ReadAfterWriteManager readAfterWriteManager;

    /**
     * 租户透明切库下单：自动识别 TenantContextHolder 路由对应租户数据库
     */
    public OrderResponse createTenantOrder(OrderCreateRequest request) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[OrderService] 开始为租户 [{}] 创建订单: {}", currentTenant, request.getOrderNo());

        OrderEntity entity = new OrderEntity();
        entity.setOrderNo(request.getOrderNo());
        entity.setProductName(request.getProductName());
        entity.setAmount(request.getAmount());
        entity.setTenantId(currentTenant);
        entity.setStatus("CREATED");

        orderMapper.insert(entity);
        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 主库写入订单并激活写后读主防延迟
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse createMasterOrderWithPostWrite(OrderCreateRequest request) {
        log.info("[OrderService] 主库事务写入订单并激活写后读主防延迟标记: {}", request.getOrderNo());
        readAfterWriteManager.markWriteOccurred();

        OrderEntity entity = new OrderEntity();
        entity.setOrderNo(request.getOrderNo());
        entity.setProductName(request.getProductName());
        entity.setAmount(request.getAmount());
        entity.setStatus("PAID");

        orderMapper.insert(entity);
        return convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey());
    }

    /**
     * 从库读取订单：在写后读主保护期内，自动回退重定向到 master 读取
     */
    @DS("slave")
    public OrderResponse getOrderReadWrite(String orderNo) {
        log.info("[OrderService] 从库读取订单 (受写后读主防延迟保护): {}", orderNo);
        OrderEntity entity = orderMapper.selectOne(
                new LambdaQueryWrapper<OrderEntity>().eq(OrderEntity::getOrderNo, orderNo)
        );
        return entity != null ? convertToVo(entity, dynamicDatasourceTemplate.getCurrentDataSourceKey()) : null;
    }

    private OrderResponse convertToVo(OrderEntity entity, String routedDs) {
        return OrderResponse.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .tenantId(entity.getTenantId())
                .productName(entity.getProductName())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .routedDataSource(routedDs)
                .build();
    }
}
```

#### 场景 2：租户多从库分组挂载与写后读主防延迟 (`DatasourceTenantReadWriteScenarioService.java`)

```java
package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceTenantReadWriteScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final AtengDynamicRoutingDataSource routingDataSource;
    private final ReadAfterWriteManager readAfterWriteManager;

    /**
     * 为指定租户配置多节点读从库分组 (GroupDataSource)
     */
    public void configureTenantSlaveGroup(String tenantId, String groupKey, String nodeKey) {
        DataSource nodeDs = routingDataSource.findDataSource(nodeKey);
        if (nodeDs != null) {
            GroupDataSource groupDataSource = new GroupDataSource(groupKey, routingDataSource.getHealthAwareStrategy());
            groupDataSource.addDatasource(nodeKey, nodeDs);
            routingDataSource.getGroupDataSources().put(groupKey, groupDataSource);
        }
    }

    /**
     * 在只读上下文执行租户查询（自动负载均衡路由至租户从库集群，写后读主生效时强制切回租户主库）
     */
    public String executeTenantReadOnlyQuery(String tenantId) {
        return dynamicDatasourceTemplate.callInTenantDataSource(tenantId, () ->
                dynamicDatasourceTemplate.getCurrentDataSourceKey()
        );
    }
}
```

#### 场景 3：动态数据源并发预热与冷启动防抖 (`DatasourcePreWarmScenarioService.java`)

```java
package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.model.DataSourcePoolStats;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourcePreWarmScenarioService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;

    /**
     * 动态挂载数据源并执行物理连接并发预热
     */
    public int registerAndPreWarm(String dsKey, String jdbcUrl, int targetCount) {
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(dsKey);
        property.setUrl(jdbcUrl);
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        // 挂载数据源并在之后执行并发预热
        dynamicDatasourceTemplate.addDataSource(dsKey, property, false);
        return dynamicDatasourceTemplate.preWarmDataSource(dsKey, targetCount);
    }

    /**
     * 实时查询预热后数据源的连接池指标快照
     */
    public DataSourcePoolStats inspectPoolStats(String dsKey) {
        return dynamicDatasourceTemplate.getPoolStats(dsKey);
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 自定义租户数据源解析器 (`TenantDataSourceProvider` SPI)

当租户标识与数据源 Key 的映射关系并非简单的 `"tenant_" + tenantId` 前缀，而是存储在配置中心、数据库或外部元数据字典时，实现 `TenantDataSourceProvider` SPI 即可完成完全接管（直接逆向提取自场景样例工程 [`CustomTenantDataSourceProvider.java`](../ateng-boot-samples/ateng-boot-sample-datasource/src/main/java/io/github/atengk/boot/sample/datasource/config/CustomTenantDataSourceProvider.java)）：

```java
package io.github.atengk.boot.sample.datasource.config;

import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "ateng.boot.datasource.multi-tenancy", name = "custom-provider-enabled", havingValue = "true")
public class CustomTenantDataSourceProvider implements TenantDataSourceProvider {

    private final Map<String, String> tenantMapping = Map.of(
            "VIP_1001", "tenant_1001",
            "VIP_1002", "tenant_1002"
    );

    @Override
    public String resolveDataSourceKey(String tenantId) {
        return tenantMapping.getOrDefault(tenantId, "tenant_" + tenantId);
    }

    @Override
    public Map<String, String> getTenantDataSourceMap() {
        return tenantMapping;
    }
}
```

### 3.2 外部数据源动态加载与三向差量同步 (`DynamicDataSourceLoader` SPI)

当租户数据源配置保存在分布式配置中心（Nacos/Apollo）或数据库表（如 `sys_tenant_datasource`）中时，声明实现 `DynamicDataSourceLoader` SPI 并配合 `DynamicDataSourceSyncManager`，即可实现增量挂载、修改热重载与安全销毁的全生命周期闭环（直接逆向提取自样例工程）：

```java
package io.github.atengk.boot.sample.datasource.config;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.loader.DynamicDataSourceLoader;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class NacosDynamicDataSourceLoader implements DynamicDataSourceLoader {

    @Override
    public Map<String, DataSourceProperty> loadDataSources() {
        Map<String, DataSourceProperty> configs = new HashMap<>();
        // 从配置中心或数据库加载最新数据源列表
        DataSourceProperty prop = new DataSourceProperty();
        prop.setPoolName("tenant_dynamic");
        prop.setUrl("jdbc:h2:mem:tenant_dynamic_db;DB_CLOSE_DELAY=-1");
        prop.setUsername("sa");
        prop.setPassword("");
        prop.setDriverClassName("org.h2.Driver");
        configs.put("tenant_dynamic", prop);
        return configs;
    }
}
```

通过场景服务或配置 `ateng.boot.datasource.dynamic-load.auto-sync.enabled=true` 触发差量同步：

```java
@Service
@RequiredArgsConstructor
public class DatasourceDynamicLoaderScenarioService {

    private final DynamicDataSourceSyncManager syncManager;

    public SyncResult triggerSync() {
        return syncManager.sync();
    }
}
```

### 3.3 自定义写操作感知解析器 (`WriteOccurredResolver` SPI)

框架默认提供基于本地滑动过期与用户/线程标识绑定的 `DefaultWriteOccurredResolver`，其工作机制如下：
1. 优先提取 Core 模块 `UserContextHolder.getUserId()` 绑定当前客户端标识；若未登录则保底回退至当前工作线程 ID；
2. 在本地 `ConcurrentHashMap` 记录窗口过期时间戳，并具备滑动窗口防无界内存泄漏能力；
3. 在分布式微服务集群跨节点防延迟场景下，开发者可自行声明实现 `WriteOccurredResolver` SPI 接入分布式集中缓存（如 Redis），实现跨实例时间窗口防延迟。

### 3.4 动态数据源生命周期监听扩展 (`DynamicDatasourceLifecycleListener` SPI)

当应用需要在物理数据源动态挂载（初始化完成）或销毁后执行自定义动作（如初始化租户表结构、注册特定中间件等），可直接注册生命周期监听器：

```java
dynamicDatasourceTemplate.addLifecycleListener(new DynamicDatasourceLifecycleListener() {
    @Override
    public void onDataSourceAdded(String dsKey, DataSource ds) {
        log.info("监听到新数据源已挂载完成: {}", dsKey);
    }

    @Override
    public void onDataSourceRemoved(String dsKey) {
        log.info("监听到数据源已安全注销释放: {}", dsKey);
    }
});
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 Spring 事务假切源刚性拦截防卫机制

Spring 官方的 `DataSourceTransactionManager` 本地事务管理器在事务开启（`doBegin`）时便从数据源获取单一物理连接并绑定至线程。若在 `@Transactional` 方法内部尝试切换不同物理连接的数据源，该指令会被事务静默忽略，引发致命串库数据事故。

`DynamicDatasourceGuard` 提供刚性阻断防卫：
- 当检测到处于活动 Spring 事务中，且目标切源数据源与当前事务物理数据源不一致时：
  ```text
  检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险：
  当前事务已绑定数据源 [master]，尝试切源至 [tenant_1001] 将被 Spring 事务静默忽略并造成串库！
  ```
- **正确规范**：若业务必须跨库执行，目标方法必须声明 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 挂起外层事务并开启独立物理连接新事务，或采用 `@DSTransactional` 本地多源协调事务。

### 4.2 从库后台异步主动探活退避自愈机制

- **终结线上用户流量当探活小白鼠**：传统读写分离在从库熔断后，冷却期一过便借用下一笔线上真实业务请求做连通性试验；若从库仍未恢复，该笔真实请求将承受物理连接超时或失败损耗。
- **后台主动探活闭环**：
  1. `HealthAwareDynamicDataSourceStrategy` 捕获从库物理网络异常后，自动将其加入黑名单；
  2. 调度受管后台探活任务（基于虚拟线程或核心调度线程池），周期性调用轻量 `Connection.isValid(1)` 检测故障物理库；
  3. 一旦探活成功，**立即从黑名单解封并恢复流量放行**；
  4. 若持续探活失败，探活周期自适应指数退避（从 5s 退避至最大 30s），防止对宕机实例造成网络与连接风暴；
  5. 全从库集群宕机时，根据 `fallback-to-master=true` 策略，自动将读请求安全回退至主库，保障应用对外服务零中断。

### 4.3 物理连接池并发预热与冷启动防抖动

- **消除首批突发请求毛刺**：HikariCP 默认惰性按需创建连接，当动态挂载租户库后瞬时涌入大并发流量时，各线程竞争建立物理连接，引发请求排队与连接超时。
- **Fail-Safe 预热防卫**：
  1. 调用 `preWarmDataSource(dsKey, count)` 并发建立物理连接，验证连通性后将其归还池中；
  2. 自动根据连接池配置进行上限对齐（`Math.min(targetCount, hikari.getMaximumPoolSize())`）；
  3. 若预热过程中发生部分物理连接超时或建连失败，遵循 Fail-Safe 容错机制记录告警并安全释放已建连接，绝不中断数据源正常挂载流程；
  4. 原生解包 `ItemDataSource`，确保对底层物理连接池的精准透视。

### 4.4 Micrometer 与 Actuator 连接池实时指标透视

- 自动为所有已挂载数据源（包括主库、静态从库、动态租户库）向 Micrometer 注册实时 Gauges 度量：
  - `datasource.connections.active`：活跃在途事务连接数
  - `datasource.connections.idle`：空闲就绪物理连接数
  - `datasource.connections.total`：连接池当前总物理连接数
  - `datasource.connections.threads_awaiting`：正在排队等待获取连接的线程数
- 配合 Spring Boot Actuator 的 `/actuator/metrics` 或 `/actuator/prometheus` 接口，无缝接入 Prometheus 与 Grafana 实时看板；
- 数据源注销时，通过 `DynamicDatasourceLifecycleListener` 自动清理度量指标，杜绝内存泄漏。

### 4.5 外部数据源智能三向差量同步与核心库白名单防护

`DynamicDataSourceSyncManager` 对比最新外部配置集与内存当前已挂载数据源集，执行三向差量比对（Smart Diff）：
1. **新增数据源**：执行前置网络探活预检，挂载并按需并发预热；
2. **热重载数据源**：当配置发生变更（URL、账号、连接池参数），安全销毁旧连接池并重载新连接池；
3. **已删除数据源**：执行安全优雅排空（等待在途事务连接归还，默认超时 5s），彻底关闭连接池并清理度量指标；
4. **核心库白名单铁律**：`isProtectedCoreKey` 默认锁死核心库白名单（`master`, `slave` 及主库 `primary`），即使外部配置被误清空或恶意篡改，核心主从库绝对禁止被注销删除！

### 4.6 零明文国密密码解密与内存瞬态还原

- **密文前缀标准**：
  - 国密 SM4 ECB/CBC：`enc:sm4:<Base64Key>:<CipherHex>` 或 `enc:sm4:<CipherHex>`（使用全局 `secret-key`）
  - 国际 AES CBC/GCM：`enc:aes:<Base64Key>:<Base64Iv>:<CipherHex>`
- **瞬态还原**：在 HikariCP 连接池创建前触发解密，创建完毕后通过 `AtengDataSourceInitEvent` 立即将 `DataSourceProperty` 的 `password` 字段还原回原始密文，防止内存堆转储（Heap Dump）或日志审计中泄露明文数据库凭据。

### 4.7 异步受管线程池五维上下文透传

`ateng-boot-datasource` 自动注册 `DataSourceAsyncContextSnapshotProvider` 并接入 Core 模块的 `AsyncContext` 基础设施。无论通过 `@Async` 注解调度，还是提交至受管线程池（`ThreadPoolTaskExecutor`），自动完成以下五维快照的完整透传与安全清理：
$$\langle\text{MDC TraceId}, \text{CurrentUser}, \text{TenantId}, \text{IgnoreTenant}, \text{DataSourceKey}\rangle$$

---

## 5. 物理排版与代码全真验证 (Evergreen & Zero-Glitch)

### 5.1 验证源头全真逆向提取

本模块所有文档示例、YAML 配置及 API 行为均 100% 逆向提取自已跑通的场景样例工程：
- **场景工程路径**：[`ateng-boot-samples/ateng-boot-sample-datasource`](../ateng-boot-samples/ateng-boot-sample-datasource)
- **端到端集成测试**：
  - [`DatasourceHardenedScenariosIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/DatasourceHardenedScenariosIntegrationTest.java)：覆盖租户多从库分组路由与写后读主、从库故障熔断与主动自愈、连接池并发预热、Micrometer 监控采样与外部数据源差量同步 5 大生产场景实测；
  - [`MultiTenantRoutingIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/MultiTenantRoutingIntegrationTest.java)：多租户物理分库、`STRICT` 刚性拦截、`IGNORE` 强制主库、`setIgnoreTenant(true)` 免租户穿透及 SPI 行为测试；
  - [`ReadWriteSplittingIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/ReadWriteSplittingIntegrationTest.java)：读写分离、写后读主防延迟与从库宕机自愈熔断测试；
  - [`DatasourceBatchAndLifecycleIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/DatasourceBatchAndLifecycleIntegrationTest.java)：批量巡检容错隔离（`FAIL_SAFE` 与 `FAIL_FAST`）、动态挂载排空与零明文 SM4 密文密码真实连接池初始化测试；
  - [`DatasourceAsyncAndTransactionGuardIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/DatasourceAsyncAndTransactionGuardIntegrationTest.java)：事务守卫刚性拦截、`@DSTransactional` 放行、`NESTED` 拦截、五维异步快照透传与凭据解密测试；
  - [`OrderControllerMockMvcTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/OrderControllerMockMvcTest.java)：Web 边界 HTTP 契约与 `ReadAfterWriteFilter` 请求后线程清理端到端集成测试。

### 5.2 真实控制台测试输出记录

以下控制台输出直接截取自本地实际执行 `mvn test` 的真实控制台输出：

```log
[INFO] Running io.github.atengk.boot.sample.datasource.DatasourceHardenedScenariosIntegrationTest
2026-11-01 00:42:24.498 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourceTenantReadWriteScenarioService : [TenantReadWriteScenario] 为租户 [tenant_1001] 配置只读从库组 [groupKey=tenant_1001_slave, nodeKey=slave]
2026-11-01 00:42:24.508 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourceTenantReadWriteScenarioService : [TenantReadWriteScenario] 租户从库组挂载完成: tenant_1001_slave
2026-11-01 00:42:24.516 DEBUG [           main] [NO-TRACE] .g.a.b.d.r.AtengDynamicRoutingDataSource : 租户 [tenant_1001] 只读事务匹配到从库分组 [tenant_1001_slave]，自适应路由至从库集群
2026-11-01 00:42:24.524 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourceTenantReadWriteScenarioService : [TenantReadWriteScenario] 租户 [tenant_1001] 写入订单并标记写发生: ORD-TENANT-RW-001
2026-11-01 00:42:24.538 DEBUG [           main] [NO-TRACE] .g.a.b.d.r.AtengDynamicRoutingDataSource : 租户 [tenant_1001] 处于写后读主防延迟窗口期，强制路由至租户主库: [tenant_1001]
2026-11-01 00:42:24.561 WARN  [           main] [NO-TRACE] s.DatasourceActiveProbingScenarioService : [ActiveProbingScenario] 模拟从库节点 [slave] 发生物理异常并记录失败
2026-11-01 00:42:24.565 WARN  [           main] [NO-TRACE] .g.a.b.d.s.HealthAwareDynamicDataSourceStrategy : ateng-boot-datasource: 物理从库发生异常，已加入熔断黑名单 [slave] (冷却隔离: PT30S)
2026-11-01 00:42:24.960 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourceDynamicLoaderScenarioService : [DynamicLoaderScenario] 手动触发外部动态数据源差量同步
2026-11-01 00:42:24.963 INFO  [           main] [NO-TRACE] i.g.a.b.d.l.DynamicDataSourceSyncManager : ateng-boot-datasource: 开始执行外部数据源三向差量比对同步 (Smart Diff)...
2026-11-01 00:42:24.981 INFO  [           main] [NO-TRACE] i.g.a.b.d.l.DynamicDataSourceSyncManager : ateng-boot-datasource: 差量同步新增数据源成功 [dsKey=tenant_sync_sample]
2026-11-01 00:42:24.995 INFO  [           main] [NO-TRACE] i.g.a.b.d.l.DynamicDataSourceSyncManager : ateng-boot-datasource: 完成外部数据源差量同步 (新增: 1, 注销: 0, 热重载: 0, 核心跳过: 0)
2026-11-01 00:42:25.032 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourcePreWarmScenarioService : [PreWarmScenario] 动态挂载并预热数据源 [dsKey=tenant_prewarm, targetCount=3]
2026-11-01 00:42:25.048 INFO  [           main] [NO-TRACE] i.g.a.b.d.t.DynamicDatasourceTemplate    : ateng-boot-datasource: 动态注册受管数据源成功 [dsKey=tenant_prewarm]
2026-11-01 00:42:25.048 INFO  [           main] [NO-TRACE] g.a.b.d.m.DynamicDatasourceMetricsBinder : ateng-boot-datasource: 成功注册数据源 Micrometer 度量指标 [dsKey=tenant_prewarm]
2026-11-01 00:42:25.094 INFO  [           main] [NO-TRACE] i.g.a.b.d.t.DynamicDatasourceTemplate    : ateng-boot-datasource: 预热数据源 [tenant_prewarm] 连接池完成，成功建立并释放 3/3 个物理连接
2026-11-01 00:42:25.095 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourcePreWarmScenarioService : [PreWarmScenario] 数据源 [tenant_prewarm] 成功预热 3 个物理连接
2026-11-01 00:42:25.098 INFO  [           main] [NO-TRACE] b.s.d.s.DatasourcePreWarmScenarioService : [PreWarmScenario] 注销预热数据源: tenant_prewarm
2026-11-01 00:42:25.148 INFO  [           main] [NO-TRACE] c.b.d.d.DynamicRoutingDataSource         : dynamic-datasource - remove the database named [tenant_prewarm] success
2026-11-01 00:42:25.151 INFO  [           main] [NO-TRACE] i.g.a.b.d.t.DynamicDatasourceTemplate    : ateng-boot-datasource: 已从动态路由表摘除数据源流量 [dsKey=tenant_prewarm]
2026-11-01 00:42:25.154 INFO  [           main] [NO-TRACE] i.g.a.b.d.t.DynamicDatasourceTemplate    : ateng-boot-datasource: 受管数据源已安全物理关闭 [dsKey=tenant_prewarm]
2026-11-01 00:42:25.166 INFO  [           main] [NO-TRACE] g.a.b.d.m.DynamicDatasourceMetricsBinder : ateng-boot-datasource: 已注销并清理数据源 Micrometer 度量指标 [dsKey=tenant_prewarm]
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 31.90 s -- in io.github.atengk.boot.sample.datasource.DatasourceHardenedScenariosIntegrationTest
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  01:00 min (Wall Clock)
[INFO] Finished at: 2026-11-01T00:42:35+08:00
[INFO] ------------------------------------------------------------------------
```
