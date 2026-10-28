# ateng-boot-sample-datasource 动态多数据源与物理隔离场景样例

> 面向 SaaS 物理分库透明切库、读写分离、写后读主防延迟、事务假切源刚性守卫、全库批处理容错巡检、动态数据源生命周期治理及零明文密码安全的端到端开箱即用沙箱。

---

## 1. 场景概述

本样例工程提供 100% 离线自洽的嵌入式多 H2 内存数据库测试沙箱，涵盖以下核心特性：
- **物理多租户透明切库**：根据请求头 `X-Tenant-Id` 或 `TenantContextHolder` 自动路由至对应租户数据库（`tenant_1001_db`、`tenant_1002_db`），支持 `FALLBACK_MASTER` 平滑回退与 `STRICT` 刚性隔离；
- **读写分离与写后读主防延迟**：读操作自动路由至读从库（`slave`），写事务 `@Transactional` 自动绑定写主库（`master`）并激活写后读主标记，时间窗口内后续读操作自动重定向回主库；
- **编程式多数据源对称门面**：使用 `DynamicDatasourceTemplate` 实现全库巡检（`forEachDataSource`）、全租户遍历（`forEachTenant`）、指定库执行（`runInDataSource`/`callInTenantDataSource`）；
- **动态生命周期受管治理**：运行时动态挂载新数据源（`addDataSource`）并执行建表操作，支持超时等待在途连接归还的优雅排空（`removeDataSource`）；
- **事务假切源刚性守卫**：在 Spring 单连接事务中尝试跨物理连接切源时，被 `DynamicDatasourceGuard` 刚性拦截阻断，彻底杜绝数据串库隐患；
- **零明文国密密码安全解密**：配置中的国密 SM4（`enc:sm4:`）与国际 AES（`enc:aes:`）密文密码在连接池创建前完成瞬态解密，杜绝明文凭据泄露。

---

## 2. 快速起步

### 2.1 本地极速构建自验

执行以下 Maven 命令即可在无外部数据库依赖的环境下完成端到端自动化测试回归：

```bash
mvn test -pl ateng-boot-datasource,ateng-boot-samples/ateng-boot-sample-datasource -am -T 1C -nsu -B
```

### 2.2 本地运行体验

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-datasource
```

启动后可使用 cURL 或 HTTP 客户端测试接口：

#### 租户 1001 下单
```bash
curl -X POST http://localhost:8080/api/orders/tenant \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 1001" \
  -d '{"orderNo":"ORD-1001-001","productName":"SaaS 旗舰商品","amount":299.00}'
```

#### 租户 1001 查询订单
```bash
curl -X GET http://localhost:8080/api/orders/tenant/ORD-1001-001 \
  -H "X-Tenant-Id: 1001"
```

#### 租户 1002 尝试跨租户查询（物理分库隔离，返回空）
```bash
curl -X GET http://localhost:8080/api/orders/tenant/ORD-1001-001 \
  -H "X-Tenant-Id: 1002"
```

#### 写后读主防延迟测试
```bash
# 1. 主库下单并激活写后读主防延迟标记
curl -X POST http://localhost:8080/api/orders/master \
  -H "Content-Type: application/json" \
  -d '{"orderNo":"ORD-MASTER-001","productName":"主库直写商品","amount":666.00}'

# 2. 发起只读查询（在 3 秒窗口期内自动重定向到主库命中数据）
curl -X GET http://localhost:8080/api/orders/read-write/ORD-MASTER-001
```
