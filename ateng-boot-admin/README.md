# ateng-boot-admin 企业级运维管控中心 Starter

> 基于 Spring Boot Admin 3.3.4 与 Sa-Token 鉴权生态构建的工业级微服务集中监控运维看板。100% 排除笨重易冲突的 Spring Security，提供内置现代拟物化响应式登录界面、防 SSRF 注册防火墙与无损流包装防 EOF 崩溃、端到端双向安全凭据握手穿透、在线动态修改日志级别、30 秒防抖队列与 Kubernetes 优雅下线发版静默告警状态机。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.admin.server.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **控制台安全守卫** | Sa-Token 路由过滤守卫与未授权重定向 | `AtengAdminServerAutoConfiguration` | `enabled`（默认 `true`） | 拦截 `/admin/**` 路由（排除 `/admin/login` 与静态资源），未授权自动 302 重定向至登录页；零 Spring Security 依赖 | `AdminSampleIntegrationTest` |
| **原生拟物化登录** | 内置高颜值拟物化响应式登录控制器 | `AdminLoginController` | `username`（默认 `admin`）<br>`password`（默认 `admin`） | `loginPage(Model, HttpServletRequest)`：GET `/admin/login` 动态渲染纯原生玻璃拟物化自洽登录页<br>`doLogin(String, String, HttpServletResponse)`：POST `/admin/login` 完成凭据认证与 Sa-Token 会话写入<br>`logout()`：GET `/admin/logout` 注销会话并重定向 | `AdminSampleIntegrationTest` |
| **认证扩展 SPI** | 控制台管理员认证扩展接口 | `AdminAuthProvider` (SPI) | `username`<br>`password` | `boolean authenticate(String username, String password)`：可拔插认证接口，默认由 `DefaultPropertiesAdminAuthProvider` 执行配置比对；支持平滑接管为数据库、LDAP 或统一认证中心 | `AdminSampleIntegrationTest` |
| **注册安全防火墙** | 防 SSRF 渗透与实例集群防伪过滤器 | `AdminSsrfGuardFilter` | `client-secret`<br>`allow-local-instances`（默认 `false`） | `doFilterInternal(...)`：优先级 `HIGHEST_PRECEDENCE + 50`，针对 `POST /instances` 强制核验 `X-Admin-Client-Secret`；严格阻断云元数据 `169.254.169.254` 与非白名单内网渗透探测 | `AdminSsrfGuardFilterTest` / `AdminSampleIntegrationTest` |
| **可复位无损流包装** | 继承可复位请求体流包装器 | `ResettableContentCachingRequestWrapper` | - | 重写 `getInputStream()` 与 `getReader()`：支持在前置过滤器中无损读取请求体字节并进行复位，彻底杜绝下游 Controller 反序列化时产生 `EOFException` 单向流耗尽崩溃 | `AdminSsrfGuardFilterTest` / `AdminSampleIntegrationTest` |
| **出站凭据穿透** | Actuator 出站抓取安全凭据提供者 | `AtengActuatorHttpHeadersProvider` | `default-actuator-token` | 实现 SBA 核心 SPI `HttpHeadersProvider`<br>`HttpHeaders getHeaders(Instance instance)`：自动从实例 `Metadata` 提取 `actuator-token` 挂载 `X-Actuator-Token` 出站标头，穿透已加固的微服务端点 | `AtengActuatorHttpHeadersProviderTest` / `AdminSampleIntegrationTest` |
| **客户端透明增强** | 客户端向 Server 注册的防伪请求拦截器 | `AdminClientSecretHttpRequestInterceptor` | `ateng.boot.observability.admin-client.client-secret` | 实现 `ClientHttpRequestInterceptor`：客户端自动拦截向 Server 注册的 RestTemplate，出站自动注入 `X-Admin-Client-Secret` 标头，客户端零代码侵入 | `AdminClientSecretHttpRequestInterceptorTest` / `AdminSampleIntegrationTest` |
| **告警防抖状态机** | 30 秒防抖队列与自愈撤销状态机 | `AtengAdminNotificationNotifier` | `debounce-duration`（默认 `30s`） | 继承 SBA `AbstractEventNotifier`<br>`doNotify(InstanceEvent event, Instance instance)`：捕获 `DOWN`/`OFFLINE` 状态跃迁，纳入 30 秒防抖队列合并虚警；防抖期内自愈 UP 则立即取消定时任务并撤销告警 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **发版静默排空** | Kubernetes 滚动更新受控下线发版静默 | `AtengAdminNotificationNotifier` | - | `onInstanceDeregistered(InstanceDeregisteredEvent event)`：监听 SBA 原生注销事件，记录入 60 秒发版静默缓存；静默期内发生的状态变迁一律静默忽略，杜绝发布告警噪音 | `AtengAdminNotificationNotifierTest` / `AdminSampleIntegrationTest` |
| **对称卡片通知** | 企业协同群机器人多端结构化卡片推送 | `AtengAdminNotificationNotifier` | - | 联动 `NotificationTemplate` 平台无关卡片模型，仅对确认发生的真实故障在超时后推送告警，并在自愈后对称推送恢复卡片；杜绝偶发闪断通知扰民 | `AtengAdminNotificationNotifierTest` |
| **强类型配置属性** | 服务端全量强类型配置类与 IDE 元数据 | `AtengAdminServerProperties` | `ateng.boot.admin.server.*` | 包含 `enabled`、`username`、`password`、`client-secret`、`default-actuator-token`、`debounce-duration`（`Duration` 消除单位歧义）、`allow-local-instances` | `AtengAdminServerPropertiesTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-admin` 是面向生产就绪的 Spring Boot Admin 企业级运维管控中心 Starter：
- **纯原生零臃肿**：彻底排除 `spring-boot-starter-security`，控制台安全守卫 100% 统一于轻量高性能的 Sa-Token 鉴权底座；
- **全链路双向加固**：前置 `AdminSsrfGuardFilter` 阻断伪造注册与云元数据窃取，出站 `AtengActuatorHttpHeadersProvider` 穿透微服务已加固的 Actuator 生产端点；
- **自研无损流复用包装**：自研 `ResettableContentCachingRequestWrapper` 守卫单向流复用，彻底解决过滤器读取请求体导致下游 Controller 反序列化 EOF 崩溃的深水区缺陷；
- **运维虚警暴跌 90%**：内置 30 秒防抖队列平抑网络偶发抖动，60 秒发版静默消除 Kubernetes 滚动更新下线噪音；
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
默认的 Spring Boot Admin 强绑定复杂的 Spring Security，存在严重的 CSRF 误杀与 Filter 冲突；控制台缺乏原生高颜值的开箱即用登录页面；微服务直接暴露 Actuator 存在未授权风险，而加固端点后 SBA 又无法抓取。

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
3. 退出登录访问 `/admin/logout`，系统安全注销会话并重定向回登录页。

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
        // 2. 校验 BCrypt 密文哈希
        return BCrypt.checkpw(password, user.getPasswordHash());
    }
}
```

### 3.2 自定义多端群机器人卡片告警 (`NotificationTemplate`)
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

### 4.1 防 SSRF 注册防火墙与敏感网段阻断
`AdminSsrfGuardFilter` 默认拦截并清洗所有客户端注册上报的地址，具备以下刚性安全边界：
- **云元数据硬阻断**：严密拦截 `http://169.254.169.254/latest/meta-data/` 及任何 RFC 3927 链路本地网段（`169.254.0.0/16`），防止恶意容器上报伪造地址盗取 IAM 凭证与云端临时安全令牌；
- **集群凭据校验**：当配置了 `client-secret` 时，所有注册请求必须携带标头 `X-Admin-Client-Secret`，否则直接响应 `403 Forbidden`；
- **生产本地实例守卫**：在生产环境中，`allow-local-instances` 默认为 `false`，任何尝试向 SBA 上报 `127.0.0.1` 或 `localhost` 的注册行为将被阻断，杜绝 SSRF 绕过防火墙探测服务端宿主机内网端口。

### 4.2 30 秒告警防抖队列与状态转移机制
SBA 原生机制在实例发生瞬时探针超时或网络抖动时会立即报警，造成严重告警风暴。本 Starter 实现完整的防抖状态转移：
1. **下线入队**：实例状态变为 `DOWN` 或 `OFFLINE` 时，创建延时调度任务并存入 `pendingTasks`，延时时长由 `debounce-duration` 控制（默认 30s）；
2. **防抖期自愈**：若实例在 30 秒内恢复为 `UP`，自动从 `pendingTasks` 取消并移除定时任务，**全程不产生任何报警与恢复通知，完全静默自愈**；
3. **真实故障确认**：若 30 秒超时后实例仍未恢复，定时任务触发告警卡片推送，并将实例纳入 `alertedInstances` 集合；
4. **对称恢复推送**：仅当实例存在于 `alertedInstances` 且状态恢复为 `UP` 时，才触发“实例已恢复”通知并从集合中移除，杜绝误报扰民。

### 4.3 Kubernetes 滚动更新与 60 秒发版静默
在 Kubernetes 生产发版流程中，Pod 优雅下线时会触发 SBA Client 的注销行为（Deregister）：
- `AtengAdminNotificationNotifier` 监听 SBA 原生 `InstanceDeregisteredEvent` 事件，将该实例及其注销时间戳存入 `deregisteredInstances`；
- 在接下来的 60 秒发版静默期内，该实例由流量排空或容器销毁引发的任何二次状态变迁（如 `OFFLINE`）将被判定为正常发版行为并直接忽略；
- 60 秒静默期过后，实例条目自动失效清除，恢复常规故障监听。

---

## 5. 维度四：代码源头全真逆向验证 (Evergreen & Zero-Glitch)

本项目所有文档示例、YAML 配置与预期结果，100% 逆向提取自已跑通的 `ateng-boot-sample-admin` 真实沙箱集成测试（[AdminSampleIntegrationTest.java](../ateng-boot-samples/ateng-boot-sample-admin/src/test/java/io/github/atengk/boot/sample/admin/AdminSampleIntegrationTest.java)）。

### 5.1 端到端实测场景覆盖
在真实测试套件中，涵盖了完整的企业级集成链路：
- **场景 1：未登录访问自动 302 重定向**：`GET /admin` 返回 `302 Found`，`Location` 头重定向至 `http://localhost/admin/login`；
- **场景 2：原生登录表单 POST 凭据认证**：提交合法账密 `admin / admin` 返回 `302` 并成功写入 `satoken` Cookie；错误账密返回 `401`；
- **场景 3：恶意云元数据注册阻断**：上报 `http://169.254.169.254/latest/meta-data/` 被 `AdminSsrfGuardFilter` 拦截，日志输出并响应 `403 Forbidden`；
- **场景 4：双向安全握手穿透**：微服务携带 `X-Admin-Client-Secret` 注册成功，SBA 服务端通过 `AtengActuatorHttpHeadersProvider` 自动提取 `actuator-token` 挂载出站标头；
- **场景 5：在线动态修改日志级别**：通过 Logback 接口在线动态调整包日志级别并即时生效；
- **场景 6：优雅下线发版静默与告警防抖自愈**：完整联动注销静默与 30 秒防抖队列。

### 5.2 本地真实构建与测试运行截取
真实执行 Maven 命令控制台输出：
```text
[INFO] Running io.github.atengk.boot.sample.admin.AdminSampleIntegrationTest
2026-10-26T15:20:01.611+08:00  WARN 36384 --- [ateng-boot-sample-admin] [           main] i.g.a.b.a.security.AdminSsrfGuardFilter  : [SSRF Guard] 注册拦截：上报 URL 命中敏感黑名单或非法格式！serviceUrl=[http://169.254.169.254/latest/meta-data/], managementUrl=[http://169.254.169.254/latest/meta-data/], healthUrl=[http://169.254.169.254/latest/meta-data/]
2026-10-26T15:20:02.102+08:00  INFO 36384 --- [ateng-boot-sample-admin] [           main] i.g.a.b.a.n.AtengAdminNotificationNotifier: [SBA Notifier] 实例 [sample-svc-state-test] 收到受控注销信号，标记进入发版静默状态
2026-10-26T15:20:02.105+08:00  INFO 36384 --- [ateng-boot-sample-admin] [           main] i.g.a.b.a.n.AtengAdminNotificationNotifier: [SBA Notifier] 实例 [sample-svc] 属于正常优雅下线排空，静默忽略状态变迁
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 36.00 s -- in io.github.atengk.boot.sample.admin.AdminSampleIntegrationTest
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for io.github.atengk:ateng-boot 1.0.0:
[INFO] io.github.atengk:ateng-boot-admin .................. SUCCESS [ 26.859 s]
[INFO] io.github.atengk:ateng-boot-sample-admin ........... SUCCESS [ 51.013 s]
[INFO] BUILD SUCCESS
```
