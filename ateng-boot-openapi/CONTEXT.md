# OpenAPI Context

`ateng-boot-openapi` 负责基于 OpenAPI 3.0 与原生 SpringDoc 标准的接口契约治理、生产安全访问守卫拦截、全链路请求头切面透传、强类型模型增强与多业务域分组管理。

## Language

**OpenApiProperties**:
OpenAPI 3.0 / Swagger 接口契约治理与生产安全配置属性类，以 `ateng.boot.openapi` 为前缀，强类型纳管文档元数据、多环境网关 Servers、全局/自定义请求头清单、五模生产安全守卫（含 IP 白名单与 Token 凭据）、多安全方案（SecuritySchemes）、多业务分组列表及离线导出门面配置。
_Avoid_: SwaggerProperties, DocProperties, SpringDocProperties

**OpenApiGuardFilter**:
最高优先级访问守卫 Servlet 过滤器，在生产环境 `enabled=false` 时执行 404 物理切断防御渗透探测，在开启状态下支持 HTTP Basic 认证挑战、Sa-Token 会话凭据守卫、静态 Token 标头守卫及前置 CIDR / IP 白名单网络防火墙，并放行静态前端资源。
_Avoid_: SwaggerFilter, DocAuthFilter, SecurityGuardFilter

**GuardMode**:
接口文档生产访问守卫模式枚举，定义免密模式（NONE）、HTTP Basic 浏览器原生弹窗模式（BASIC）、Sa-Token 会话凭据桥接模式（SATOKEN）、CIDR/IP 白名单防火墙模式（IP_WHITELIST）与静态令牌标头模式（TOKEN）。
_Avoid_: AuthMode, SecurityMode, DocMode

**GlobalHeaderCustomizer**:
基于 SpringDoc `OperationCustomizer` 的全局请求头自动注入切面，在 AST 构建期自动向每个接口 Operation 追加全链路追踪 `X-Trace-Id`、多租户 `X-Tenant-Id` 及配置驱动的自定义全局请求头列表（如 `X-App-Version` 等），杜绝样板注解膨胀。
_Avoid_: HeaderInjector, OperationHeaderFilter, CommonParamCustomizer

**SecuritySchemesCustomizer**:
通用多鉴权方案自动化配置定制器，支持通过配置驱动向 OpenAPI `Components.securitySchemes` 注册 HTTP Bearer、ApiKey (Header/Query)、OAuth2 等通用安全方案并挂载全局安全要求，激活 Swagger UI 锁头交互。
_Avoid_: AuthSchemeCustomizer, SecurityConfigurator, SchemeRegistrar

**SaTokenSecurityCustomizer**:
弱依赖联动 Sa-Token 鉴权中心的 OpenAPI 定制器，自动在 `Components.securitySchemes` 注册 APIKey 认证方案并在全局 `OpenAPI.security` 挂载要求，激活 Swagger UI 右上角“Authorize / 锁头”弹窗。
_Avoid_: SecuritySchemeCustomizer, SaTokenDocCustomizer, SwaggerAuthCustomizer

**OpenApiModelConverter**:
基于 SpringDoc `ModelConverter` 的接口契约模型治理增强器，自动探测实现 Core 模块 `IBaseEnum` 的枚举字段并追加代码与描述全景字典、保障 `ValidList<E>` 批量入参 100% 渲染为原生 Array JSON 契约，并为 `@Sensitive` 脱敏字段无感注入掩码安全警示标签。
_Avoid_: ModelEnhancer, SchemaModifier, ModelInterceptor

**OpenApiExportHelper**:
OpenAPI 3.0 规范契约标准化离线导出门面类，提供将指定业务分组的 OpenAPI 契约高效导出为格式化 JSON / YAML 字符串、byte[] 或输出流的能力，支撑 CI/CD 自动化校验与前端 SDK 生成。
_Avoid_: SpecExporter, DocDumper, OpenApiWriter

**ResultCodeProvider**:
业务状态码字典扩展点 SPI 接口，允许各业务子模块（如订单、支付、用户等）向系统文档 `Info.description` 动态追加专属状态码字典，支持多错误码体系协同。
_Avoid_: ErrorCodeSPI, StatusDictionaryProvider, ResultCodeHook

**ResultCodeDictionaryCustomizer**:
基于 Core 模块通用状态码规范及 `ResultCodeProvider` SPI 的文档字典定制器，自动向 `Info.description` 尾部追加 Markdown 格式的系统状态码全景字典，统一全链路错误码认知。
_Avoid_: ErrorCodeCustomizer, StatusDictionaryCustomizer, ResultCodeTableInjector

**@IgnoreGlobalHeaders**:
声明于 Controller 类或方法上的全局请求头豁免注解，支持精准声明忽略指定的请求头参数名称列表（如 `@IgnoreGlobalHeaders({"X-Tenant-Id"})`），或留空全量豁免所有全局头，由 `GlobalHeaderCustomizer` 识别并跳过注入。
_Avoid_: @SkipHeaders, @ExcludeHeaders, @NoGlobalHeaders

**StandardApiResponseCustomizer**:
标准 HTTP 响应语义补全定制器，自动为 Operation 补全 200/400/401/500 标准响应模型与语义说明，且防御性保留开发者自定义说明。
_Avoid_: ApiResponseFilter, CommonResponseCustomizer, ResponseDescriptor

**DynamicGroupedOpenApiRegistrar**:
配置驱动的多业务分组动态注册器，实现 `ImportBeanDefinitionRegistrar`，依据 `ateng.boot.openapi.groups` 强类型配置列表动态向 Spring 容器注册 `GroupedOpenApi` Bean，并在未配置分组时兜底注册 `default` 全量分组。
_Avoid_: GroupRegistrar, OpenApiGroupConfig, GroupedApiFactory

**OpenApiAutoConfiguration**:
OpenAPI 3.0 接口文档自动化装配入口类，由 Spring Boot 3 规范通过 `AutoConfiguration.imports` 注册，统一协调装配基础 OpenAPI 契约、/docs 友好重定向路由、安全守卫过滤器、模型转换器及各定制器 Bean。
_Avoid_: SwaggerAutoConfiguration, SpringDocAutoConfiguration, DocConfiguration
