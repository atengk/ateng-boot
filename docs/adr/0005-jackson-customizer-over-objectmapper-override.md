# 采用 Jackson2ObjectMapperBuilderCustomizer 挂载定制而非覆盖 ObjectMapper

在 Starter 中直接注入 `@Bean public ObjectMapper objectMapper()` 会彻底覆盖 Spring Boot 官方自动配置，导致模块自动发现、参数名绑定与国际化机制失效。我们决定统一通过向容器注册 `Jackson2ObjectMapperBuilderCustomizer` 实现 Long 转 String（防前端雪花精度丢失）与 Java 8 全量日期东八区格式化，保证生态绝对非侵入。
