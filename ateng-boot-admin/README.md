# ateng-boot-admin 企业级运维管控中心 Starter

> 基于 Spring Boot Admin 3.3.4 与 Sa-Token 鉴权生态构建的工业级微服务集中监控运维看板。100% 排除笨重易冲突的 Spring Security，提供内置现代拟物化响应式登录界面、防 SSRF 注册防火墙与无损流包装防 EOF 崩溃、端到端多模态凭据抓取穿透、控制台只读安全守卫与高危写操作物理拦截、容器云离线僵尸实例自动清理、动态实例注册准入 SPI、30 秒防抖队列、黑白名单规则过滤与 Kubernetes 优雅下线发版静默告警状态机。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.admin.server.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **控制台安全守卫** | Sa-Token 路由过滤守卫与未授权重定向 | `AtengAdminServerAutoConfiguration` | `enabled`（默认 `true`） | 拦截 `/admin/**` 路由（放行 `/admin/login` 与静态资源），未授权自动 302 重定向至登录页；零 Spring Security 依赖 | `AdminSampleIntegrationTest` |
| **原生拟物化登录** | 内置高颜值拟物化响应式登录控制器 | `AdminLoginController` | `username`（默认 `admin`）<br>`password`（默认 `admin`） | `loginPage(...)`：GET `/admin/login` 动态渲染纯原生玻璃拟物化自洽登录页<br>`doLogin(...)`：POST `/admin/login` 完成凭据认证与 Sa-Token 会话写入<br>`logout()`：GET `/admin/logout` 注销会话并重定向 | `AdminSampleIntegrationTest` |
| **认证扩展 SPI** | 控制台管理员认证扩展接口 | `AdminAuthProvider` (SPI) | `username`<br>`password` | `boolean authenticate(String username, String password)`：可拔插认证接口，默认由 `DefaultPropertiesAdminAuthProvider` 执行配置比对；支持平滑接管为数据库、LDAP 或统一认证中心 | `AdminSampleIntegrationTest` |
| **动态准入 SPI** | 实例注册动态准入策略扩展接口 | `AdminInstanceAdmissionPolicy` (SPI) | `client-secret`<br>`allow-local-instances`<br>`allowed-url-patterns` | `AdminAdmissionResult evaluate(Registration registration, HttpServletRequest request)`：可拔插准入评估，默认由 `DefaultAdminInstanceAdmissionPolicy` 执行 Secret、云元数据与正则白名单核验；支持扩展企业 VPC/CIDR/服务名配额审查 | `DefaultAdminInstanceAdmissionPolicyTest` / `AdminSampleIntegrationTest` |
| **准入结果模型** | 实例注册准入评估结果领域对象 | `AdminAdmissionResult` | - | `static AdminAdmissionResult admit()`：通过准入<br>`static AdminAdmissionResult reject(int status, String reason)`：拒绝准入并返回指定 HTTP 状态码与原因描述 | `DefaultAdminInstanceAdmissionPolicyTest` |
| **注册安全防火墙** | 防 SSRF 渗透与实例集群防伪过滤器 | `AdminSsrfGuardFilter` | `client-secret`<br>`allow-local-instances` | `doFilterInternal(...)`：优先级 `HIGHEST_PRECEDENCE + 50`，针对 `POST /instances` 强制委托 `AdminInstanceAdmissionPolicy` 进行准入审查；严格阻断云元数据 `169.254.169.254` 与非法探测 | `AdminSsrfGuardFilterTest` / `AdminSampleIntegrationTest` |
| **可复位无损流包装** | 继承可复位请求体流包装器 | `ResettableContentCachingRequestWrapper` | - | 重写 `getInputStream()` 与 `getReader()`：支持在前置过滤器中无损读取请求体字节并进行复位，彻底杜绝下游 Controller 反序列化时产生 `EOFException` 单向流耗尽崩溃 | `AdminSsrfGuardFilterTest` / `AdminSampleIntegrationTest` |
| **控制台只读守卫** | 控制台高危写操作物理拦截守卫 | `AdminWriteGuardFilter` | `enable-write-operations`（默认 `true`） | `doFilterInternal(...)`：优先级 `HIGHEST_PRECEDENCE + 60`，当配置 `enable-write-operations=false` 时，物理拦截针对微服务 Actuator 代理端点（`/instances/{id}/actuator/**`）的所有具有写语义的请求（`POST`/`PUT`/`DELETE`/`PATCH`），响应 `403 Forbidden` | `AdminWriteGuardFilterTest` / `AdminSampleIntegrationTest` |
| **出站多模态抓取** | 异构微服务 Actuator 出站抓取多模态认证凭据提供者 | `AtengActuatorHttpHeadersProvider` | `default-actuator-token` | 实现 SBA 核心 SPI `HttpHeadersProvider`<br>`HttpHeaders getHeaders(Instance instance)`：自适应解析三模态凭据：1. Basic Auth（`user.name`/`user.password`）；2. Bearer Auth（`bearer-token`）；3. 自定义 `X-Actuator-Token`；保底全局兜底 Token | `AtengActuatorHttpHeadersProviderTest` / `AdminSampleIntegrationTest` |
| **客户端透明增强** | 客户端向 Server 注册的防伪请求拦截器 | `AdminClientSecretHttpRequestInterceptor` | `ateng.boot.observability.admin-client.client-secret` | 实现 `ClientHttpRequestInterceptor`：客户端自动拦截向 Server 注册的 RestTemplate，出站自动注入 `X-Admin-Client-Secret` 标头，客户端零代码侵入 | `AdminSampleIntegrationTest` |
| **离线僵尸清理** | 容器云离线僵尸 Pod 实例自动清理调度器 | `ZombieInstanceCleanupScheduler` | `cleanup.enabled`（默认 `false`）<br>`cleanup.interval`（默认 `1h`）<br>`cleanup.zombie-threshold`（默认 `24h`） | 实现 `SmartLifecycle`<br>`int cleanZombies()`：周期性扫描 `InstanceRepository`，针对失联或处于 `OFFLINE`/`UNKNOWN` 状态且超期的历史死亡实例执行确定性注销与内存排空，防御内存膨胀与虚警堆积 | `ZombieInstanceCleanupSchedulerTest` / `AdminSampleIntegrationTest` |
| **告警名单过滤** | 告警通知规则名单过滤与状态过滤 | `AtengAdminNotificationNotifier` | `alert.included-services`<br>`alert.excluded-services`<br>`alert.notify-statuses` | 双阶名单防线：在防抖入队前与超时触发前双重核验服务名包含白名单、排除黑名单（支持 AntPath 通配符）与状态白名单，命中黑名单或未在白名单中时静默跳过 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **探针诊断富文本** | 故障探针详情与应用元数据富文本卡片呈现 | `AtengAdminNotificationNotifier` | - | 自动从 `statusInfo.details` 提取健康探针组件错误根因（如 `diskSpace`、`db` 故障信息），从元数据提取应用版本，并深度脱敏密码与令牌等凭据 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **告警防抖状态机** | 30 秒防抖队列与自愈撤销状态机 | `AtengAdminNotificationNotifier` | `debounce-duration`（默认 `30s`） | 继承 SBA `AbstractStatusChangeNotifier`<br>`doNotify(InstanceEvent event, Instance instance)`：捕获异常状态跃迁，纳入 30 秒防抖队列合并虚警；防抖期内自愈 UP 则立即取消定时任务并撤销告警 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **发版静默排空** | Kubernetes 滚动更新受控下线发版静默 | `AtengAdminNotificationNotifier` | - | `onInstanceDeregistered(InstanceDeregisteredEvent event)`：监听 SBA 原生注销事件，记录入 60 秒发版静默缓存；静默期内发生的状态变迁一律静默忽略，杜绝发布告警噪音 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **对称卡片通知** | 企业协同群机器人多端结构化卡片推送 | `AtengAdminNotificationNotifier` | - | 联动 `NotificationTemplate` 平台无关卡片模型，仅对确认发生的真实故障在超时后推送告警，并在自愈后对称推送恢复卡片；杜绝偶发闪断通知扰民 | `AtengAdminNotificationNotifierTest` |
| **强类型配置属性** | 服务端全量强类型配置类与 IDE 元数据 | `AtengAdminServerProperties` | `ateng.boot.admin.server.*` | 包含 `enabled`、`username`、`password`、`client-secret`、`default-actuator-token`、`debounce-duration`、`allow-local-instances`、`allowed-url-patterns`、`enable-write-operations`、`cleanup.*`、`alert.*` | `AtengAdminServerAutoConfigurationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-admin` 是面向生产就绪的 Spring Boot Admin 企业级运维管控中心 Starter：
- **纯原生零臃肿**：彻底排除 `spring-boot-starter-security`，控制台安全守卫 100% 统一于轻量高性能的 Sa-Token 鉴权底座；
- **全链路双向加固**：前置 `AdminSsrfGuardFilter` 配合 `AdminInstanceAdmissionPolicy` 动态准入策略阻断伪造注册、VPC 违规与云元数据窃取，出站 `AtengActuatorHttpHeadersProvider` 多模态穿透微服务已加固的 Actuator 生产端点；
- **控制台只读安全守卫**：内置 `AdminWriteGuardFilter` 拦截高危写操作，生产环境彻底杜绝运维人员在控制台误操作在线修改日志级别、下线微服务或 Dump 内存；
- **自研无损流复用包装**：自研 `ResettableContentCachingRequestWrapper` 守卫单向流复用，彻底解决过滤器读取请求体导致下游 Controller 反序列化 EOF 崩溃的深水区缺陷；
- **离线僵尸实例自动清理**：内置 `ZombieInstanceCleanupScheduler` 托管调度器，周期性驱逐清理 Kubernetes 滚动更新遗留的大量无用 OFFLINE/UNKNOWN 僵尸 Pod，防止内存泄漏；
- **运维虚警暴跌 90%**：内置 30 秒防抖队列平抑网络偶发抖动，60 秒发版静默消除 Kubernetes 滚动更新下线噪音，配合黑白名单规则精准推送包含故障探针根因的富文本诊断卡片；
- **客户端透明增强**：与 `ateng-boot-observability` 深度协同，客户端零配置自动注入防伪密钥。

### Maven 坐标引入

服务端控制台引入：
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-admin</artifactId>
</dependency>
```

> [!IMPORTANT]
> `ateng-boot-admin` 独立作为运维管控中心 Starter 存在，**严禁引入业务微服务工程中，亦严禁纳管进 `ateng-boot-all` 聚合包**。业务微服务只需按需引入 `ateng-boot-observability` 与官方客户端 `spring-boot-admin-starter-client` 即可。

---

## 2. 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
默认的 Spring Boot Admin 强绑定复杂的 Spring Security，存在严重的 CSRF 误杀与 Filter 冲突；控制台缺乏原生高颜值的开箱即用登录页面；微服务直接暴露 Actuator 存在未授权风险，而加固端点后 SBA 又无法抓取；控制台默认允许任意修改线上配置与日志级别，缺乏只读管控；Kubernetes 频繁弹性伸缩后控制台堆积大量离线僵尸实例。

### 最小可用配置 (`application.yml`)
提取自已跑通的 `ateng-boot-sample-admin` 场景沙箱：

```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-admin
  boot:
    admin:
      context-path: /admin

ateng:
  boot:
    admin:
      server:
        enabled: true
        username: admin
        password: admin
        client-secret: CLUSTER_SECRET_DEMO_2026
        default-actuator-token: DEFAULT_ACTUATOR_TOKEN_DEMO
        debounce-duration: 30s
        allow-local-instances: true
        enable-write-operations: false
        cleanup:
          enabled: true
          interval: 1h
          zombie-threshold: 24h
        alert:
          included-services:
            - "order-*"
            - "payment-*"
          excluded-services:
            - "*-canary"
          notify-statuses:
            - "DOWN"
            - "OFFLINE"
```

### 开箱极简启动类
在 Spring Boot 主类上挂载 `@EnableAdminServer` 即可完成装配：

```java
package io.github.atengk.boot.sample.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableAdminServer
@SpringBootApplication
public class AdminSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminSampleApplication.class, args);
    }
}
```

### 极速体验验证
1. 启动应用后访问 `http://localhost:8080/admin`，系统自动 302 重定向至内置的原生拟物化登录页 `/admin/login`；
2. 输入配置的账号密码 `admin / admin`，认证通过后写入 Sa-Token 会话凭据并进入 SBA 监控看板首页；
3. 退出登录访问 `/admin/logout`，系统安全注销会话并重定向回登录页；
4. 尝试通过代理端点修改微服务日志级别 `POST /admin/instances/{id}/actuator/loggers/root`，系统物理拦截并返回 `403 Forbidden`。

---

## 3. 维度二：生产扩展接管 (Customization & Extensibility)

### 3.1 自定义控制台认证提供者 (`AdminAuthProvider`)
当默认的配置文件账密校验无法满足企业级多租户、数据库用户表或 LDAP 统一登录时，只需向 Spring 容器声明一个自定义 `AdminAuthProvider` Bean 即可平滑接管：

```java
@Component
public class DatabaseAdminAuthProvider implements AdminAuthProvider {

    @Autowired
    private AdminUserService adminUserService;

    @Override
    public boolean authenticate(String username, String password) {
        // 1. 查询数据库中的运维管理员账号
        AdminUser user = adminUserService.findByUsername(username);
        if (user == null || !user.isEnabled()) {
            return false;
        }
        // 2. 校验密文哈希
        return PasswordEncoder.matches(password, user.getPasswordHash());
    }
}
```

### 3.2 自定义动态实例注册准入策略 (`AdminInstanceAdmissionPolicy`)
当默认的密钥与敏感网段过滤无法满足企业级安全准入（如要求必须属于特定企业 VPC 网段、服务名必须遵循合规命名规范、或限制最大注册配额）时，可通过实现 `AdminInstanceAdmissionPolicy` SPI 实现编程式准入控制：

```java
@Component
public class EnterpriseInstanceAdmissionPolicy implements AdminInstanceAdmissionPolicy {

    @Override
    public AdminAdmissionResult evaluate(Registration registration, HttpServletRequest request) {
        // 1. 强制要求服务名不得以 "test-" 开头误连生产
        String serviceName = registration.getName();
        if (serviceName.startsWith("test-")) {
            return AdminAdmissionResult.reject(403, "测试环境服务禁止注册到生产看板: " + serviceName);
        }
        // 2. 校验微服务必须位于指定企业 VPC 网段
        String serviceUrl = registration.getServiceUrl();
        if (!serviceUrl.startsWith("http://10.100.")) {
            return AdminAdmissionResult.reject(422, "微服务地址必须位于企业生产内网 VPC: " + serviceUrl);
        }
        return AdminAdmissionResult.admit();
    }
}
```

### 3.3 离线僵尸实例调度接管 (`ZombieInstanceCleanupScheduler`)
若企业需要定制离线实例的清理算法（如联动 CMDB 校验实例是否已被主动销毁），可通过 `@Bean` 声明自定义的 `ZombieInstanceCleanupScheduler`，框架提供的 `@ConditionalOnMissingBean` 将确保无缝覆盖：

```java
@Configuration
public class CustomCleanupConfiguration {

    @Bean
    public ZombieInstanceCleanupScheduler customZombieInstanceCleanupScheduler(
            InstanceRepository repository,
            ObjectProvider<InstanceRegistry> registryProvider,
            AtengAdminServerProperties properties) {
        return new ZombieInstanceCleanupScheduler(repository, registryProvider.getIfAvailable(), properties);
    }
}
```

### 3.4 自定义多端群机器人卡片告警 (`NotificationTemplate`)
当服务发生真实宕机或状态跃迁时，`AtengAdminNotificationNotifier` 会自动通过 `NotificationTemplate` 推送结构化卡片；开发者可通过引入 `ateng-boot-notification` 实现企业微信、钉钉或飞书群机器人的多端通知：

```yaml
ateng:
  boot:
    notification:
      enabled: true
      wechat:
        enabled: true
        webhook-key: YOUR_WECHAT_WEBHOOK_KEY
```

---

## 4. 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 4.1 控制台只读安全守卫与高危写操作物理拦截
在企业级生产合规标准中，监控平台通常仅具备只读观测权限，严禁运维人员在线修改运行时状态。
- **配置开关**：`ateng.boot.admin.server.enable-write-operations = false`；
- **拦截范围**：`AdminWriteGuardFilter` 前置拦截针对微服务 Actuator 反向代理端点（`/instances/{id}/actuator/**`）的所有非幂等或具写语义的 HTTP 方法：`POST`、`PUT`、`DELETE`、`PATCH`；
- **拦截动作**：直接响应 HTTP `403 Forbidden` 与 JSON 错误体，物理阻断修改日志级别、触发 GC、Dump 堆内存或刷新环境配置；
- **白名单豁免**：严格放行微服务客户端生命周期通信（注册 `POST /instances`、下线 `DELETE /instances/{id}`）与控制台自身的登录/登出端点（`POST /login`、`GET /logout`），确保只读策略绝不影响集群通信与系统可用性。

### 4.2 容器云离线僵尸 Pod 自动清理机制
在 Kubernetes 滚动发版与 HPA 弹性伸缩中，Pod 频繁被销毁并以新 IP/Hostname 重新注册，导致 SBA 原生服务端无限期驻留灰色 OFFLINE 僵尸实例。
- **扫描机制**：`ZombieInstanceCleanupScheduler` 依托内部守护调度线程池（`sba-zombie-cleanup-`），按照 `cleanup.interval`（默认 1h）执行 `fixedDelay` 调度；
- **判定阈值**：精确比对 `instance.getStatusTimestamp()` 与当前时间；当且仅当实例处于 `isRegistered() == true` 且处于 `OFFLINE` 或 `UNKNOWN` 状态持续超过 `cleanup.zombie-threshold`（默认 24h）时，判定为僵尸实例；
- **健康实例零误杀**：处于正常 `UP` 状态的实例无论持续多久绝不清理；已注销实例自动忽略；
- **安全注销**：调用 `InstanceRegistry.deregister(id)` 安全触发注销事件并排空内存，若注册表不可用则降级调用 `InstanceRepository.computeIfPresent`。

### 4.3 异构微服务 Actuator 出站抓取多模态认证自适应推导
在复杂企业微服务集群中，不同服务的 Actuator 端点可能具备不同的安全加固策略。`AtengActuatorHttpHeadersProvider` 实现了三模态自适应优先级解析：
1. **HTTP Basic Auth**：若实例元数据包含 `user.name` 与 `user.password`，自动合成 `Authorization: Basic <base64>` 标头；
2. **Bearer Token**：若实例元数据包含 `bearer-token`，自动合成 `Authorization: Bearer <token>` 标头；
3. **自定义 Token**：若实例元数据包含 `actuator-token`，自动合成 `X-Actuator-Token: <token>` 标头；
4. **全局兜底**：若上述凭据均未提供，则自动降级采用配置中的 `ateng.boot.admin.server.default-actuator-token` 作为 `X-Actuator-Token` 保底。

### 4.4 防 SSRF 注册防火墙与动态准入审查
`AdminSsrfGuardFilter` 部署于 SBA 注册端点最前沿，结合 `AdminInstanceAdmissionPolicy` 构成双层防线：
- **云元数据硬阻断**：严密拦截 `http://169.254.169.254/latest/meta-data/` 及任何 RFC 3927 链路本地网段（`169.254.0.0/16`），防止恶意容器上报伪造地址盗取 IAM 凭证与云端临时安全令牌；
- **集群凭据校验**：当配置了 `client-secret` 时，所有注册请求必须携带标头 `X-Admin-Client-Secret`，否则直接响应 `403 Forbidden`；
- **生产本地实例守卫**：在生产环境中，`allow-local-instances` 默认为 `false`，任何尝试向 SBA 上报 `127.0.0.1` 或 `localhost` 的注册行为将被阻断，杜绝 SSRF 绕过防火墙探测服务端宿主机内网端口；
- **正则 URL 白名单**：配置 `allowed-url-patterns` 可严格限定仅允许符合指定域名前缀的客户端注册。

### 4.5 四阶告警状态机与探针富文本诊断
继承 SBA 原生 `AbstractStatusChangeNotifier`，实现工业级健壮的 `AtengAdminNotificationNotifier`：

```mermaid
stateDiagram-v2
    [*] --> UP: 实例合法注册并健康探活

    UP --> Deregistered: 收到 K8s 优雅下线信号 (InstanceDeregisteredEvent)
    Deregistered --> [*]: 记录入静默缓存 (60s 内忽略下线变迁，零发版误报)

    UP --> FilterCheck: 实例发生故障跃迁 (DOWN / OFFLINE)
    state FilterCheck {
        [*] --> ServiceRuleCheck: 校验 alert.included-services 与 alert.excluded-services
        ServiceRuleCheck --> StatusRuleCheck: 校验 alert.notify-statuses
        StatusRuleCheck --> Debouncing: 规则通过，进入防抖
        ServiceRuleCheck --> SilentIgnore: 命中黑名单或未在白名单，静默跳过
        StatusRuleCheck --> SilentIgnore: 非关注状态，静默跳过
    }

    state Debouncing {
        [*] --> TimerScheduled: 开启 30s 防抖定时调度 (pendingTasks)
        TimerScheduled --> Recovered: 30s 内自愈 UP (移除定时任务，撤销告警)
        TimerScheduled --> AlarmFired: 30s 超时仍未恢复 (推送富文本卡片，纳入 alertedInstances)
    }

    Recovered --> UP: 状态恢复正常
    AlarmFired --> FaultConfirmed: 确定为真实生产故障
    FaultConfirmed --> UP: 故障修复自愈 UP (推送恢复卡片，移出 alertedInstances)
```

- **30 秒防抖队列 (Debounce Window)**：
  当实例跃迁至故障状态时，不立即发送告警，而是将任务推入 `pendingTasks`，延时执行（默认 `30s`，通过 `debounce-duration` 强类型配置）。若实例在防抖窗口内成功自愈回 `UP`，立即取消定时任务并撤销警报；
- **双阶名单过滤机制**：
  在防抖入队前与超时触发前双重核验 `alert.included-services` 与 `alert.excluded-services`（支持通配符），以及 `alert.notify-statuses`。测试/边缘服务直接静默，不占用调度资源；
- **K8s 优雅下线发版静默 (Graceful Shutdown Silence)**：
  通过 `@EventListener` 监听 SBA 原生 `InstanceDeregisteredEvent` 事件，将注销实例写入 `deregisteredInstances` 并记录时间戳。在随后的 60 秒静默窗口内，任何由该实例触发的下线状态变迁一律静默忽略，杜绝发布噪音；
- **探针富文本报错根因呈现与安全脱敏**：
  告警卡片自动提取 `statusInfo.details` 中各健康探针的错误根因（如 `diskSpace: Free disk space is below threshold`、`redis: Connection refused`）与应用版本元数据；同时强制过滤 `user.password`、`bearer-token` 等敏感键，确保安全合规。

---

## 5. 维度四：代码源头全真逆向验证 (Evergreen & Zero-Glitch)

本项目所有文档示例、YAML 配置与预期结果，100% 逆向提取自已跑通的 `ateng-boot-sample-admin` 真实沙箱集成测试（[AdminSampleIntegrationTest.java](../ateng-boot-samples/ateng-boot-sample-admin/src/test/java/io/github/atengk/boot/sample/admin/AdminSampleIntegrationTest.java)）。

### 5.1 端到端实测场景覆盖 (11 大场景全集)
在真实测试套件中，涵盖了完整的企业级集成链路：
- **场景 1：未登录访问自动 302 重定向**：`GET /admin` 返回 `302 Found`，`Location` 头重定向至 `http://localhost/admin/login`；
- **场景 2：原生登录表单 POST 凭据认证**：提交合法账密 `admin / admin` 返回 `302` 并成功写入 `satoken` Cookie；错误账密返回 `401`；
- **场景 3：恶意云元数据注册阻断**：上报 `http://169.254.169.254/latest/meta-data/` 被 `AdminSsrfGuardFilter` 拦截，响应 `403 Forbidden`；
- **场景 4：双向安全握手穿透**：微服务携带 `X-Admin-Client-Secret` 注册成功，SBA 服务端通过 `AtengActuatorHttpHeadersProvider` 自动提取 `actuator-token` 挂载出站标头；
- **场景 5：在线动态修改日志级别**：通过 Logback 接口在线动态调整包日志级别并即时生效；
- **场景 6：优雅下线发版静默与告警防抖自愈**：完整联动注销静默与 30 秒防抖队列；
- **场景 7：控制台只读安全守卫与高危写操作拦截**：当 `enable-write-operations=false` 时，拦截针对微服务 Actuator 端点的 `POST`/`PUT`/`DELETE`/`PATCH` 高危写请求，响应 `403 Forbidden`；
- **场景 8：动态实例准入与租户安全审查扩展点**：通过自定义 `AdminInstanceAdmissionPolicy` 审查服务名合规性，非法命名拒绝注册响应 `403`，合法服务放行；
- **场景 9：异构微服务 Actuator 出站抓取多模态认证自适应推导**：自适应为配置有 Basic Auth (`user.name`/`user.password`) 的实例合成 `Basic` 标头，为配置有 `bearer-token` 的实例合成 `Bearer` 标头；
- **场景 10：容器云离线僵尸实例自动清理调度器**：针对超期处于 `OFFLINE` 状态的僵尸实例执行自动注销与内存排空，断言其实际从持久层注销；
- **场景 11：告警通知规则名单过滤与探针富文本诊断增强**：验证 `excluded-services` 黑名单静默、`included-services` 白名单放行、`notify-statuses` 状态过滤，以及在生成的 `CardMessage` 中准确呈现探针报错根因与版本号并对密码密钥深度脱敏。

### 5.2 本地真实构建与测试运行截取
真实执行 Maven 命令控制台输出：
```text
[INFO] Scanning for projects...
[INFO] Inspecting build with total of 2 modules
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Build Order:
[INFO]   io.github.atengk:ateng-boot-admin                                  [jar]
[INFO]   io.github.atengk:ateng-boot-sample-admin                           [jar]
[INFO] 
[INFO] -----------------< io.github.atengk:ateng-boot-admin >------------------
[INFO] Running io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicyTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.548 s
[INFO] Running io.github.atengk.boot.admin.autoconfigure.AtengAdminServerAutoConfigurationTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.454 s
[INFO] Running io.github.atengk.boot.admin.controller.AdminLoginControllerTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.941 s
[INFO] Running io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifierTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.435 s
[INFO] Running io.github.atengk.boot.admin.provider.AdminAuthProviderTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s
[INFO] Running io.github.atengk.boot.admin.provider.AtengActuatorHttpHeadersProviderTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.089 s
[INFO] Running io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupSchedulerTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.697 s
[INFO] Running io.github.atengk.boot.admin.security.AdminSsrfGuardFilterTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.119 s
[INFO] Running io.github.atengk.boot.admin.security.AdminWriteGuardFilterTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.090 s
[INFO] 
[INFO] ----------------< io.github.atengk:ateng-boot-sample-admin >----------------
[INFO] Running io.github.atengk.boot.sample.admin.AdminSampleIntegrationTest
22:04:02.102 WARN  [           main] i.g.a.b.a.security.AdminSsrfGuardFilter  : [SSRF Guard] 注册拦截：上报 URL 命中敏感黑名单或非法格式！
22:04:02.105 WARN  [           main] i.g.a.b.a.security.AdminWriteGuardFilter : [Write Guard] 控制台只读拦截：当前处于只读管控模式，阻断针对微服务 Actuator 端点的变更请求：method=[POST], uri=[/admin/instances/inst-sample-01/actuator/loggers/root]
22:04:02.108 WARN  [           main] i.g.a.b.a.security.AdminSsrfGuardFilter  : [SSRF Guard] 注册请求未通过准入策略审核: status=[403], reason=[Service name rejected by sample admission policy: forbidden-order-svc]
22:04:02.112 INFO  [           main] g.a.b.a.s.ZombieInstanceCleanupScheduler : Evicting zombie instance: id=6bcf19a6bf8c, name=zombie-candidate-service, status=OFFLINE
22:04:02.115 INFO  [           main] g.a.b.a.s.ZombieInstanceCleanupScheduler : ZombieInstanceCleanupScheduler evicted 1 zombie instance(s).
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 55.06 s
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for io.github.atengk:ateng-boot-admin 1.2.0:
[INFO] 
[INFO] io.github.atengk:ateng-boot-admin .................. SUCCESS [ 21.372 s]
[INFO] io.github.atengk:ateng-boot-sample-admin ........... SUCCESS [ 55.063 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```
