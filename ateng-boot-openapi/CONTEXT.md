# OpenAPI Context

`ateng-boot-openapi` 负责基于 OpenAPI 3.0 与原生 SpringDoc 标准的接口契约治理、生产安全访问守卫拦截、全链路请求头切面透传与多业务域分组管理。

## Language

**OpenApiProperties**:
OpenAPI 3.0 / Swagger 接口契约治理与生产安全配置属性类，以 `ateng.boot.openapi` 为前缀，强类型纳管文档元数据、多环境网关 Servers、全局请求头开关、双模守卫凭据与多业务分组列表。
_Avoid_: SwaggerProperties, DocProperties, SpringDocProperties

**OpenApiGuardFilter**:
最高优先级访问守卫 Servlet 过滤器，在生产环境 `enabled=false` 时执行 404 物理切断防御渗透探测，在开启状态下支持 HTTP Basic 认证挑战与 Sa-Token 会话凭证守卫，并放行静态前端资源。
_Avoid_: SwaggerFilter, DocAuthFilter, SecurityGuardFilter

**GuardMode**:
接口文档生产访问守卫模式枚举，定义免密模式（NONE）、HTTP Basic 浏览器原生弹窗模式（BASIC）与 Sa-Token 会话凭据桥接模式（SATOKEN）。
_Avoid_: AuthMode, SecurityMode, DocMode

**GlobalHeaderCustomizer**:
基于 SpringDoc `OperationCustomizer` 的全局请求头自动注入切面，在 AST 构建期自动向每个接口 Operation 追加全链路追踪 `X-Trace-Id` 与多租户 `X-Tenant-Id` 请求头参数，杜绝样板注解膨胀。
_Avoid_: HeaderInjector, OperationHeaderFilter, CommonParamCustomizer

**SaTokenSecurityCustomizer**:
弱依赖联动 Sa-Token 鉴权中心的 OpenAPI 定制器，自动在 `Components.securitySchemes` 注册 APIKey 认证方案并在全局 `OpenAPI.security` 挂载要求，激活 Swagger UI 右上角“Authorize / 锁头”弹窗。
_Avoid_: SecuritySchemeCustomizer, SaTokenDocCustomizer, SwaggerAuthCustomizer

**ResultCodeDictionaryCustomizer**:
基于 Core 模块通用状态码规范的文档字典定制器，自动向 `Info.description` 尾部追加 Markdown 格式的系统状态码全景字典，统一全链路错误码认知。
_Avoid_: ErrorCodeCustomizer, StatusDictionaryCustomizer, ResultCodeTableInjector

**StandardApiResponseCustomizer**:
标准 HTTP 响应语义补全定制器，自动为 Operation 补全 200/400/401/500 标准响应模型与语义说明，且防御性保留开发者自定义说明。
_Avoid_: ApiResponseFilter, CommonResponseCustomizer, ResponseDescriptor

**DynamicGroupedOpenApiRegistrar**:
配置驱动的多业务分组动态注册器，实现 `ImportBeanDefinitionRegistrar`，依据 `ateng.boot.openapi.groups` 强类型配置列表动态向 Spring 容器注册 `GroupedOpenApi` Bean，并在未配置分组时兜底注册 `default` 全量分组。
_Avoid_: GroupRegistrar, OpenApiGroupConfig, GroupedApiFactory

**OpenApiAutoConfiguration**:
OpenAPI 3.0 接口文档自动化装配入口类，由 Spring Boot 3 规范通过 `AutoConfiguration.imports` 注册，统一协调装配基础 OpenAPI 契约、/docs 友好重定向路由、安全守卫过滤器及各定制器 Bean。
_Avoid_: SwaggerAutoConfiguration, SpringDocAutoConfiguration, DocConfiguration
