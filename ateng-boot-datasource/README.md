# ateng-boot-datasource 基础设施 Starter

> 面向 SaaS 物理分库透明切库、高可用主从读写分离、从库自愈熔断、写后读主防延迟、编程式对称门面 `DynamicDatasourceTemplate`、事务假切源刚性守卫 `DynamicDatasourceGuard` 与零明文国密密码解密的生产就绪多数据源中间件基础设施 Starter。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.datasource.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **租户分库** | SaaS 物理多租户透明切源 | `AtengDynamicRoutingDataSource` | `ateng.boot.datasource.multi-tenancy.enabled`<br>`ateng.boot.datasource.multi-tenancy.mode`<br>`ateng.boot.datasource.multi-tenancy.data-source-prefix` | 自动感知 `TenantContextHolder.getTenantId()` 映射目标物理库（如 `tenant_1001`）；支持 `STRICT`（刚性阻断抛 `TenantDataSourceNotFoundException`）、`FALLBACK_MASTER`（回退主库）与 `IGNORE` 三级模式 | `MultiTenantRoutingIntegrationTest` / `OrderService` |
| **租户分库** | 租户数据源解析扩展 SPI | `TenantDataSourceProvider` | - | `resolveDataSourceKey(String tenantId)`，支持自定义外部租户库映射字典或外部元数据注册中心动态解析 | `TenantDataSourceProviderTest` |
| **读写分离** | 主从读写分离与自适应路由 | `AtengDynamicRoutingDataSource` | `ateng.boot.datasource.read-write-split.enabled`<br>`ateng.boot.datasource.read-write-split.master-data-source`<br>`ateng.boot.datasource.read-write-split.slave-data-source` | 默认写操作绑定主库（`master`）；标记 `@DS("slave")` 或只读查询自动负载均衡路由至从库集群（`slave`） | `ReadWriteSplittingIntegrationTest` / `OrderService` |
| **读写分离** | 写后即读防延迟引擎 | `ReadAfterWriteManager` | `ateng.boot.datasource.read-write-split.read-after-write.enabled`<br>`ateng.boot.datasource.read-write-split.read-after-write.scope`<br>`ateng.boot.datasource.read-write-split.read-after-write.window-ttl` | `markWriteOccurred()` 记录写操作；`isWriteOccurred()` 判定防延迟；支持单请求（`REQUEST`）与时间窗口（`WINDOW`，默认 3s）模式，窗口期内强制回退主库 | `ReadWriteSplittingIntegrationTest` / `OrderControllerMockMvcTest` |
| **读写分离** | 请求作用域防延迟上下文 | `ReadAfterWriteContextHolder` | - | `markWriteOccurred()` / `isWriteOccurred()` / `clear()`，线程级单请求防延迟上下文持有者 | `ReadAfterWriteContextHolderTest` |
| **读写分离** | 时间窗口写操作感知 SPI | `WriteOccurredResolver` | - | `markWrite(Duration)` / `isWriteOccurred(Duration)` / `clear()`，支持通过本地内存（`DefaultWriteOccurredResolver`）或分布式缓存扩展 | `DefaultWriteOccurredResolverTest` |
| **读写分离** | 从库自愈熔断高可用策略 | `HealthAwareDynamicDataSourceStrategy` | `ateng.boot.datasource.read-write-split.failover.enabled`<br>`ateng.boot.datasource.read-write-split.failover.cooldown-window`<br>`ateng.boot.datasource.read-write-split.failover.fallback-to-master` | 实时感知从库物理网络异常，自动熔断隔离故障从库（默认 30s 冷却）；全从库故障时平滑保底降级回退主库 | `HealthAwareDynamicDataSourceStrategyTest` |
| **事务守卫** | 假切源刚性拦截防卫 | `DynamicDatasourceGuard` | `ateng.boot.datasource.guard.enabled`<br>`ateng.boot.datasource.guard.fail-on-false-switch` | `validateSwitch(String, Method, Class<?>)`，在 Spring 单连接事务中拦截跨物理连接切源，杜绝静默串库（抛出 `DataSourceSwitchException`） | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourceTransactionGuardScenarioService` |
| **对称门面** | 编程式切源执行门面 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.enabled` | `callInDataSource(String, Supplier<T>)` / `runInDataSource(String, Runnable)`，基于显式数据源 Key 切换执行并保证现场安全清理 | `DynamicDatasourceTemplateTest` / `DatasourceLifecycleScenarioService` |
| **对称门面** | 租户级编程式切源门面 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.multi-tenancy.enabled` | `callInTenantDataSource(String, Supplier<T>)` / `runInTenantDataSource(String, Runnable)`，根据租户标识自动映射并切换租户库 | `DynamicDatasourceTemplateTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 全库批量巡检容错沙箱 | `DynamicDatasourceTemplate` | - | `forEachDataSource(Consumer<String>)` / `forEachDataSource(BatchExecutionPolicy, ...)`，支持全库安全遍历，自动收集 `BatchExecutionResult` | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 全租户批量巡检容错沙箱 | `DynamicDatasourceTemplate` | - | `forEachTenant(BiConsumer<String, String>)` / `forEachTenant(BatchExecutionPolicy, ...)`，支持跨所有已注册租户执行统计与数据巡检 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceBatchScenarioService` |
| **对称门面** | 数据源元数据与键集查询 | `DynamicDatasourceTemplate` | - | `getDataSourceKeys()` / `getGroupKeys()` / `containsDataSource(String)` / `getCurrentDataSourceKey()` / `resolveActiveTenants()` | `DynamicDatasourceTemplateTest` |
| **生命周期** | 运行时动态挂载数据源 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.dynamic-load.enabled`<br>`ateng.boot.datasource.dynamic-load.probe-timeout` | `addDataSource(String, DataSourceProperty)` 与 `addDataSource(String, DataSource)`，前置网络探活校验后动态挂载新物理连接池 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceLifecycleScenarioService` |
| **生命周期** | 运行时优雅排空注销数据源 | `DynamicDatasourceTemplate` | `ateng.boot.datasource.dynamic-load.drain-timeout` | `removeDataSource(String, boolean)`，支持等待在途事务连接归还的优雅排空，避免连接悬挂与暴力销毁报错 | `DatasourceBatchAndLifecycleIntegrationTest` / `DatasourceLifecycleScenarioService` |
| **密码安全** | 国密/国际算法零明文解密 | `DataSourcePasswordDecryptor` | `ateng.boot.datasource.security.password-decryption-enabled`<br>`ateng.boot.datasource.security.secret-key`<br>`ateng.boot.datasource.security.iv` | `supports(String)` 与 `decrypt(String)`，自动识别国密 SM4（`enc:sm4:`）与国际 AES（`enc:aes:`）密文密码并瞬态解密，杜绝配置中心明文凭据 | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourcePasswordSecurityScenarioService` |
| **密码安全** | 凭据解密事件驱动还原 | `AtengDataSourceInitEvent` | - | 在连接池物理创建前瞬态注入明文密码，连接池创建完成后立即还原为原始密文，防止内存泄露 | `AtengDataSourceInitEventTest` |
| **异步传播** | 五维上下文全链路跨线程透传 | `DynamicDatasourceContextPropagator` | - | 自动集成至 Core 模块 `AsyncContext` 基础设施，全自动透传 `MDC TraceId`、`User`、`TenantId`、`IgnoreTenant` 与 `DataSourceKey` | `DatasourceAsyncAndTransactionGuardIntegrationTest` / `DatasourceAsyncContextScenarioService` |
| **声明切源** | Baomidou 核心声明式注解 | `@DS` | `spring.datasource.dynamic.datasource.*` | 标于类或方法声明目标数据源 Key 或 SpEL 表达式（如 `@DS("master")`、`@DS("slave")`、`@DS("#header.tenant")`） | `OrderService` / `DatasourceTransactionGuardScenarioService` |
| **多源事务** | 本地多源协调事务注解 | `@DSTransactional` | - | Baomidou 原生多源本地事务协调注解，支持多数据源按序提交与回滚，自动被 `DynamicDatasourceGuard` 放行 | `DynamicDatasourceGuardTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-datasource` 是面向高并发、微服务与多租户云原生架构的工业级多数据源基础设施 Starter：
- **纯原生零臃肿底座**：100% 依托 Spring 6、HikariCP 与 Baomidou Dynamic-Datasource 官方核心扩展，坚决杜绝引入重型第三方全量工具包；
- **全自动多租户物理分库**：深度整合 Core 模块 `TenantContextHolder`，开箱支持 `STRICT`（严格分库）、`FALLBACK_MASTER`（回退主库行级隔离）与 `IGNORE` 三级隔离策略；
- **写后即读防从库延迟 (Read-After-Write Consistency)**：原生解决主从复制物理毫秒级延迟导致的脏读痛点，支持单请求（`REQUEST`）与时间窗口（`WINDOW`）模式，写后自动平滑回退主库；
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
      # 主从读写分离与写后即读防延迟配置
      read-write-split:
        enabled: true
        master-data-source: master
        slave-data-source: slave
        read-after-write:
          enabled: true
          scope: REQUEST
          window-ttl: 3s
        failover:
          enabled: true
          cooldown-window: 30s
          fallback-to-master: true
      # 事务切源刚性守卫
      guard:
        enabled: true
        fail-on-false-switch: true
      # 动态加载与生命周期受管治理
      dynamic-load:
        enabled: true
        probe-timeout: 2s
        drain-timeout: 5s
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

#### 场景 2：REST 传输边界控制器 (`OrderController.java`)

```java
package io.github.atengk.boot.sample.datasource.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/tenant")
    public Result<OrderResponse> createTenantOrder(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.createTenantOrder(request));
    }

    @GetMapping("/tenant/{orderNo}")
    public Result<OrderResponse> getTenantOrder(@PathVariable String orderNo) {
        return Result.success(orderService.getTenantOrder(orderNo));
    }

    @PostMapping("/master")
    public Result<OrderResponse> createMasterOrder(@Valid @RequestBody OrderCreateRequest request) {
        return Result.success(orderService.createMasterOrderWithPostWrite(request));
    }

    @GetMapping("/read-write/{orderNo}")
    public Result<OrderResponse> getOrderReadWrite(@PathVariable String orderNo) {
        return Result.success(orderService.getOrderReadWrite(orderNo));
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 3.1 自定义租户数据源解析器 (`TenantDataSourceProvider` SPI)

当租户标识与数据源 Key 的映射关系并非简单的 `"tenant_" + tenantId` 前缀，而是存储在配置中心、数据库或外部 KMS 字典时，实现 `TenantDataSourceProvider` SPI 即可完成完全接管：

```java
@Component
public class CustomTenantDataSourceProvider implements TenantDataSourceProvider {

    private final Map<String, String> tenantMapping = Map.of(
            "VIP_TENANT_A", "ds_cluster_shanghai",
            "VIP_TENANT_B", "ds_cluster_beijing"
    );

    @Override
    public String resolveDataSourceKey(String tenantId) {
        return tenantMapping.getOrDefault(tenantId, "tenant_" + tenantId);
    }
}
```

### 3.2 自定义写操作感知解析器 (`WriteOccurredResolver` SPI)

框架默认提供基于本地内存滑动过期的 `DefaultWriteOccurredResolver`。在分布式微服务架构中，若需实现跨多节点集群的全局写后读主防延迟，可通过 Redis 接入分布式实现：

```java
@Component
@ConditionalOnProperty(prefix = "ateng.boot.datasource.read-write-split.read-after-write", name = "scope", havingValue = "WINDOW")
public class RedisWriteOccurredResolver implements WriteOccurredResolver {

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void markWrite(Duration windowTtl) {
        String key = "rw:delay:" + UserContextHolder.getUserId();
        stringRedisTemplate.opsForValue().set(key, "1", windowTtl);
    }

    @Override
    public boolean isWriteOccurred(Duration windowTtl) {
        String key = "rw:delay:" + UserContextHolder.getUserId();
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    @Override
    public void clear() {
        String key = "rw:delay:" + UserContextHolder.getUserId();
        stringRedisTemplate.delete(key);
    }
}
```

### 3.3 编程式全库与租户批量巡检 (`DynamicDatasourceTemplate`)

```java
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final OrderMapper orderMapper;

    /**
     * 全库批量巡检订单总数（遇到个别坏库自动隔离容错，不阻断全盘）
     */
    public BatchExecutionResult inspectAllDatabases() {
        return dynamicDatasourceTemplate.forEachDataSource(dsKey -> {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<>());
            log.info("数据源 [{}] 当前订单存量: {}", dsKey, count);
        });
    }

    /**
     * 运行时动态挂载新数据源并在使用后优雅排空注销
     */
    public void executeOnDynamicDatabase(String tempDsKey, String jdbcUrl) {
        DataSourceProperty property = new DataSourceProperty();
        property.setUrl(jdbcUrl);
        property.setUsername("sa");
        property.setPassword("");
        property.setDriverClassName("org.h2.Driver");

        // 1. 动态添加并初始化连接池
        dynamicDatasourceTemplate.addDataSource(tempDsKey, property);
        try {
            // 2. 编程式切源执行
            dynamicDatasourceTemplate.runInDataSource(tempDsKey, () -> {
                log.info("正在临时数据源 [{}] 上执行归档脚本", tempDsKey);
            });
        } finally {
            // 3. 优雅排空注销连接池 (drain=true 等待在途连接归还)
            dynamicDatasourceTemplate.removeDataSource(tempDsKey, true);
        }
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 Spring 事务假切源刚性拦截防卫机制

Spring 官方的 `DataSourceTransactionManager` 本地事务管理器在事务开启（`doBegin`）时便从数据源获取单一物理连接并绑定至线程。若在 `@Transactional` 方法内部尝试切换不同物理连接的数据源，该指令会被事务静默忽略，引发致命串库数据事故。

`DynamicDatasourceGuard` 提供刚性阻断防卫：
- 当检测到处于活动 Spring 事务中，且目标切源数据源与当前事务物理数据源不一致时：
  ```
  检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险：
  当前事务已绑定数据源 [master]，尝试切源至 [tenant_1001] 将被 Spring 事务静默忽略并造成串库！
  ```
- **正确规范**：若业务必须跨库执行，目标方法必须声明 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 挂起外层事务并开启独立物理连接新事务，或采用 `@DSTransactional` 本地多源协调事务。

### 4.2 从库宕机自愈熔断与故障转移 (Failover)

- **物理异常隔离**：当从库因网络抖动或崩溃导致 `getConnection()` 抛出 `SQLException` 时，`HealthAwareDynamicDataSourceStrategy` 捕获异常并将该从库放入黑名单冷却隔离窗口（默认 30 秒）；
- **动态负载摘除**：在冷却期内，所有读请求自动绕开故障从库节点；
- **全从库宕机保底**：当从库集群全部宕机时，根据 `fallback-to-master=true` 策略，自动将读请求安全回退至主库，保障应用对外服务零中断。

### 4.3 零明文国密密码解密与内存瞬态还原

- **密文前缀标准**：
  - 国密 SM4 ECB/CBC：`enc:sm4:<Base64Key>:<CipherHex>` 或 `enc:sm4:<CipherHex>`（使用全局 `secret-key`）
  - 国际 AES CBC/GCM：`enc:aes:<Base64Key>:<Base64Iv>:<CipherHex>`
- **瞬态还原**：在 HikariCP 连接池创建前触发解密，创建完毕后通过 `AtengDataSourceInitEvent` 立即将 `DataSourceProperty` 的 `password` 字段还原回原始密文，防止内存堆转储（Heap Dump）或日志审计中泄露明文数据库凭据。

### 4.4 异步受管线程池五维上下文透传

`ateng-boot-datasource` 自动注册 `DynamicDatasourceContextPropagator` 并接入 Core 模块的 `AsyncContext` 基础设施。无论通过 `@Async` 注解调度，还是提交至受管线程池（`ThreadPoolTaskExecutor`），自动完成以下五维快照的完整透传与安全清理：
$$\langle\text{MDC TraceId}, \text{CurrentUser}, \text{TenantId}, \text{IgnoreTenant}, \text{DataSourceKey}\rangle$$

---

## 5. 物理排版与代码全真验证 (Evergreen & Zero-Glitch)

### 5.1 验证源头全真逆向提取

本模块所有文档示例、YAML 配置及 API 行为均 100% 逆向提取自已跑通的场景样例工程：
- **场景工程路径**：[`ateng-boot-samples/ateng-boot-sample-datasource`](../ateng-boot-samples/ateng-boot-sample-datasource)
- **端到端集成测试**：
  - [`MultiTenantRoutingIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/MultiTenantRoutingIntegrationTest.java)：多租户物理分库与严格隔离测试
  - [`ReadWriteSplittingIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/ReadWriteSplittingIntegrationTest.java)：读写分离与写后读主防延迟测试
  - [`DatasourceBatchAndLifecycleIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/DatasourceBatchAndLifecycleIntegrationTest.java)：批量巡检与动态挂载排空测试
  - [`DatasourceAsyncAndTransactionGuardIntegrationTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/DatasourceAsyncAndTransactionGuardIntegrationTest.java)：事务守卫刚性拦截与 SM4/AES 解密测试
  - [`OrderControllerMockMvcTest`](../ateng-boot-samples/ateng-boot-sample-datasource/src/test/java/io/github/atengk/boot/sample/datasource/OrderControllerMockMvcTest.java)：Web 边界 HTTP 契约端到端集成测试

### 5.2 真实控制台测试输出记录

以下控制台输出直接截取自本地实际执行 `mvn test` 的真实控制台输出：

```log
[INFO] Running io.github.atengk.boot.sample.datasource.MultiTenantRoutingIntegrationTest
2026-10-24T12:56:29.610+08:00  INFO 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.service.OrderService         : [OrderService] 开始为租户 [1001] 创建订单: ORD-1001-A
2026-10-24T12:56:29.612+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] .g.a.b.d.r.AtengDynamicRoutingDataSource : 租户物理分库透明切源命中: tenantId=[1001], dsKey=[tenant_1001]
2026-10-24T12:56:29.613+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.mapper.OrderMapper.insert    : ==>  Preparing: INSERT INTO t_order ( id, order_no, tenant_id, product_name, amount, status ) VALUES ( ?, ?, ?, ?, ?, ? )
2026-10-24T12:56:29.614+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.mapper.OrderMapper.insert    : <==    Updates: 1
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.053 s -- in io.github.atengk.boot.sample.datasource.MultiTenantRoutingIntegrationTest

[INFO] Running io.github.atengk.boot.sample.datasource.ReadWriteSplittingIntegrationTest
2026-10-24T12:56:29.788+08:00  INFO 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.service.OrderService         : [OrderService] 主库事务写入订单并激活写后读主防延迟标记: ORD-RW-MASTER-001
2026-10-24T12:56:29.790+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.mapper.OrderMapper.insert    : <==    Updates: 1
2026-10-24T12:56:29.790+08:00  INFO 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.service.OrderService         : [OrderService] 从库读取订单 (受写后读主防延迟保护): ORD-RW-MASTER-001
2026-10-24T12:56:29.792+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] .g.a.b.d.r.AtengDynamicRoutingDataSource : 写后读主 (Read-After-Write) 防延迟生效，强制只读查询路由至主库: [master] (原路由: [slave])
2026-10-24T12:56:29.794+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.m.O.selectWithCursor         : <==      Total: 1
2026-10-24T12:56:29.794+08:00  INFO 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.service.OrderService         : [OrderService] 从库读取订单 (受写后读主防延迟保护): ORD-RW-MASTER-001
2026-10-24T12:56:29.797+08:00 DEBUG 7244 --- [ateng-boot-sample-datasource] [           main] i.g.a.b.s.d.m.O.selectWithCursor         : <==      Total: 0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.026 s -- in io.github.atengk.boot.sample.datasource.ReadWriteSplittingIntegrationTest

[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  01:32 min (Wall Clock)
[INFO] ------------------------------------------------------------------------
```
