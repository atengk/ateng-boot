# OpenAPI 接口文档治理与安全底座 (ateng-boot-openapi)

`ateng-boot-openapi` 是面向生产就绪的企业级 RESTful 接口契约治理与安全防护底座。彻底弃用停更的传统 Knife4j Java Starter，全面拥抱 **SpringDoc 官方原生标准生态（Swagger UI 5.x，依赖 `springdoc-openapi-starter-webmvc-ui:2.6.0`）**，坚持纯原生、零臃肿与防御纵深设计原则。

---

## 维度零：特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 组件 / SPI | 关键配置项 (`ateng.boot.openapi.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **装配生命周期** | 核心契约自动装配 | `OpenApiAutoConfiguration` | `enabled` (默认 `true`) | `customOpenAPI(OpenApiProperties)`: 构建基础 OpenAPI 契约 | [OpenApiAutoConfigurationTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/autoconfigure/OpenApiAutoConfigurationTest.java) |
| **快捷路由** | /docs 友好路由重定向 | `OpenApiAutoConfiguration` | `redirect-docs` (默认 `true`) | `openApiWebMvcConfigurer`: 注册 `/docs` 与 `/doc` 302 重定向至 `/swagger-ui/index.html` | [OpenApiSampleEndToEndTest](file:///d:/My/dev/ateng-boot/ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiSampleEndToEndTest.java) |
| **安全防御** | 生产环境物理切断 (404) | `OpenApiGuardFilter` | `enabled=false` | `doFilter(...)`: 彻底切断 Swagger UI 与 `/v3/api-docs` 端点，响应原生 404 | [OpenApiGuardIntegrationTest](file:///d:/My/dev/ateng-boot/ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiGuardIntegrationTest.java) |
| **安全防御** | HTTP Basic 访问守卫 | `OpenApiGuardFilter` | `guard.mode=BASIC`<br>`guard.basic.username`<br>`guard.basic.password` | `handleBasicAuth`: 触发标准 HTTP 401 `WWW-Authenticate: Basic` 原生认证弹窗 | [OpenApiGuardFilterTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **安全防御** | Sa-Token 会话凭证守卫 | `OpenApiGuardFilter` | `guard.mode=SATOKEN` | `handleSaTokenAuth`: 弱依赖联动 Sa-Token 会话，未登录用户拒绝访问返回 401 | [OpenApiGuardFilterTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **安全防御** | 静态资源白名单放行 | `OpenApiGuardFilter` | `guard.mode` | `isStaticResource(path)`: 对 CSS, JS, WebJars 等资源免密放行，防御样式死锁 | [OpenApiGuardFilterTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **标头透传** | 全链路追踪头自动注入 | `GlobalHeaderCustomizer` | `headers.trace-id` (默认 `true`) | `customize(Operation, HandlerMethod)`: 自动注入非必填请求头 `X-Trace-Id` | [GlobalHeaderCustomizerTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **标头透传** | 多租户标识头自动注入 | `GlobalHeaderCustomizer` | `headers.tenant-id` (默认 `true`) | `customize(Operation, HandlerMethod)`: 自动注入非必填请求头 `X-Tenant-Id` | [GlobalHeaderCustomizerTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **鉴权集成** | Sa-Token 锁头弹窗集成 | `SaTokenSecurityCustomizer` | 无需配置，自动感知 | `customise(OpenAPI)`: 向 Components 注册 APIKey 并全局挂载，激活右上角 Authorize 锁头 | [SaTokenSecurityCustomizerTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/customizer/SaTokenSecurityCustomizerTest.java) |
| **契约规范** | 状态码全景字典注入 | `ResultCodeDictionaryCustomizer` | `show-result-codes` (默认 `true`) | `customise(OpenAPI)`: 自动向 `Info.description` 尾部注入系统通用状态码 Markdown 表格 | [ResultCodeDictionaryCustomizerTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/customizer/ResultCodeDictionaryCustomizerTest.java) |
| **契约规范** | 标准 HTTP 响应说明补全 | `StandardApiResponseCustomizer` | `standard-responses` (默认 `true`) | `customize(Operation, HandlerMethod)`: 为 Operation 补全 200/400/401/500 响应语义 | [StandardApiResponseCustomizerTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/customizer/StandardApiResponseCustomizerTest.java) |
| **分组治理** | 配置驱动多业务分组路由 | `DynamicGroupedOpenApiRegistrar` | `groups` (配置列表) | `registerBeanDefinitions(...)`: 依据配置动态注册多 `GroupedOpenApi`，未配置时兜底单全量 | [DynamicGroupedOpenApiRegistrarTest](file:///d:/My/dev/ateng-boot/ateng-boot-openapi/src/test/java/io/github/atengk/boot/openapi/group/DynamicGroupedOpenApiRegistrarTest.java) |

---

## 维度一：最小起步体验 (Happy Path & Out-of-the-Box)

只需引入依赖，即可享受零配置开箱即用的 OpenAPI 3.0 接口文档、全链路 TraceId 自动注入与 `/docs` 极简重定向：

### 1. 引入 Maven 依赖
```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-openapi</artifactId>
</dependency>
```

### 2. 基础配置文件 (`application.yml`)
```yaml
ateng:
  boot:
    openapi:
      enabled: true
      title: "电商中台微服务 API 文档"
      version: "1.0.0"
      description: "基于 ateng-boot 构建的标准化接口契约"
```

### 3. Controller 编码（显式强契约 Result<T>）
```java
@Tag(name = "用户管理", description = "面向后台系统的用户 CRUD 接口")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "创建系统用户")
    @PostMapping
    public Result<UserVO> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserVO vo = new UserVO(10001L, request.getUsername());
        return Result.success(vo);
    }
}
```

访问 `http://localhost:8080/docs` 即可直接 302 重定向至 Swagger UI 界面。

---

## 维度二：生产扩展接管 (Customization & Extensibility)

框架内部所有核心组件（`OpenAPI`、`GlobalHeaderCustomizer`、`SaTokenSecurityCustomizer`、`ResultCodeDictionaryCustomizer`、`StandardApiResponseCustomizer`）均显式标配 `@ConditionalOnMissingBean`，支持下游平滑覆盖。

### 1. 覆盖默认 OpenAPI 元数据 Bean
当需要高度定制 OpenAPI 全局信息（如添加专有扩展标签或安全方案）时，直接在业务工程声明 Bean 即可无缝接管：
```java
@Configuration(proxyBeanMethods = false)
public class CustomOpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("定制中台接口体系")
                        .version("2.5.0")
                        .description("由企业自定义的 OpenAPI 契约规范"));
    }
}
```

### 2. 多业务分组路由声明 (`ateng.boot.openapi.groups`)
无需手写冗长的 Java 模板代码，直接在 `application.yml` 中声明业务分组，由框架自动完成动态路由装配：
```yaml
ateng:
  boot:
    openapi:
      groups:
        - group: "admin"
          display-name: "运营管理端 (Admin API)"
          paths-to-match:
            - "/api/admin/**"
          packages-to-scan:
            - "com.example.controller.admin"
        - group: "app"
          display-name: "移动端服务 (App API)"
          paths-to-match:
            - "/api/app/**"
          packages-to-scan:
            - "com.example.controller.app"
```

---

## 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 生产环境物理切断 (HTTP 404)
- **行为机制**：当设置 `ateng.boot.openapi.enabled: false` 时，框架会物理卸载所有 OpenAPI 相关 Bean（内存占用清零），且 `OpenApiGuardFilter` 会在请求进入第一站将所有 `/swagger-ui/**`、`/docs` 及 `/v3/api-docs/**` 请求直接阻断并响应 HTTP 404。
- **安全保障**：黑客或自动化爬虫扫描端点时无法探测到任何元数据或页面，杜绝接口信息泄露风险。

### 2. HTTP Basic Auth 凭据质询
- **行为机制**：当配置 `ateng.boot.openapi.guard.mode: BASIC` 时，未携带有效凭据访问文档页面将收到 HTTP 401 响应与 `WWW-Authenticate: Basic realm="OpenAPI Document Guard"` 请求头，触发浏览器原生输入弹窗。
- **静态资源豁免**：`.css`、`.js`、`.png` 及 `webjars` 资源由过滤器自动白名单放行，确保认证弹窗与前端静态样式不发生死锁或破损。

### 3. Sa-Token 会话锁头联动
- **零配置感知**：只要 classpath 存在 `cn.dev33.satoken.SaManager` 类，框架会自动提取 Sa-Token 配置的 Token 键名（如 `satoken`），并在 Swagger UI 右上角注入“Authorize / 锁头”图标。
- **单次输入全局自动携带**：开发人员在弹窗中输入一次有效 Token，后续在 Swagger UI 点击“Try it out”测试的每一个请求，均会自动在 Header 中携带该 Token。

---

## 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

- **真实场景样例工程**：[`ateng-boot-samples/ateng-boot-sample-openapi`](file:///d:/My/dev/ateng-boot/ateng-boot-samples/ateng-boot-sample-openapi)
- **端到端集成测试**：[`OpenApiSampleEndToEndTest.java`](file:///d:/My/dev/ateng-boot/ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiSampleEndToEndTest.java)
- **生产安全守卫测试**：[`OpenApiGuardIntegrationTest.java`](file:///d:/My/dev/ateng-boot/ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiGuardIntegrationTest.java)
- **架构决策记录**：[ADR-0041: 纯原生 SpringDoc 架构选型与接口文档生产治理防护决策](file:///d:/My/dev/ateng-boot/docs/adr/0041-pure-springdoc-openapi-and-production-guard-architecture.md)
