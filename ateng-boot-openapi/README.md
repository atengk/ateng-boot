# OpenAPI 接口文档治理与安全底座 (ateng-boot-openapi)

`ateng-boot-openapi` 是面向生产就绪的企业级 RESTful 接口契约治理、五模安全守卫与离线契约交付底座。全面拥抱 **SpringDoc 官方原生标准生态（Swagger UI 5.x，依赖 `springdoc-openapi-starter-webmvc-ui:2.6.0`）**，坚持纯原生、零臃肿与防御纵深设计原则，深度协同 Core 模块的 `IBaseEnum`、`ValidList` 与 `@FieldSensitive` 原语，为分布式微服务与单体应用提供可信契约基础设施。

---

## 维度零：特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.openapi.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **装配生命周期** | 核心契约自动装配 | `OpenApiAutoConfiguration` | `enabled` (默认 `true`) | `customOpenAPI(OpenApiProperties)`: 构建基础 OpenAPI 3.0 元数据契约 | [OpenApiAutoConfigurationTest](./src/test/java/io/github/atengk/boot/openapi/autoconfigure/OpenApiAutoConfigurationTest.java) |
| **快捷路由** | /docs 友好路由重定向 | `OpenApiAutoConfiguration` | `redirect-docs` (默认 `true`) | `openApiWebMvcConfigurer`: 注册 `/docs` 与 `/doc` 302 重定向至 `/swagger-ui/index.html` | [OpenApiSampleEndToEndTest](../ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiSampleEndToEndTest.java) |
| **安全防御** | 生产环境物理切断 (404) | `OpenApiGuardFilter` | `enabled=false` | `doFilter(...)`: 彻底切断 Swagger UI、`/v3/api-docs` 及离线导出端点，原生响应 404 | [OpenApiGuardIntegrationTest](../ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiGuardIntegrationTest.java) |
| **安全防御** | HTTP Basic 访问守卫 | `OpenApiGuardFilter` | `guard.mode=BASIC`<br>`guard.basic.username`<br>`guard.basic.password` | `handleBasicAuth`: 触发标准 HTTP 401 `WWW-Authenticate: Basic` 弹窗，恒定时间比对防时序攻击 | [OpenApiGuardFilterTest](./src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **安全防御** | Sa-Token 会话凭证守卫 | `OpenApiGuardFilter` | `guard.mode=SATOKEN` | `handleSaTokenAuth`: 弱依赖联动 Sa-Token 会话，未登录用户拒绝访问返回 401 | [OpenApiGuardFilterTest](./src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **安全防御** | 静态令牌 (TOKEN) 守卫 | `OpenApiGuardFilter` | `guard.mode=TOKEN`<br>`guard.token.secret`<br>`guard.token.header-name`<br>`guard.token.allow-query-param` | `handleTokenAuth`: 比对 `X-Doc-Token` 或 `?doc-token=xxx`，恒定时间防时序攻击比对 | [OpenApiGuardFilterTest](./src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **安全防御** | CIDR 白名单网络防火墙 | `OpenApiGuardFilter`<br>`OpenApiCidrMatcher` | `guard.ip-whitelist.cidrs`<br>`guard.ip-whitelist.trusted-header`<br>`guard.ip-whitelist.enabled` | `matches(String clientIp)`: 支持 IPv4/IPv6 与子网掩码计算，配置反代受信任标头深度防伪造 | [OpenApiCidrMatcherTest](./src/test/java/io/github/atengk/boot/openapi/guard/OpenApiCidrMatcherTest.java) |
| **安全防御** | 静态资源白名单放行 | `OpenApiGuardFilter` | `guard.mode` | `isStaticResource(path)`: 对 CSS, JS, WebJars, 图标等资源免密放行，防御样式死锁 | [OpenApiGuardFilterTest](./src/test/java/io/github/atengk/boot/openapi/guard/OpenApiGuardFilterTest.java) |
| **契约模型保真** | IBaseEnum 强类型枚举映射 | `OpenApiModelConverter` | 无需配置，自动探测 | `resolve(...)`: 映射真实 Integer/String 强类型 Schema，注入 `enum` 取值、Markdown 字典与示例 | [OpenApiModelConverterTest](./src/test/java/io/github/atengk/boot/openapi/converter/OpenApiModelConverterTest.java) |
| **契约模型保真** | ValidList 批量数组保真 | `OpenApiModelConverter` | 无需配置，自动探测 | `resolve(...)`: 拦截泛型 `ValidList<E>` 并精确转换为原生 `ArraySchema` `[ { item } ]` | [OpenApiModelConverterTest](./src/test/java/io/github/atengk/boot/openapi/converter/OpenApiModelConverterTest.java) |
| **契约模型保真** | @FieldSensitive 脱敏警示 | `OpenApiModelConverter` | 无需配置，自动探测 | `resolve(...)`: 为脱敏字段注入 `[脱敏字段: xxx掩码]` 警示前缀与标准示例掩码值 | [OpenApiModelConverterTest](./src/test/java/io/github/atengk/boot/openapi/converter/OpenApiModelConverterTest.java) |
| **标头透传** | 全链路追踪头自动注入 | `GlobalHeaderCustomizer` | `headers.trace-id` (默认 `true`) | `customize(Operation, HandlerMethod)`: 自动注入请求头 `X-Trace-Id` | [GlobalHeaderCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **标头透传** | 多租户标识头自动注入 | `GlobalHeaderCustomizer` | `headers.tenant-id` (默认 `true`) | `customize(Operation, HandlerMethod)`: 自动注入请求头 `X-Tenant-Id` | [GlobalHeaderCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **标头透传** | 自定义全局请求头配置注入 | `GlobalHeaderCustomizer` | `headers.custom` (列表) | `customize(Operation, HandlerMethod)`: 声明式注入自定义 Header（名称、描述、必填项与示例） | [GlobalHeaderCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **标头豁免** | 声明式精细化请求头豁免 | `@IgnoreGlobalHeaders` | 注解驱动 | 标注于类或方法上，支持无参全量豁免或精准指定豁免名单（如 `{"X-Tenant-Id"}`） | [GlobalHeaderCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/GlobalHeaderCustomizerTest.java) |
| **通用鉴权** | 配置化多安全方案挂载 | `SecuritySchemesCustomizer` | `security-schemes` (列表) | `customise(OpenAPI)`: 配置驱动向 Components 注册 HTTP Bearer、ApiKey 并全局激活锁头 | [SecuritySchemesCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/SecuritySchemesCustomizerTest.java) |
| **鉴权集成** | Sa-Token 锁头自动感知 | `SaTokenSecurityCustomizer` | 弱依赖感知 | `customise(OpenAPI)`: 探测并提取 Sa-Token 密钥名称，注册 APIKey 并激活锁头 | [SaTokenSecurityCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/SaTokenSecurityCustomizerTest.java) |
| **状态码规范** | 通用状态码全景字典注入 | `ResultCodeDictionaryCustomizer` | `show-result-codes` (默认 `true`) | `customise(OpenAPI)`: 自动向 `Info.description` 追加 Core 模块通用状态码 Markdown 字典 | [ResultCodeDictionaryCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/ResultCodeDictionaryCustomizerTest.java) |
| **状态码扩展** | 业务状态码字典 SPI 扩展 | `ResultCodeProvider` | SPI 注册 | `getResultCodes()`, `getTitle()`, `resolveScenario()`: 动态向全局描述注入业务领域状态码字典 | [ResultCodeProviderTest](./src/test/java/io/github/atengk/boot/openapi/customizer/ResultCodeProviderTest.java) |
| **契约规范** | 标准 HTTP 响应说明补全 | `StandardApiResponseCustomizer` | `standard-responses` (默认 `true`) | `customize(Operation, HandlerMethod)`: 为 Operation 补全 200/400/401/500 响应语义 | [StandardApiResponseCustomizerTest](./src/test/java/io/github/atengk/boot/openapi/customizer/StandardApiResponseCustomizerTest.java) |
| **业务分组** | 配置驱动多业务分组路由 | `DynamicGroupedOpenApiRegistrar` | `groups` (配置列表) | `registerBeanDefinitions(...)`: 依据配置动态注册多 `GroupedOpenApi`，未配置时兜底单全量 | [DynamicGroupedOpenApiRegistrarTest](./src/test/java/io/github/atengk/boot/openapi/group/DynamicGroupedOpenApiRegistrarTest.java) |
| **离线交付** | 离线契约导出门面助手 | `OpenApiExportHelper` | `exportJson(...)`<br>`exportYaml(...)`<br>`export(...)` | 支持指定分组或全量 OpenAPI 规范导出为 JSON/YAML 字符串、字节数组或写入输出流 | [OpenApiExportHelperTest](./src/test/java/io/github/atengk/boot/openapi/export/OpenApiExportHelperTest.java)<br>[OpenApiSampleEndToEndTest](../ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiSampleEndToEndTest.java) |
| **离线交付** | 受管离线契约导出 REST 控制器 | `OpenApiExportController` | `export.enabled: true` | `GET /docs/export/{group}.json` 与 `/{group}.yaml`，带 `Content-Disposition` 附件下载响应头 | [OpenApiExportControllerTest](./src/test/java/io/github/atengk/boot/openapi/export/OpenApiExportControllerTest.java) |

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
      title: "电商中台微服务 API 契约沙箱"
      version: "1.0.0"
      description: "基于 ateng-boot 构建的标准化接口契约文档"
      headers:
        trace-id: true
        tenant-id: true
      redirect-docs: true
```

### 3. Controller 编码（显式强契约 Result<T>）
```java
@Tag(name = "用户管理", description = "面向后台系统的用户治理接口")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "创建系统用户")
    @PostMapping
    public Result<AdminUserVO> createUser(@Valid @RequestBody AdminUserCreateRequest request) {
        AdminUserVO vo = new AdminUserVO(
                10001L,
                request.getUsername(),
                request.getRealName(),
                request.getEmail(),
                "13800138000",
                UserStatusEnum.ENABLE,
                LocalDateTime.now()
        );
        return Result.success(vo);
    }
}
```

访问 `http://localhost:8080/docs` 即可直接 302 重定向至 Swagger UI 界面。

---

## 维度二：生产扩展接管 (Customization & Extensibility)

框架内部所有核心组件（`OpenAPI`、`GlobalHeaderCustomizer`、`SecuritySchemesCustomizer`、`ResultCodeDictionaryCustomizer`、`OpenApiExportHelper` 等）均显式标配 `@ConditionalOnMissingBean`，支持业务无缝接管与深度扩展。

### 1. 声明式精细化请求头豁免 (`@IgnoreGlobalHeaders`)
当部分对外开放接口（如管理员登录、健康检查、Webhook 回调）不应展示内部全局租户头时，使用 `@IgnoreGlobalHeaders` 注解：
```java
@Tag(name = "运营管理端 - 开放认证", description = "开放登录与探针端点")
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    /**
     * 精准豁免 X-Tenant-Id 头，保留 X-Trace-Id 与自定义应用头
     */
    @Operation(summary = "管理员登录认证")
    @IgnoreGlobalHeaders({"X-Tenant-Id"})
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("mock-jwt-token-12345");
    }

    /**
     * 无参全量豁免所有全局请求头，保持公开接口绝对纯净
     */
    @Operation(summary = "公开系统心跳探针")
    @IgnoreGlobalHeaders
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }
}
```

### 2. 业务领域状态码字典 SPI (`ResultCodeProvider`)
各领域业务模块通过实现 `ResultCodeProvider` 接口并注册为 Spring Bean，系统将自动汇总追加至全局文档 `Info.description`：
```java
@Component
public class SampleResultCodeProvider implements ResultCodeProvider {

    public enum SampleBusinessCode implements IResultCode {
        ARTICLE_AUDIT_PENDING(8001, "文章正在审核中"),
        USER_ACCOUNT_LOCKED(8002, "用户账号已被风控锁定");

        private final Integer code;
        private final String msg;

        SampleBusinessCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }

    @Override
    public String getTitle() {
        return "### 样例工程自定义业务状态码字典 (Sample Result Codes)";
    }

    @Override
    public Collection<? extends IResultCode> getResultCodes() {
        return Arrays.asList(SampleBusinessCode.values());
    }

    @Override
    public String resolveScenario(IResultCode resultCode) {
        if (resultCode == SampleBusinessCode.ARTICLE_AUDIT_PENDING) {
            return "敏感词拦截或新发布内容进入人工审核队列";
        }
        if (resultCode == SampleBusinessCode.USER_ACCOUNT_LOCKED) {
            return "密码输错超限或异常地理位置登录触发风控冻结";
        }
        return resultCode.getMsg();
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
```

### 3. 通用 SecuritySchemes 配置化与 Swagger UI 锁头激活
在无需引入重量级安全框架时，直接通过配置驱动注册标准 HTTP Bearer (JWT) 或 APIKey 方案并激活右上角 Authorize 锁头：
```yaml
ateng:
  boot:
    openapi:
      security-schemes:
        - name: "bearerAuth"
          type: "http"
          scheme: "bearer"
          bearer-format: "JWT"
          description: "通用 JWT 认证方案"
```

### 4. 离线契约自动化导出 (`OpenApiExportHelper`)
在单元测试、本地 CLI 或 CI/CD 流水线中，通过门面类提取指定分组的格式化 JSON / YAML：
```java
// 1. 静态方式导出指定分组 JSON
String adminJson = OpenApiExportHelper.exportJson("admin");

// 2. 静态方式导出默认分组 YAML
String yamlContent = OpenApiExportHelper.exportYaml();

// 3. 流式写入文件或 HTTP 响应
try (FileOutputStream fos = new FileOutputStream("target/openapi-admin.json")) {
    OpenApiExportHelper.export("admin", OpenApiFormat.JSON, fos);
}
```

同时，当配置 `ateng.boot.openapi.export.enabled: true` 时，系统自动暴露受管下载端点：
- `GET /docs/export/json` 与 `GET /docs/export/yaml`（默认全量分组）
- `GET /docs/export/{group}.json` 与 `GET /docs/export/{group}.yaml`（指定业务分组）
自动携带 `Content-Disposition: attachment; filename="openapi-{group}.json"` 响应头。

---

## 维度三：核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 生产环境物理切断 (HTTP 404 Cutoff)
- **行为机制**：当设置 `ateng.boot.openapi.enabled: false` 时，框架物理卸载 OpenAPI 相关 Bean，同时 `OpenApiGuardFilter` 在前沿阻断所有 `/swagger-ui/**`、`/docs`、`/v3/api-docs/**` 与 `/docs/export/**` 请求并直接响应 HTTP 404。
- **安全保障**：黑客与外部爬虫扫描端点时无法探测到任何契约元数据或页面，杜绝生产信息泄露。

### 2. 五模生产安全守卫与 CIDR 前置防火墙
- **五大认证模式 (`ateng.boot.openapi.guard.mode`)**：
  - `NONE`：默认免密模式，适合本地与开发环境；
  - `BASIC`：HTTP 401 `WWW-Authenticate: Basic` 响应头质询，恒定时间 (`MessageDigest.isEqual`) 防时序攻击；
  - `SATOKEN`：弱依赖联动 Sa-Token 会话，未登录用户拒绝访问返回 401；
  - `TOKEN`：静态令牌标头 (`X-Doc-Token`) 或可选 URL Query 参数 (`?doc-token=xxx`) 比对，专为 CI/CD 自动化构建设计；
  - `IP_WHITELIST`：纯 IP 白名单模式，非授权 IP 响应 403 Forbidden。
- **反代受信任标头深度防护 (`trusted-header`)**：
  在配置 `guard.ip-whitelist.trusted-header`（如 `X-Forwarded-For`）时解析反向代理多级 IP；**未配置时严格读取物理连接 IP (`request.getRemoteAddr()`)**，杜绝公网环境下客户端随意伪造 Header 绕过白名单。

### 3. 强类型模型转换与契约保真机制
- **`IBaseEnum` 枚举保真**：基于 Core 模块通用枚举规范，自动提取 `getCode()` 运行时数据类型（Integer 映射为 `integer`，String 映射为 `string`），自动填充选项合法值与 Markdown 字典列表，消除无量纲裸数值与常数名失真。
- **`ValidList<E>` 数组转换**：拦截泛型包装类（ADR-0027），强制转换为原生 Array JSON 契约 `[ { item } ]`，消除默认反射导致的泛型擦除与 `{ "list": [...] }` 属性层级歧义。
- **`@FieldSensitive` 脱敏警示**：探测出参视图字段上的脱敏注解，自动注入 `[脱敏字段: xxx掩码]` 警示前缀与标准脱敏示例（如 `138****5678`），确保调用方对出界数据格式认知 100% 对齐。

---

## 维度四：物理排版与代码全真验证 (Evergreen & Zero-Glitch)

- **真实场景样例工程**：[`ateng-boot-samples/ateng-boot-sample-openapi`](../ateng-boot-samples/ateng-boot-sample-openapi)
- **端到端集成测试**：[`OpenApiSampleEndToEndTest.java`](../ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiSampleEndToEndTest.java)
- **生产安全守卫测试**：[`OpenApiGuardIntegrationTest.java`](../ateng-boot-samples/ateng-boot-sample-openapi/src/test/java/io/github/atengk/boot/sample/openapi/OpenApiGuardIntegrationTest.java)
- **架构决策记录**：[ADR-0068: OpenAPI 全场景契约治理加固与安全守卫架构决策](../docs/adr/0068-openapi-full-scenario-hardening-and-security-governance-architecture.md)
