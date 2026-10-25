# Datasource 动态多数据源与物理隔离底座

面向 SaaS 物理分库隔离、高可用主从读写分离、全库批处理巡检、运行时动态数据源生命周期治理及零明文密码安全的生产就绪多数据源中间件基础设施。

## Language

**Dynamic Routing Data Source (动态路由数据源)**:
在多数据源拓扑中，根据当前执行上下文（线程局部变量、用户租户、只读事务状态或编程式指令）透明解析并切换底层真实物理连接池的代理数据源实现。
_Avoid_: 多数据源管理器, 数据源工厂, DataSourceRouter, MultiDataSource

**Physical Multi-Tenancy (SaaS 物理多租户隔离)**:
通过将不同租户的数据分配至完全独立的物理数据库（Schema/Instance），从存储层实现 100% 物理级绝对隔离的架构模式。
_Avoid_: 租户表隔离, 字段隔离, 行级租户, 软隔离

**Tenant Routing Source (租户透明切库引擎)**:
自动拦截并提取业务请求携带的租户标识（`TenantContextHolder`），动态映射为目标租户物理数据库连接的路由引擎。
_Avoid_: 租户拦截器, 租户解析器, TenantSwitch

**Read-Write Splitting (主从读写分离)**:
将写操作（事务/增删改）自动路由至主库（Master），将无副作用只读查询自动路由至从库集群（Slaves）的数据库负载均衡机制。
_Avoid_: 主备分流, 读写路由, MasterSlaveRouter

**Read-After-Write Consistency (写后即读一致性防延迟)**:
当某个会话或线程发生写操作后，在设定的时间窗口或请求周期内，强制其后续读请求回退路由至主库，以避免主从复制延迟引发的脏读与数据不一致。
_Avoid_: 强制主库读, 主库透传, 同步等待, ForceMaster

**False DataSource Switch (事务内假切源)**:
在基于单物理连接的本地事务（如 Spring `DataSourceTransactionManager`）内部尝试切换数据源时，由于事务已锁定初次获取的连接，导致后续切源注解或指令被静默忽略而实际上依然操作旧连接的隐性数据串库缺陷。
_Avoid_: 切源失效, 事务污染, 假切换, 脏切源

**Dynamic Datasource Guard (多数据源协同守卫)**:
用于在运行时拦截并检测事务内切换数据源操作，识别假切源高危风险并触发刚性阻断或安全告警的防御性探针组件。
_Avoid_: 事务拦截器, 切源防御器, DsGuardFilter

**Dynamic Datasource Template (编程式多数据源对称门面)**:
提供对称、完备的编程式数据源切换、全库安全遍历、租户批处理巡检、动态挂载与优雅排空注销的高阶操作门面。
_Avoid_: DataSourceHelper, DynamicDsUtil, MultiDbTemplate

**Health-Aware Strategy (自愈熔断路由策略)**:
感知从库物理网络连通性与健康状态，在从库宕机或连接超时后自动隔离熔断，并在全从库宕机时安全降级回退主库的高可用路由策略。
_Avoid_: 故障转移策略, 探针策略, CircuitBreakerStrategy

**Zero-Secret Decryption (零明文凭据安全解密)**:
在动态数据源初始化与连接池创建前，对配置中的国密 SM4（`enc:sm4:`）或国际 AES（`enc:aes:`）密文密码进行内存瞬态解密，杜绝配置中心与日志泄露明文密码的安全机制。
_Avoid_: 密码脱敏, 动态密码, 密钥还原, PasswordDecrypt
