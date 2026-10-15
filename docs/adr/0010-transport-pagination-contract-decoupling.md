# 10. 传输契约与持久层解耦：标准 PageResult 与流式转换架构决策

## 背景与痛点

在基于 MyBatis-Plus 等 ORM 框架的传统 Spring Boot 研发中，开发者常习惯于在 Controller 接口中直接将 ORM 的 `Page<T>`（如 `com.baomidou.mybatisplus.extension.plugins.pagination.Page`）作为传输载荷返回，这引发了多项严重的架构设计缺陷：

1. **持久层内部状态严重泄露**：MyBatis-Plus 的 `Page` 对象携带了诸如 `optimizeCountSql`、`searchCount`、`orders`、`countId`、`maxLimit` 等大量内部执行器状态与 SQL 优化标记。直接序列化为 JSON 会产生严重的冗余噪音，不仅污染对外 API 契约，也暴露了底层技术实现细节；
2. **传输契约与数据模型高度耦合**：当接口直接暴露持久层实体（`Page<UserEntity>`）时，实体上的内部列（如逻辑删除标记 `deleted`、乐观锁版本号 `version` 等）极易意外泄露；即使依赖 `@JsonIgnore` 防御，当需要定制字段或视图脱敏时仍难以优雅扩展；
3. **分层穿透与依赖倒置**：传输层 (Web Controller) 被迫直接依赖数据访问层 (MyBatis-Plus) 的核心类，若未来更换或混合使用其他持久层实现（如 JPA、Spring Data JDBC 或原生 SQL），接口契约将面临破坏性重构。

---

## 核心架构与决策内容

经深入推演与设计，我们决定确立**传输契约与持久层彻底解耦 (Transport Decoupling)** 原则，通过「纯净载荷 + 查询封装 + 流式适配工具」三位一体架构解决该问题：

```mermaid
flowchart LR
    Client["前端 / 外部客户端"]
    Controller["Controller 接入层"]
    Service["Service 业务层"]
    Persistence["MyBatis-Plus 持久层"]

    Client -->|PageQuery| Controller
    Controller -->|PageQuery| Service
    Service -->|PageUtil.toPage(query)| Persistence
    Persistence -->|IPage&lt;Entity&gt;| Service
    Service -->|PageResult.map() / PageUtil| Controller
    Controller -->|Result&lt;PageResult&lt;VO&gt;&gt;| Client
```

### 1. 纯净传输载荷：`PageResult<T>` (`ateng-boot-core`)
- 位于通用核心模块，**100% 零依赖任何 ORM 框架**；
- 仅保留国际标准分页 5 大核心要素：
  - `records`：当前页数据集合（标配空安全，绝不返回 `null`）；
  - `total`：总记录数；
  - `size`：单页容量（默认 10）；
  - `current`：当前页码（从 1 开始，默认 1）；
  - `pages`：总页数（支持由 total 与 size 自动计算衍生）。
- 内置流式映射 API：`<R> PageResult<R> map(Function<? super T, ? extends R> mapper)`，支持一行代码平滑完成 `Entity` 到 `VO` 的流式转换，完整保留分页元数据；
- 提供 `PageResult.of(...)` 与 `PageResult.empty()` 静态工厂方法。

### 2. 标准分页查询入参：`PageQuery` (`ateng-boot-core`)
- 封装通用的分页查询基类，标准化 `current`（默认 1）与 `size`（默认 10）参数名与默认值；
- 避免不同业务接口随心所欲命名（如 `pageNum/pageSize` vs `page/limit` vs `current/size`）。

### 3. 持久层适配门面：`PageUtil` (`ateng-boot-mybatis-plus`)
- 依运行时上下文深度内聚于 `ateng-boot-mybatis-plus` 模块；
- 提供入参适配：`PageUtil.toPage(query)`，安全将 `PageQuery` 转换为 MyBatis-Plus 的 `Page<T>`；
- 提供出参转换：`PageUtil.toPageResult(iPage)` 与带流式转换器的 `PageUtil.toPageResult(iPage, mapper)`。

---

## 后果与权衡 (Consequences & Trade-offs)

### 正向收益
- **彻底消除 ORM 噪音**：对外接口 JSON 响应仅包含纯净 5 要素，彻底消除 `optimizeCountSql` 等 ORM 噪音；
- **强制模型隔离与安全防御**：推动团队在 Service 边界建立专用的 `VO` 视图模型，杜绝数据库内部列意外暴露；
- **API 契约坚如磐石**：即使底层持久层发生方言更替或升级，对外的 `PageResult<VO>` 强类型契约始终保持零破坏性变更。

### 妥协成本
- 相比以往直接返回 `Page<Entity>`，开发者在 Controller/Service 边界需要编写一行流式转换代码（通过 `PageUtil.toPageResult(page, entity -> ...)` 或 `pageResult.map(...)`）。这一极小的语法动作换取了长期的架构洁净度与契约健壮性，权衡极其划算。
