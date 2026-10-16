# 18. 基于 JSqlParser AST 的声明式细粒度数据权限引擎架构决策

## 背景与痛点

在企业级管理系统、政企协同与大型 SaaS 平台中，根据当前登录用户的组织架构、行政归属或角色策略对数据实施行级细粒度访问控制（如“仅看本人”、“仅看本部门”、“本部门及下属部门”、“自定义部门列表”），是持久层安全防护的核心基石。传统实现方案普遍存在以下严重痛点：

1. **手工拼接 SQL 易引发注入与维护灾难 (Manual String Concatenation & SQL Injection)**：
   - 依赖业务在 Service 或 XML 中手动拼接 `AND dept_id IN (...)`，不仅极易疏漏导致跨部门越权泄漏，且动态拼接字符串极易引发 SQL 注入漏洞；
2. **强侵入业务领域模型 (Domain Model Pollution)**：
   - 传统基于 AOP 的方案常暴力篡改查询入参对象，强制要求所有实体类或查询 DTO 继承特定的基类，严重侵入纯净的业务领域模型；
3. **复杂 SQL 语法破坏 (Syntax Corruption in Complex Queries)**：
   - 简单的正则替换或尾部追加在遇到多表关联 `JOIN`、嵌套子查询、`UNION`、`GROUP BY` 或 `ORDER BY` 时，极易引发 SQL 语法解析错误或别名混淆；
4. **与上层安全认证框架深度耦合 (Tight Security Coupling)**：
   - 缺乏标准 SPI 契约，往往将当前组织获取逻辑硬编码绑定在 Spring Security 或特定 JWT 上下文中，在定时任务、MQ 异步消息或跨系统调用时无法复用；
5. **未标注解的普通查询面临无谓的解析性能损耗 (Unnecessary AST Overhead)**：
   - 传统拦截器对系统中所有查询 SQL 一律进行 JSqlParser 全量语法树解析，在不需要数据权限控制的普通配置表或公共字典查询中产生可观的 CPU 与内存垃圾回收开销。

---

## 核心架构与决策内容

经架构推演与全面验证，`ateng-boot` 正式确立 **基于 MyBatis-Plus 3.5.17 内置 `DataPermissionInterceptor` 与 JSqlParser AST 语法树改写的企业级声明式细粒度数据权限引擎**，内聚于 `ateng-boot-mybatis-plus` 运行时中（包路径 `io.github.atengk.boot.mybatisplus.permission.*`）。

```mermaid
flowchart TD
    subgraph ClientReq["业务触发 (Controller / Service / Job)"]
        Req["执行 SQL 查询操作 (Mapper.selectList / selectPage)"]
    end

    subgraph ContextCheck["上下文放行检查 (DataPermissionContextHolder)"]
        CheckIgnore{"isIgnore() == true ?\n(runWithoutPermission 闭包)"}
        CheckAdmin{"isSuperAdmin() == true ?\n(runAsSuperAdmin 闭包/超管)"}
    end

    subgraph ScopeMetadata["声明式注解与元数据缓存 (ConcurrentHashMap)"]
        CacheHit{"scopeCache 命中\n当前 MappedStatement ?"}
        InspectAnno["反射检查 Mapper 方法与类\n@DataScope 注解元数据"]
        CheckHasScope{"存在有效 @DataScope\n且 ignore == false ?"}
    end

    subgraph AstRewrite["JSqlParser AST 语法树安全改写"]
        CheckAlias{"表别名匹配 ?\n(dataScope.alias 校验)"}
        InvokeSPI["调用 DataPermissionHandler SPI\n构造 JSqlParser Expression 表达式"]
        AppendAnd["MyBatis-Plus AST 引擎追加:\nAND (dept_id IN (...) OR user_id = ...)"]
    end

    subgraph Execution["数据库持久层执行"]
        Bypass["短路放行 (Bypass AST Rewrite)\n零解析损耗，执行原始 SQL"]
        ExecRewrite["执行改写后的安全 SQL\n(含分页 count 自动保护)"]
    end

    Req --> CheckIgnore
    CheckIgnore -->|是| Bypass
    CheckIgnore -->|否| CheckAdmin
    CheckAdmin -->|是| Bypass
    CheckAdmin -->|否| CacheHit
    CacheHit -->|未缓存| InspectAnno
    InspectAnno --> CacheHit
    CacheHit -->|命中| CheckHasScope
    CheckHasScope -->|否 (无注解或显式忽略)| Bypass
    CheckHasScope -->|是| CheckAlias
    CheckAlias -->|别名不匹配| Bypass
    CheckAlias -->|别名匹配| InvokeSPI
    InvokeSPI --> AppendAnd
    AppendAnd --> ExecRewrite
```

### 1. 声明式 `@DataScope` 注解与表别名精准匹配
- **灵活标注粒度**：支持标注于 Mapper 接口类（整接口默认生效）或具体 Mapper 方法（方法独立定制）；
- **参数化字段契约**：
  - `alias`: 目标表别名（针对多表关联 JOIN 场景显式指定如 `"d"`，单表保持默认留空）；
  - `deptColumn`: 组织部门列名，默认 `"dept_id"`；
  - `userColumn`: 用户主体列名，默认 `"user_id"`；
  - `ignore`: 显式忽略豁免，默认 `false`（方便在类上统一标注时，单个特定特权方法显式声明 `@DataScope(ignore = true)` 豁免）；
  - `type`: 自定义权限业务策略标识（供 SPI 识别不同场景）；
- **表别名防御过滤**：当 SQL 涉及多表 JOIN 时，拦截器精准比对当前 AST 表节点的别名，仅对与 `alias` 一致的表节点追加条件，杜绝跨表字段混淆报错。

### 2. 标准 SPI 契约解耦与安全参数化改写
- **高内聚 SPI 抽象**：定义 `DataPermissionHandler` SPI，解耦具体 RBAC 权限中心：
  ```java
  Expression getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope);
  ```
  业务实现只需根据当前用户组织权限返回对应的 JSqlParser AST `Expression`（如 `InExpression`、`EqualsTo` 或复合 `OrExpression`）；
- **AST 语法树级安全注入**：由 MyBatis-Plus `BaseMultiTableInnerInterceptor` 将 SPI 返回的表达式与既有 `WHERE` 条件以 `AND (...)` 形式合并，复杂表达式自动附加括号保护，100% 杜绝 SQL 注入漏洞。

### 3. 上下文管理器与受控编程式闭包
- **独立上下文契约**：提供 `DataPermissionContextHolder`，基于深度计数器（`ThreadLocal<Integer>`）实现异常安全与状态恢复：
  - `isIgnore()` / `setIgnore(boolean)`：忽略数据权限过滤；
  - `isSuperAdmin()` / `setSuperAdmin(boolean)`：标记超级管理员身份；
  - `runWithoutPermission(...)`：编程式在忽略数据权限的环境下执行代码块并在 `finally` 严格还原；
  - `runAsSuperAdmin(...)`：编程式在超管特权环境下执行代码块并在 `finally` 严格还原；
  - `clear()`：请求完结或线程复用时安全释放。

### 4. 纳秒级元数据缓存与零 AST 解析秒级短路
- **O(1) 纳秒级读取**：`AtengDataPermissionInnerInterceptor` 内部维护 `ConcurrentHashMap<String, Optional<DataScope>>` 缓存，反射解析仅在首次触发时发生一次，后续调用零反射损耗；
- **全链路短路放行机制**：
  - 未标注 `@DataScope` 的普通查询方法直接短路；
  - 处于 `runWithoutPermission` 或 `runAsSuperAdmin` 闭包直接短路；
  - 显式声明 `@DataScope(ignore = true)` 的方法直接短路；
  - 短路查询直接在 `beforeQuery` 第一行秒级 `return`，完全跳过 JSqlParser 语法树词法分析与 AST 重构，零额外性能损耗。

### 5. 严格的拦截器链装配顺序与分页自动保护
- 在 `AtengMybatisPlusAutoConfiguration` 中确立铁律装配顺序：
  $$\text{多租户 (TenantLine)} \longrightarrow \text{数据权限 (DataPermission)} \longrightarrow \text{分页插件 (Pagination)} \longrightarrow \text{防全表更新删除} \longrightarrow \text{乐观锁}$$
- **分页 count 语句同步保护**：数据权限插件必须严格位于分页插件之前。当分页查询触发自动生成的 `_mpCount` 语句时，拦截器智能识别剥离 `_mpCount` 后缀，映射回原 Mapper 方法上的 `@DataScope`，确保分页总记录数与列表数据受到完全一致的数据权限隔离保护。

---

## 架构收益与规范保证

| 维度 | 传统自研或旧框架方案 | ateng-boot 架构实现 |
| :--- | :--- | :--- |
| **SQL 改写安全性** | 字符串/正则拼接，易破坏复杂语法或引发 SQL 注入 | 基于 JSqlParser 抽象语法树安全拼接，绝对免疫 SQL 注入 |
| **领域模型侵入度** | 要求业务实体继承特权基类，侵入业务模型 | 100% 声明式 `@DataScope` 注解驱动，业务实体与 Service 零侵入 |
| **多表关联与别名** | 多表关联时别名混乱，字段可能追加到错误的关联表 | 支持 `@DataScope(alias = "...")`，AST 遍历时精准校验别名匹配 |
| **短路与性能表现** | 对所有 SQL 全量执行 AST 解析，浪费 CPU 与内存 | 无注解查询、超管查询与显式豁免直接秒级短路，零 AST 解析开销 |
| **分页总数自洽性** | 仅列表查询生效，分页 count 统计全表导致总页数虚高 | 置于分页插件之前，count 语句与列表数据受同等权限隔离改写 |
| **认证生态解耦度** | 强绑定 Spring Security 或特定 Session，异步与 MQ 无法复用 | 抽象 `DataPermissionHandler` SPI，结合 `DataPermissionContextHolder` 全场景通用 |

---

## 遵循架构决策关联

- **[ADR-0005: 防御性自动装配与条件守卫](./0005-jackson-customizer-over-objectmapper-override.md)**：配置属性收敛于 `ateng.boot.mybatis-plus.data-permission.*`，统一遵循 Spring Boot 3 `imports` 装配规范；
- **[ADR-0010: 传输契约与持久层解耦](./0010-transport-pagination-contract-decoupling.md)**：数据权限仅收敛于持久层 AST 改写，Controller 传输层统一出参 `PageResult<VO>`；
- **[ADR-0011: 零重型外部依赖](./0011-zero-bloat-and-co-located-utilities.md)**：100% 依托原生 JDK 17 与 MyBatis-Plus 3.5.17 内置 JSqlParser，零外部非标依赖；
- **[ADR-0017: 企业级行级多租户安全隔离架构](./0017-multi-tenant-row-level-isolation-architecture.md)**：与多租户插件协同运行，多租户处于外层、数据权限处于内层，形成两级行级安全防护网。
