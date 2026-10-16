# 17. 企业级行级多租户安全隔离与自动填充架构决策

## 背景与痛点

在 SaaS 多租户架构、集团多组织协同以及共享资源平台中，确保租户间数据物理与逻辑层面的绝对隔离，是持久层的核心安全红线。传统持久层多租户方案普遍面临以下痛点：

1. **手工拼接易遗漏引发严重越权穿透 (Manual Condition Leaks)**：
   - 依赖业务开发者在所有业务 Mapper 或 SQL 中手工书写 `AND tenant_id = ?`，随着业务复杂度攀升，极易因疏忽遗漏导致跨租户数据越权泄漏；
2. **持久层与业务认证框架深度耦合 (Tight Security Coupling)**：
   - 多数自研方案将租户获取逻辑与特定 Web 会话或用户安全框架（如 Spring Security / Shiro）硬编码绑定，导致在后台定时任务、MQ 异步消息消费或跨服务 RPC 调用场景下无法复用；
3. **缺少细粒度动态忽略机制 (Rigid Ignore Mechanism)**：
   - 系统全局统计报表、平台公共字典查询、特权数据同步等特权场景常需绕过多租户过滤，传统方案往往只能被迫全量关闭多租户，或编写两套结构重复的 Mapper SQL；
4. **数据插入越权篡改与空值漏洞 (Insert Spoofing & Missing Fill)**：
   - 插入新记录时若依赖业务层手工设置租户标识，容易发生漏填或前端恶意伪造租户 ID 注入，破坏租户隔离底线；
5. **SQL 盲目字符串化致索引失效 (Type Coercion & Full Table Scan)**：
   - 若将数字型租户 ID（`BIGINT`）盲目改写为带引号的字符串字面量（如 `'1001'`），在 MySQL 等数据库中将引发列类型隐式转换，导致租户索引彻底失效并退化为全表扫描。

---

## 核心架构与决策内容

经架构推演与全面验证，`ateng-boot` 正式确立 **基于 MyBatis-Plus 3.5.17 `TenantLineInnerInterceptor` 与 AST 语法树自适应改写的企业级行级多租户安全隔离架构**，内聚于 `ateng-boot-mybatis-plus` 运行时中（包路径 `io.github.atengk.boot.mybatisplus.tenant.*`）。

```mermaid
flowchart TD
    subgraph ClientReq["业务触发 (Web / MQ / Scheduled Task)"]
        Req["执行 SQL 持久化操作 (CRUD)"]
    end

    subgraph TenantContext["租户上下文层 (TenantContextHolder)"]
        Holder["TenantContextHolder 线程局部栈\n- getTenantId(): String / Long\n- isIgnoreTenant(): boolean\n- runWithoutTenant(Supplier<T>)\n- runWithTenant(tenantId, Supplier<T>)"]
    end

    subgraph InterceptorChain["AtengTenantLineInnerInterceptor 插件拦截器"]
        CheckClosure{"TenantContextHolder\nisIgnoreTenant() == true ?"}
        CheckAnno{"Mapper 接口或方法标注\n@IgnoreTenant ? (缓存查找)"}
        CheckTable{"DefaultTenantLineHandler\nignoreTable(tableName) == true ?"}
        BuildAST["JSqlParser AST 语法树安全改写:\n纯数字 -> LongValue\n字符 -> StringValue\n追加 WHERE tenant_id = ?"]
        Bypass["短路放行 (Bypass AST Rewrite)\n执行原始 SQL"]
    end

    subgraph MetaFill["DefaultMetaObjectHandler 智能插入填充"]
        InsertDetect{"实体包含 tenantId 属性\n且当前值为 null ?"}
        AutoFill["自动从 TenantContextHolder 提取租户 ID 填充\n(支持 Long 与 String 类型自适应)"]
        Preserve["保留开发者显式赋值不覆盖"]
    end

    Req --> CheckClosure
    CheckClosure -->|是| Bypass
    CheckClosure -->|否| CheckAnno
    CheckAnno -->|是| Bypass
    CheckAnno -->|否| CheckTable
    CheckTable -->|是 (在白名单)| Bypass
    CheckTable -->|否 (普通业务表)| BuildAST
    BuildAST --> Req
    Req -->|Insert 操作| InsertDetect
    InsertDetect -->|是| AutoFill
    InsertDetect -->|否| Preserve
```

### 1. 租户上下文解耦与受控编程式闭包
- **独立上下文契约**：定义 `TenantContextHolder`，采用基于 `ArrayDeque` 栈状态的 `ThreadLocal`，支持 `runWithTenant` 与 `runWithoutTenant` 的任意层级多层嵌套；
- **异常安全与状态恢复**：在闭包的 `finally` 块中严格弹出并还原上一层级的上下文状态，彻底杜绝异步线程池复用污染与状态篡改；
- **双类型支持**：提供 `getTenantId()`（字符串）与 `getTenantIdAsLong()`（长整型），满足不同业务领域对于主键与租户类型的诉求。

### 2. AST 语法树智能自适应与列索引保护
- **自适应表达式**：`DefaultTenantLineHandler.getTenantId()` 根据租户字符串格式智能推断：纯数字构建 `LongValue`（精确匹配 `BIGINT` 租户列，捍卫数据库索引命中与执行计划），非纯数字构建 `StringValue`；
- **配置与白名单管理**：支持 `ateng.boot.mybatis-plus.tenant.column` 自定义租户列名（默认 `tenant_id`）；`ignore-tables` 支持声明全局免过滤表清单，在比对时自动清理反引号与引号并忽略大小写。

### 3. 多层次细粒度动态忽略机制
- **声明式 `@IgnoreTenant` 注解**：支持标注于 Mapper 接口类（整类忽略）或 Mapper 接口方法（单方法忽略）；
- **元数据纳秒级缓存**：`AtengTenantLineInnerInterceptor` 内部维护 `ConcurrentHashMap<String, Boolean>`，首次反射解析后永久缓存判定结果，运行时完全零反射损耗；
- **双向短路**：当处于 `runWithoutTenant` 闭包或检测到 `@IgnoreTenant` 时，拦截器在 `beforeQuery` 与 `beforePrepare` 阶段毫秒级短路返回，跳过任何 AST 语法树解析。

### 4. 数据插入防越权自动填充
- 深度联动 `DefaultMetaObjectHandler`，插入记录时若实体拥有 `tenantId` 属性且当前值为 `null`，自动从 `TenantContextHolder` 获取当前租户 ID 并安全写入；
- 遵循防御性赋值哲学：若调用方显式为实体的 `tenantId` 赋值（如数据清洗、系统特权导入等场景），则尊重显式值不予覆盖；
- 可选提供 `TenantEntity` 标记接口，提供类型安全的编译期读写契约。

---

## 架构收益与规范保证

| 维度 | 传统自研或旧框架方案 | ateng-boot 架构实现 |
| :--- | :--- | :--- |
| **SQL 改写安全性** | 字符串/正则简单替换，易破坏复杂 SQL 或引发注入 | 基于 JSqlParser 在 AST 抽象语法树层面重写，绝对免疫 SQL 注入 |
| **依赖耦合度** | 强依赖 Web 会话或特定安全框架，定时任务/MQ 无法使用 | 上下文与 SPI 彻底解耦，单体、微服务、MQ、异步线程全场景通用 |
| **索引命中保障** | 盲目将租户参数作为带单引号的字符串输出，引发隐式类型转换全表扫 | 纯数字自动转为 `LongValue`，确保与 `BIGINT` 租户索引完美契合 |
| **细粒度短路** | 缺少运行时忽略手段，只能被迫手写重复 SQL | 支持 `@IgnoreTenant` 注解与 `runWithoutTenant` 编程式闭包双向短路 |
| **防御性自动填充** | 无自动填充或暴力覆盖已有值 | 仅在当前属性为 `null` 时按需填充，支持特权显式设值场景 |

---

## 遵循架构决策关联

- **[ADR-0005: 防御性自动装配与条件守卫](./0005-jackson-customizer-over-objectmapper-override.md)**：配置属性收敛于 `ateng.boot.mybatis-plus.tenant.*`，统一遵循 Spring Boot 3 `imports` 装配规范；
- **[ADR-0010: 传输契约与持久层解耦](./0010-transport-pagination-contract-decoupling.md)**：行级多租户隔离持久层数据，出库统一经由 `PageResult<VO>` 转换，杜绝持久层租户实体泄漏至前端；
- **[ADR-0011: 零重型外部依赖](./0011-zero-bloat-and-co-located-utilities.md)**：100% 原生 JDK 17 + Spring 6 + MyBatis-Plus 3.5.17 内置体系，零额外第三方工具库引入。
