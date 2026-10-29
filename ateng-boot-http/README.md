# ateng-boot-http 基础设施 Starter

> 依托 Spring 6 原生 `RestClient`、`HttpServiceProxyFactory` 与 JDK 17 构建的零臃肿双模 HTTP 客户端底座，统一融合命令式调用（`HttpTemplate`）与强类型声明式代理（`@EnableHttpInterfaces` / `@HttpClient`），提供 Apache HttpClient 5 生产级池化连接与 JDK 17 原生 Client 自适应平滑降级、多命名客户端注册表（`RestClientRegistry`）、四维全链路上下文出站透传与外部公网敏感头安全剥离、RFC 3986 查询参数安全转译与动态单次超时覆盖、cURL 结构化脱敏日志、幂等动词指数退避重试及语义化异常因果转译。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **命令式通信** | 统一命令式操作门面 | `HttpTemplate` | `ateng.boot.http.enabled` | `get` / `getOptional` / `getEntity` / `post` / `put` / `patch` / `delete` / `head` / `options` / `exchange` / `postForm` / `upload` / `download` / `consumeStream` / `executeAsync`，支持 Class 与 ParameterizedTypeReference 泛型双模、URL-encoded 表单、SSE AI 流式消费、大文件零缓冲传输与异常拦截 | `HttpTemplateSampleTest` / `HttpGovernanceScenarioTest` |
| **单次动态参数** | 单次超时覆盖与头治理 | `RequestOptions`<br>`RequestOptionsApplier`<br>`HttpRequestContextHolder` | - | `RequestOptions.builder().readTimeout(Duration).header(name, value).queryParam(k, v).uriVariable(k, v).propagateContext(boolean).clientName(name).build()`，RFC 3986 安全编码与底层物理请求超时动态生效 | `RequestOptionsTest` / `RequestOptionsApplierTest` / `HttpTemplateSampleTest` |
| **方法级超时** | 方法级细粒度超时声明 | `@HttpTimeout` | - | `@HttpTimeout(readTimeout = "500ms", connectTimeout = "1s")`，声明在接口方法上动态下发建联与读取超时，方法退出时对现场严格对称清理 | `HttpInterfaceFallbackAndTimeoutTest` / `DeclarativeClientScenarioTest` |
| **声明式客户端** | 声明式接口标记驱动 | `@HttpClient`<br>`@HttpExchange` | `ateng.boot.http.clients.<name>.*` | `@HttpClient(name = "user-service", url = "${remote.url}", fallback = Fallback.class, primary = true)`，支持元注解派生、`${...}` 占位符解析、Primary 开关与本地优雅降级 | `HttpInterfaceRegistrarTest` / `DeclarativeClientScenarioTest` |
| **声明式包扫描** | 声明式轻量动态包扫描器 | `@EnableHttpInterfaces`<br>`HttpInterfaceRegistrar`<br>`HttpInterfaceFactoryBean` | - | `@EnableHttpInterfaces(basePackages = "com.example.client", clients = {UserClient.class})`，自研轻量 Spring 原生扫描器，桥接 Spring 6 `HttpServiceProxyFactory` 与动态代理 | `HttpInterfaceRegistrarTest` / `DeclarativeClientScenarioTest` |
| **声明式降级** | 本地容错降级代理 | `@HttpClient.fallback`<br>`HttpInterfaceFactoryBean` | - | 当声明式客户端调用遭遇 4xx/5xx、超时或网络中断时，由动态代理自动拦截并平滑回退执行 Fallback Bean 的同名方法 | `HttpInterfaceFallbackAndTimeoutTest` / `DeclarativeClientScenarioTest` |
| **多命名客户端** | 多命名客户端注册表与单例缓存 | `RestClientRegistry` | `ateng.boot.http.clients.<name>.*` | `getClient(clientName, annotationUrl)` / `getClient(clientName)` / `contains(clientName)` / `register(clientName, client)` / `unregister(clientName)` / `clear()` / `resolveBaseUrl(clientName, annotationUrl)`，复用全局物理连接池，单例并发安全 | `RestClientRegistryTest` / `DeclarativeClientScenarioTest` |
| **双向 mTLS 证书** | 金融级双向认证与证书管理 | `HttpSslUtil`<br>`HttpSslProperties` | `ateng.boot.http.ssl.*`<br>`ateng.boot.http.clients.<name>.ssl.*` | `HttpSslUtil.loadKeyStore(ssl)` / `HttpSslUtil.loadKeyManagers(ssl)`，支持 PKCS12 / JKS 格式客户端私钥证书加载与密钥管理器初始化，支持命名客户端独立挂载专属证书 | `HttpSslUtilTest` / `HttpGovernanceScenarioTest` |
| **物理连接池** | 双模自适应物理连接池 | `ClientHttpRequestFactoryBuilder`<br>`ApacheHttpClient5Factory`<br>`JdkHttpClientFactory` | `ateng.boot.http.engine`<br>`ateng.boot.http.pool.*`<br>`ateng.boot.http.ssl.*`<br>`ateng.boot.http.proxy.*` | 依据类路径自适应探测 Apache HttpClient 5 生产级池化连接（带后台驱逐守护）或平滑回退 JDK 17 原生 `HttpClient`，严防句柄与端口耗尽 | `ClientHttpRequestFactoryBuilderTest` / `HttpTemplateSampleTest` |
| **专属超时隔离** | 客户端专属超时包装工厂 | `ClientScopedHttpRequestFactory` | `ateng.boot.http.clients.<name>.connect-timeout`<br>`ateng.boot.http.clients.<name>.read-timeout` | 100% 共享全局物理连接池，采用 `previousTimeout` 现场还原模式为特定客户端安全附加独立的建联与读取响应超时 | `RestClientRegistryTest` / `DeclarativeClientScenarioTest` |
| **专属代理隔离** | 全局与命名客户端正向代理 | `HttpProxyProperties` | `ateng.boot.http.proxy.*`<br>`ateng.boot.http.clients.<name>.proxy.*` | 支持出站 HTTP 正向代理配置，命名客户端可独立声明专用代理通道或继承全局 | `HttpProxyPropertiesTest` / `HttpGovernanceScenarioTest` |
| **全链路上下文** | 四维上下文出站透传 | `TraceContextInterceptor` | `ateng.boot.http.tracing.enabled`<br>`ateng.boot.http.tracing.trace-id-header`<br>`ateng.boot.http.tracing.tenant-id-header`<br>`ateng.boot.http.tracing.user-id-header`<br>`ateng.boot.http.tracing.ignore-tenant-header` | 自动提取当前线程 MDC `traceId`、`TenantContextHolder` 与 `UserContextHolder` 注入出站请求头（缺省自动通过 `IdUtil.fastSimpleUUID()` 注入），实现分布式全链路可观测闭环 | `TraceContextInterceptorTest` / `HttpGovernanceScenarioTest` / `DeclarativeClientScenarioTest` |
| **非信任域隔离** | 外部公网敏感头安全剥离 | `TraceContextInterceptor`<br>`HttpInterceptorUtil` | `ateng.boot.http.clients.<name>.propagate-context` | 依据目标 URL 最长前缀匹配或单次请求显式声明，向外部非信任域（微信、支付宝、第三方 SaaS 等）发起调用时强制安全剥离内网租户与用户标识，杜绝敏感身份泄漏 | `TraceContextInterceptorTest` / `HttpGovernanceScenarioTest` / `DeclarativeClientScenarioTest` |
| **出站审计日志** | 结构化 cURL 脱敏日志 | `HttpLoggingInterceptor` | `ateng.boot.http.logging.enabled`<br>`ateng.boot.http.logging.level`<br>`ateng.boot.http.logging.max-body-length`<br>`ateng.boot.http.logging.sensitive-headers`<br>`ateng.boot.http.logging.mask-params` | 格式化输出出站请求 cURL 片段与响应耗时，对 Authorization/Cookie/Token 等敏感头及 URL 查询参数（token、secret、password、apiKey 等）自动掩码脱敏，大载荷安全截断防爆内存 | `HttpLoggingInterceptorTest` / `HttpGovernanceScenarioTest` |
| **弹性指数退避** | 幂等重试与 RFC Retry-After 降频 | `HttpRetryInterceptor` | `ateng.boot.http.retry.enabled`<br>`ateng.boot.http.retry.max-attempts`<br>`ateng.boot.http.retry.delay`<br>`ateng.boot.http.retry.multiplier`<br>`ateng.boot.http.retry.max-delay`<br>`ateng.boot.http.retry.jitter`<br>`ateng.boot.http.retry.retry-status-codes`<br>`ateng.boot.http.retry.retry-methods` | 仅针对幂等动词（GET/HEAD）在遭遇网络抖动或 429/502/503/504 错误码时执行指数退避重试与随机抖动，自适应解析 RFC 7231 / RFC 6585 `Retry-After` 响应头精准覆盖本地退避时延，单次重试前显式释放物理响应流，环绕内聚日志记录 | `HttpRetryInterceptorTest` / `HttpGovernanceScenarioTest` |
| **因果异常体系** | 统一异常转译与强类型反序列化 | `HttpErrorHandler`<br>`HttpRemoteException`<br>`HttpResultCode` | - | 统一拦截 4xx 与 5xx 状态码并转译为业务受管的 `HttpRemoteException`（继承 `BusinessException`），提供 `getResponseBodyAs(Class<T>)` / `getResponseBodyAs(ParameterizedTypeReference<T>)` 强类型错误载荷反序列化，100% 保持原始因果链并映射清晰错误码 | `HttpErrorHandlerTest` / `HttpRemoteExceptionTest` / `HttpTemplateSampleTest` / `DeclarativeClientScenarioTest` |
| **官方基线融合** | Spring Boot 官方基线配置器对齐 | `AtengHttpAutoConfiguration`<br>`RestClientBuilderConfigurer` | - | 自动注入并调度 Spring Boot 官方 `RestClientBuilderConfigurer.configure(builder)`，无缝继承容器消息转换器与 Actuator Micrometer `http.client.requests` 度量指标 | `AtengHttpAutoConfigurationTest` |
| **Apache 扩展 SPI** | HttpClient 5 定制器扩展 | `HttpClient5Customizer` | - | 提供回调接口自定义扩展 `HttpClientBuilder` 与 `PoolingHttpClientConnectionManagerBuilder`（如定制 User-Agent、DNS 解析器、Socket 参数等） | `ClientHttpRequestFactoryBuilderTest` / `HttpTemplateSampleTest` |
| **Spring 定制 SPI** | RestClient 全局定制器 | `RestClientCustomizer` | - | Spring Boot 官方标准定制扩展，支持下游添加全局请求头、消息转换器与全局拦截器 | `RestClientRegistryTest` / `HttpTemplateSampleTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-http` 是面向生产就绪的高性能、低开销 HTTP 客户端基础设施模块：
- **零臃肿哲学**：坚持纯原生标准库与 Spring 6 原生能力优先，坚决杜绝引入 OkHttp 运行时核心、Feign、Retrofit、Hutool、Guava 等重型第三方全量工具包；
- **双模调用闭环**：
  - **命令式（Imperative）**：通过 `HttpTemplate` 执行灵活可控的单次 HTTP 通信，支持流式传输、单次参数微调与上下文穿透；
  - **声明式（Declarative）**：通过 `@EnableHttpInterfaces` 动态包扫描与 `@HttpClient` 声明式接口，基于 Spring 6 原生 `@HttpExchange` 驱动，强类型面向契约编程；
- **共享连接池与配置隔离**：全应用无论存在多少声明式或命令式客户端，统一共享底层物理连接池，按命名客户端独立纳管 BaseUrl、超时与敏感头安全防护。

### Maven 依赖引入
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-http</artifactId>
</dependency>
```

---

## 2. 基础配置说明与生产范式

### 场景痛点
在微服务与分布式协同场景中，HTTP 调用常常面临：
1. 底层连接池创建泛滥，不同接口反复创建独立连接池导致文件描述符（FD）和本地端口耗尽；
2. 内部微服务调用丢失 `traceId` 与 `tenantId`，而向外部公网（如微信、支付宝、第三方开放平台）发起调用时又极易将内部敏感租户与用户身份泄露至公网；
3. 网络偶发抖动引发雪崩，缺乏安全的幂等退避重试或盲目重试非幂等操作（如 POST 导致重复扣款）。

### 生产推荐配置 (`application.yml`)
```yaml
ateng:
  boot:
    http:
      enabled: true                     # 是否启用 HTTP 模块自动装配（默认：true）
      engine: auto                      # 通信引擎：auto (优先 HttpClient 5，缺省回退 JDK 17)
      pool:
        max-connections: 200            # 连接池最大总连接数（默认：200）
        max-per-route: 50               # 单路由最大并发连接数（默认：50）
        connect-timeout: 3s             # TCP 建联超时时间（默认：3s）
        read-timeout: 10s               # 读取响应超时时间（默认：10s）
        connection-request-timeout: 2s  # 从连接池借出连接超时时间（默认：2s）
        idle-timeout: 60s               # 空闲连接驱逐阈值（默认：60s）
        validate-after-inactivity: 2s   # 连接长久未用后的保活探测时延（默认：2s）
      ssl:
        insecure: false                 # 是否跳过 SSL 证书校验与主机名验证（生产严格保持 false，内网自签名环境按需开启）
      proxy:
        enabled: false                  # 是否开启出口 HTTP 正向代理
        host: 127.0.0.1
        port: 7890
      tracing:
        enabled: true                   # 是否启用四维全链路上下文出站透传（默认：true）
        trace-id-header: X-Trace-Id     # 全链路追踪头名称（默认：X-Trace-Id）
        tenant-id-header: X-Tenant-Id   # 多租户隔离头名称（默认：X-Tenant-Id）
        user-id-header: X-User-Id       # 用户身份头名称（默认：X-User-Id）
        ignore-tenant-header: X-Ignore-Tenant # 忽略租户标记头名称
      logging:
        enabled: true                   # 是否启用出站 cURL 结构化脱敏日志（默认：true）
        level: HEADERS                  # 日志级别：NONE, BASIC, HEADERS, BODY（默认：HEADERS）
        max-body-length: 4096           # 载荷打印最大截断字节数（默认：4096）
        sensitive-headers:              # 强制自动掩码脱敏的敏感请求头（默认内置常见凭据头）
          - Authorization
          - Cookie
          - Set-Cookie
          - X-Token
        mask-params:                    # 强制自动掩码脱敏的 URL 查询参数（默认包含密码、密钥与令牌）
          - secret
          - password
          - token
          - apiKey
          - api_key
          - key
          - access_token
      retry:
        enabled: true                   # 是否启用弹性指数退避重试（默认：true）
        max-attempts: 3                 # 最大尝试次数（含首次请求，默认：3）
        delay: 500ms                    # 初始重试退避时延（默认：500ms）
        multiplier: 2.0                 # 退避递增倍数（默认：2.0）
        max-delay: 5s                   # 最大退避时延上限（默认：5s）
        jitter: true                    # 是否引入随机抖动防共振（默认：true）
        retry-status-codes: [429, 502, 503, 504] # 触发重试的服务端瞬态状态码（支持 429 降频与 5xx 故障）
        retry-methods: [GET, HEAD]      # 允许重试的幂等 HTTP 动词（坚决杜绝重试 POST/PUT）
      clients:
        user-service:
          base-url: http://user-service.internal:8080 # 内部微服务目标根路径
          connect-timeout: 2s           # 客户端专属建联超时（覆盖全局）
          read-timeout: 5s              # 客户端专属读取超时（覆盖全局）
          propagate-context: true       # 内部信任域：允许透传 MDC 与租户上下文
        wechat-pay:
          base-url: https://api.mch.weixin.qq.com    # 外部非信任第三方 OpenAPI
          connect-timeout: 2s
          read-timeout: 5s
          propagate-context: false      # 外部非信任域：严格物理剥离内网私有租户与用户头
        scoped-proxy-client:
          base-url: http://proxy-target.internal:8080
          proxy:
            enabled: true
            host: 127.0.0.1
            port: 8888
        mtls-service:
          base-url: https://mtls.internal:8443
          ssl:
            key-store: classpath:certs/sample-client-cert.p12 # 客户端私钥证书路径
            key-store-password: cert_password                 # 密钥库密码
            key-store-type: PKCS12                            # 密钥库类型（默认 PKCS12）
```

---

## 3. 最小起步体验 (Happy Path & Out-of-the-Box)

### 3.1 声明式客户端三板斧（强类型契约面向接口编程）

#### 第一板斧：激活声明式包扫描 (`@EnableHttpInterfaces`)
在 Spring Boot 主类或配置类上标注：
```java
package io.github.atengk.boot.sample.http;

import io.github.atengk.boot.http.annotation.EnableHttpInterfaces;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableHttpInterfaces(basePackages = "io.github.atengk.boot.sample.http.client")
@SpringBootApplication
public class SampleHttpApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleHttpApplication.class, args);
    }
}
```

#### 第二板斧：声明强类型 HTTP 客户端接口 (`@HttpClient`)
```java
package io.github.atengk.boot.sample.http.client;

import io.github.atengk.boot.http.annotation.HttpClient;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import io.github.atengk.boot.sample.http.dto.UserResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpClient(name = "user-service", url = "${sample.user-service.url:http://localhost:8080}")
@HttpExchange
public interface UserServiceClient {

    @GetExchange("/users/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);

    @PostExchange("/users")
    UserResponse createUser(@RequestBody UserRequest user);
}
```

#### 第三板斧：依赖注入直接执行强类型安全调用
```java
@Service
public class OrderBusinessService {

    @Autowired
    private UserServiceClient userServiceClient;

    public void processUserOrder(Long userId) {
        // 自动序列化、底层连接池复用、上下文出站透传与异常转译全自动完成
        UserResponse user = userServiceClient.getUserById(userId);
        log.info("Successfully fetched user info: id={}, username={}, role={}",
                user.getId(), user.getUsername(), user.getRole());
    }
}
```

---

### 3.2 命令式客户端三板斧（统一门面 `HttpTemplate`）

```java
@Service
public class FlexibleHttpService {

    @Autowired
    private HttpTemplate httpTemplate;

    public void invokeRemoteService() {
        // 1. 极简 GET 调用并自动反序列化
        UserResponse user = httpTemplate.get("http://api.internal/users/1001", UserResponse.class);

        // 2. 携带单次动态超时与特定 Query 参数
        RequestOptions options = RequestOptions.builder()
                .readTimeout(Duration.ofSeconds(3))
                .queryParam("detail", "true")
                .header("X-Custom-Trace", "trace-sample-001")
                .build();

        UserResponse detailed = httpTemplate.get("http://api.internal/users/1001", UserResponse.class, options);

        // 3. 极简 POST 创建资源
        UserRequest request = UserRequest.builder()
                .username("NewUser")
                .email("user@example.com")
                .role("DEVELOPER")
                .build();
        Map<?, ?> result = httpTemplate.post("http://api.internal/users", request, Map.class);
    }
}
```

---

## 4. 生产扩展接管 (Customization & Extensibility)

### 4.1 自定义 Apache HttpClient 5 底层参数 (`HttpClient5Customizer`)
当企业需要定制特定的 DNS 域名解析器、自定义 User-Agent 或定制连接池 Socket 参数时，实现 `HttpClient5Customizer` 即可平滑接管：
```java
@Configuration
public class HttpCustomConfiguration {

    @Bean
    public HttpClient5Customizer sampleHttpClient5Customizer() {
        return (clientBuilder, connectionManagerBuilder) -> {
            clientBuilder.setUserAgent("ateng-sample-agent/1.0.0");
        };
    }
}
```

### 4.2 全局扩展 Spring 官方 `RestClientCustomizer`
通过声明 `RestClientCustomizer` Bean，无缝为所有 RestClient 注入业务统一出站请求头或自定义消息转换器：
```java
@Configuration
public class RestClientConfiguration {

    @Bean
    public RestClientCustomizer sampleRestClientCustomizer() {
        return restClientBuilder -> restClientBuilder.defaultHeader("X-Custom-Client", "ateng-http-sample");
    }
}
```

### 4.3 编程式接管命名客户端 (`RestClientRegistry`)
支持下游在运行期动态向注册表注入自定义构建的 `RestClient` 实例，提供对称完备的编程式纳管能力：
```java
@Component
public class CustomClientRegistrar implements InitializingBean {

    @Autowired
    private RestClientRegistry restClientRegistry;

    @Override
    public void afterPropertiesSet() {
        RestClient customClient = RestClient.builder()
                .baseUrl("https://special-gateway.internal")
                .build();
        restClientRegistry.register("special-gateway", customClient);
    }
}
```

---

## 5. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 5.1 虚拟线程高并发压测协同 (Virtual Threads Resilience)
- **零 Pinning 不变量**：底层无论采用 Apache HttpClient 5 连接池还是 JDK 17 原生 Client，内部连接借还与 I/O 均经过全真高并发压测验证；
- **自适应并发弹性**：
  - 在 **Java 21+** 环境下运行时，系统自适应支持通过 `Thread.ofVirtual()` 拉起 500+ 虚拟线程并发向目标服务发起请求；物理连接平滑借出与归还，零死锁、零载体线程钉死（Carrier Thread Pinning）；
  - 在 **Java 17** 运行时下，自适应平滑回退至 Core 模块受管异步线程池设施（`ThreadPoolTaskExecutor`），提供一致的生产稳定性。

### 5.2 全局物理连接池共享与专属超时隔离
- **物理唯一性**：严禁按命名客户端重复创建底层连接池。所有声明式客户端接口统一共享 Spring 容器唯一的 `ClientHttpRequestFactory`（默认 200 总连接数 / 50 路由并发）；
- **超时隔离机制**：通过 `ClientScopedHttpRequestFactory` 代理包装器与 `RequestOptionsApplier`，每个命名客户端在复用同一个全局物理连接池的同时，独立生效专属的建联与读取超时；
- **线程变量安全还原**：采用 `previousTimeout` 现场还原模式，在请求创建完成前绑定超时，创建完成后立即在 `finally` 中严格还原，彻底杜绝线程复用时引发的上下文污染。

### 5.3 四维上下文透传与非信任域安全隔离
- **内部微服务信任域**：出站物理请求前，拦截器自动提取当前线程的四维业务上下文（`traceId`、`tenantId`、`userId`、`ignoreTenant`）并注入对应请求头；若当前未开启全链路链路追踪，自动使用 `IdUtil.fastSimpleUUID()` 无锁生成短 UUID 兜底，保障全链路可观测性闭环；
- **外部公网非信任域（微信/支付宝等）**：在 `clients.<name>` 中显式配置 `propagate-context: false`，拦截器依据最长前缀自适应匹配或显式声明，**出站前 100% 物理剥离 `X-Tenant-Id` 与 `X-User-Id`**，严格杜绝内部租户标识与用户身份泄露至公网，同时保持 `X-Trace-Id` 正常透传以保留跨系统可观测性。

### 5.4 弹性指数退避重试安全防线
- **动词安全白名单**：默认仅针对 **GET** 与 **HEAD** 等天然安全幂等动词执行重试；**坚决杜绝自动重试 POST/PUT/DELETE 等非幂等动词**，从源头杜绝资金划扣或状态重复流转事故；
- **物理连接及时归还**：遭遇 502/503/504 错误触发重试时，拦截器在每次重试前显式释放关闭上一次失败轮次的底层响应流（`response.close()`），防止连接堆积导致连接池泄漏；
- **随机抖动防共振**：退避计算深度整合 `RetryUtil`，默认开启 `jitter = true`，有效打散并发重试峰值，避免重试共振打垮下游刚恢复的服务。

### 5.5 因果异常体系与统一错误码转译
- **严禁 Log-and-Throw 双重污染**：所有 4xx 与 5xx 状态码响应经由 `HttpErrorHandler` 统一转译为 `HttpRemoteException`；
- **因果堆栈 100% 留存**：转译过程中完整保留底层根因异常链（`cause`），并挂载请求动词、目标 URI、HTTP 状态码及安全截断后的响应体（防超长文本撑爆堆内存）；
- **细粒度错误码契约**：400 映射为 `REMOTE_HTTP_BAD_REQUEST`、401 映射为 `REMOTE_HTTP_UNAUTHORIZED`、404 映射为 `REMOTE_HTTP_NOT_FOUND`、500 映射为 `REMOTE_HTTP_SERVER_ERROR`、503 映射为 `REMOTE_HTTP_SERVICE_UNAVAILABLE`。

---

## 6. 锚定实测与场景用例索引 (Evergreen Verification)

本模块所有特性与配置 100% 锚定场景沙箱工程（`ateng-boot-samples/ateng-boot-sample-http`）端到端真实测试用例：

- **声明式内部 CRUD 与四维上下文透传**：详见 `DeclarativeClientScenarioTest.java` 场景 1；
- **声明式外部公网敏感私有头 100% 剥离**：详见 `DeclarativeClientScenarioTest.java` 场景 2；
- **声明式 404/500 因果异常转译与状态码断言**：详见 `DeclarativeClientScenarioTest.java` 场景 3；
- **自适应虚拟线程 500+ 并发冲击与长连接复用压测**：详见 `DeclarativeClientScenarioTest.java` 场景 4；
- **声明式方法级细粒度超时控制 (`@HttpTimeout`)**：详见 `DeclarativeClientScenarioTest.java` 场景 5；
- **声明式本地优雅降级容错 (`@HttpClient.fallback`)**：详见 `DeclarativeClientScenarioTest.java` 场景 6；
- **声明式方法级超时触发本地优雅降级协同**：详见 `DeclarativeClientScenarioTest.java` 场景 7；
- **命令式全套动词通信与流式上传下载**：详见 `HttpTemplateSampleTest.java`；
- **出站 cURL 脱敏日志与 503 指数退避重试**：详见 `HttpGovernanceScenarioTest.java` 场景 1-5；
- **命名客户端专属正向代理与连接配置解析验证**：详见 `HttpGovernanceScenarioTest.java` 场景 6；
- **金融级 mTLS 双向认证证书配置绑定与专职物理工厂端到端装配**：详见 `HttpGovernanceScenarioTest.java` 场景 7；
- **轻量扫描器与元注解派生**：详见 `HttpInterfaceRegistrarTest.java`；
- **多命名客户端注册表单例并发与多级 BaseUrl 覆盖**：详见 `RestClientRegistryTest.java`；
- **出站 SSL 与金融级 mTLS 证书工具类**：详见 `HttpSslUtilTest.java`。
