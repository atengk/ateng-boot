# 采用 Jackson2ObjectMapperBuilderCustomizer 挂载定制而非覆盖 ObjectMapper

在 Starter 中直接注入 `@Bean public ObjectMapper objectMapper()` 会彻底覆盖 Spring Boot 官方自动配置，导致模块自动发现、参数名绑定与国际化机制失效。我们决定统一通过向容器注册 `Jackson2ObjectMapperBuilderCustomizer` 实现 Long 转 String（防前端雪花精度丢失）与 Java 8 全量日期东八区格式化，保证生态绝对非侵入。

## 增量演进：非主键数值保真例外机制 (#1163)

Jackson `SimpleModule` 为 `Long.class` / `Long.TYPE` 注册原生 `ToStringSerializer.instance` 时，由于 `ToStringSerializer` 未实现 `ContextualSerializer`，字段级 `@JsonFormat(shape = JsonFormat.Shape.NUMBER)` 会被静默忽略，从而误将 `Result.timestamp` 与 `PageResult` 分页统计要素转为 JSON 字符串。为兼顾全局防失真与标准契约数值保真：

1. **`ateng-boot-core` 显式数值保真序列器 (`NumericLongSerializer`)**：在 `Result.timestamp`、`PageResult.{total,size,current,pages}`、`PageQuery.{current,size}`、`OperateLogModel.costTime`、`SqlLogModel.{costTime,affectedRows}`、`ThreadPoolStatus.completedTaskCount` 与 `SnowflakeIdInfo` 非 `id` 元数据等非主键字段标注 `@JsonSerialize(using = NumericLongSerializer.class)`，确保即便脱离 Web 定制器（如独立 `ObjectMapper`、`JsonUtil.createLongToStringObjectMapper()`）亦确定性输出 JSON `number`；`SnowflakeIdInfo.id` 等业务/雪花 ID 字段维持 `longToString` 默认转串策略不变。
2. **上下文感知转串增强 (`ContextualSerializer`)**：升级 `JacksonCustomizer`（及对称收敛 `JsonUtil`）的长整型转串序列化器，在属性标注 `@JsonFormat(shape = JsonFormat.Shape.NUMBER / NUMBER_INT)` 时让位输出数字，消除 Swagger `ModelResolver`（`integer/int64`）与实际 JSON 响应之间的契约错位。
