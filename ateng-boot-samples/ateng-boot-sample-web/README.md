# ateng-boot-sample-web Web 运行时增强体验工程

> 专注于演示与自验 `ateng-boot-web` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleWebApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-web
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存桩与自洽配置，无需启动任何外部依赖即可启动运行与跑通测试；
- **Local 真实模式**：若需本地调优或连接真实 Redis 中间件体验 Tier 1 / Tier 2 分布式限流与防重幂等，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并按需调整配置，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

### 3.1 传输契约、序列化规范、异常防御与枚举字典 (`WebContractAndExceptionController` & 内置端点)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **会员实名注册 (第一道防线)** | `WebContractAndExceptionController` | `POST /sample/web/contract/user` | `curl -X POST "http://localhost:8080/sample/web/contract/user" -H "Content-Type: application/json" -d "{\"name\":\"张三\",\"age\":25,\"email\":\"zhangsan@example.com\",\"phone\":\"13812345678\"}"` |
| **渠道商批量入驻 (ValidList 校验)** | `WebContractAndExceptionController` | `POST /sample/web/contract/users/batch` | `curl -X POST "http://localhost:8080/sample/web/contract/users/batch" -H "Content-Type: application/json" -d "[{\"name\":\"渠道商A\",\"age\":30,\"email\":\"a@example.com\",\"phone\":\"13900000001\"}]"` |
| **会员信息更新 (分组校验)** | `WebContractAndExceptionController` | `PUT /sample/web/contract/user` | `curl -X PUT "http://localhost:8080/sample/web/contract/user" -H "Content-Type: application/json" -d "{\"id\":1846182948192841920,\"name\":\"李四\",\"age\":26,\"email\":\"lisi@example.com\",\"phone\":\"13800000002\"}"` |
| **时间与雪花 ID 格式化** | `WebContractAndExceptionController` | `GET /sample/web/contract/datetime` | `curl "http://localhost:8080/sample/web/contract/datetime"` |
| **枚举强类型双向反序列化** | `WebContractAndExceptionController` | `POST /sample/web/contract/enum-bind` | `curl -X POST "http://localhost:8080/sample/web/contract/enum-bind" -H "Content-Type: application/json" -d "{\"username\":\"王五\",\"statusCode\":1,\"status\":\"ACTIVE\",\"orderType\":\"normal\"}"` |
| **单枚举字典查询 (只读端点)** | `EnumDictionaryController` | `GET /api/system/enums/userStatus` | `curl "http://localhost:8080/api/system/enums/userStatus"` |
| **全量枚举字典索引 (只读端点)** | `EnumDictionaryController` | `GET /api/system/enums` | `curl "http://localhost:8080/api/system/enums"` |
| **批量聚合枚举字典 (只读端点)** | `EnumDictionaryController` | `POST /api/system/enums/batch` | `curl -X POST "http://localhost:8080/api/system/enums/batch" -H "Content-Type: application/json" -d "[\"userStatus\",\"orderType\"]"` |
| **受控业务异常 (轻量日志)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/business` | `curl "http://localhost:8080/sample/web/contract/error/business"` |
| **因果链底层驱动异常 (完整堆栈)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/causal` | `curl "http://localhost:8080/sample/web/contract/error/causal"` |
| **未知系统异常 (生产强制脱敏)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/unexpected` | `curl "http://localhost:8080/sample/web/contract/error/unexpected"` |
| **支付通道领域异常 (SPI 转译)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/payment` | `curl "http://localhost:8080/sample/web/contract/error/payment"` |
| **媒体类型约束验证 (415/406)** | `WebContractAndExceptionController` | `POST /sample/web/contract/error/media-type` | `curl -X POST "http://localhost:8080/sample/web/contract/error/media-type" -H "Content-Type: text/plain" -d "invalid-type"` |
| **大文件超限异常 (413 拦截)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/payload-too-large` | `curl "http://localhost:8080/sample/web/contract/error/payload-too-large"` |
| **异步处理超时 (503 拦截)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/async-timeout` | `curl "http://localhost:8080/sample/web/contract/error/async-timeout"` |
| **协议契约解包视图** | `WebContractAndExceptionController` | `GET /sample/web/contract/unpack` | `curl "http://localhost:8080/sample/web/contract/unpack"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-web,ateng-boot-samples/ateng-boot-sample-web -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-web README](../../ateng-boot-web/README.md)。
