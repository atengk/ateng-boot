# 坚持显式 API 响应契约，拒绝 ResponseBodyAdvice 自动包装

自动响应包装切面（如 `ResponseBodyAdvice`）会导致返回 String 时的 `ClassCastException` 异常、意外污染 Swagger/Actuator 端点以及破坏文件下载流。我们决定强制 Controller 显式声明返回 `Result<T>`，绝不搞全局隐式自动包装，以保证前后端契约透明、代码自解释且杜绝底层类型转换副作用。
