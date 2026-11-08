# ateng-boot-sample-mybatis-plus 持久层增强底座体验工程

> 专注于演示与自验 `ateng-boot-mybatis-plus` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleMybatisPlusApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-mybatis-plus
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线 H2 内存数据库（`MODE=MySQL`）与 `schema.sql` 自动建表，无需启动任何外部物理数据库即可启动运行与跑通全量测试；
- **Local 真实模式**：若需连接本地或远端真实 MySQL / PostgreSQL 实例联调，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并按需修改连接参数，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验（各读取端点均内置按需幂等种子数据初始化，零前置准备即可直接调用）：

### 3.1 实体五级阶梯、生命周期自动填充与逻辑删除闭环 (`MpEntityLifecycleController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **创建用户与审计自动填充** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/user` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/user" -H "Content-Type: application/json" -d "{\"name\":\"王五\",\"age\":26,\"email\":\"wangwu@example.com\",\"phone\":\"13812345678\",\"operator\":\"auditor-alice\"}"` |
| **验证显式审计值不覆盖** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/user` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/user" -H "Content-Type: application/json" -d "{\"name\":\"陈七\",\"age\":31,\"email\":\"chenqi@example.com\",\"phone\":\"13987654321\",\"operator\":\"auditor-alice\",\"explicitCreateBy\":\"manual-creator\"}"` |
| **查询有效用户安全视图列表** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/users` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/users"` |
| **商品 SKU 联合唯一索引循环软删除** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/cycle-soft-delete` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/cycle-soft-delete?skuCode=SKU-999"` |
| **商品 SKU 回收站穿透分页查询** | `MpEntityLifecycleController` | `GET /sample/mybatis-plus/lifecycle/sku/recycle-bin` | `curl "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/recycle-bin?current=1&size=10"` |
| **按主键 ID 恢复误删商品 SKU** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/{id}/restore` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/1/restore?operator=ops-director"` |
| **按 SKU 编码批量恢复（空条件防呆拦截）** | `MpEntityLifecycleController` | `POST /sample/mybatis-plus/lifecycle/sku/restore-by-code` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/lifecycle/sku/restore-by-code?skuCode=SKU-999"` |

### 3.2 字段级透明加解密、视图动态脱敏与受管 JSON 映射 (`MpSecurityAndJsonController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **数据安全三态生命周期对比写入** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/crypto-compare` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/crypto-compare" -H "Content-Type: application/json" -d "{\"name\":\"周八\",\"age\":29,\"email\":\"zhouba@example.com\",\"phone\":\"13812345678\"}"` |
| **一键体验数据安全三态对比 (GET)** | `MpSecurityAndJsonController` | `GET /sample/mybatis-plus/security-json/crypto-compare/demo` | `curl "http://localhost:8080/sample/mybatis-plus/security-json/crypto-compare/demo"` |
| **创建会员画像与深层泛型收货地址** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/member-profile` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/member-profile" -H "Content-Type: application/json" -d "{\"memberName\":\"林九\",\"tags\":[\"架构\",\"云原生\"],\"roles\":[\"VIP\"],\"extraInfo\":{\"city\":\"Shenzhen\"},\"bio\":\"资深技术专家\",\"level\":8,\"shippingAddresses\":[{\"label\":\"公司\",\"city\":\"深圳市\",\"detailAddress\":\"南山区科技园\",\"defaultAddress\":true}]}"` |
| **查询会员画像详情 (支持种子自愈)** | `MpSecurityAndJsonController` | `GET /sample/mybatis-plus/security-json/member-profile/1` | `curl "http://localhost:8080/sample/mybatis-plus/security-json/member-profile/1"` |
| **验证 JSON 集合列 NULL 空安全保底** | `MpSecurityAndJsonController` | `POST /sample/mybatis-plus/security-json/member-profile/null-safety` | `curl -X POST "http://localhost:8080/sample/mybatis-plus/security-json/member-profile/null-safety?memberName=空集合保底用户"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-mybatis-plus,ateng-boot-samples/ateng-boot-sample-mybatis-plus -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-mybatis-plus README](../../ateng-boot-mybatis-plus/README.md)。
