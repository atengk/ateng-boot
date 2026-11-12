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
| **时间、雪花 ID 与高精度金额格式化** | `WebContractAndExceptionController` | `GET /sample/web/contract/datetime` | `curl "http://localhost:8080/sample/web/contract/datetime"` (校验 `BigDecimal("1E+7")` 输出为平原十进制 `10000000`) |
| **枚举强类型双向反序列化** | `WebContractAndExceptionController` | `POST /sample/web/contract/enum-bind` | `curl -X POST "http://localhost:8080/sample/web/contract/enum-bind" -H "Content-Type: application/json" -d "{\"username\":\"王五\",\"statusCode\":1,\"status\":\"ACTIVE\",\"orderType\":\"normal\"}"` |
| **单枚举字典查询 (只读端点)** | `EnumDictionaryController` (内置) | `GET /api/system/enums/userStatus` | `curl "http://localhost:8080/api/system/enums/userStatus"` |
| **全量枚举字典索引 (只读端点)** | `EnumDictionaryController` (内置) | `GET /api/system/enums` | `curl "http://localhost:8080/api/system/enums"` |
| **批量聚合枚举字典 (只读端点)** | `EnumDictionaryController` (内置) | `POST /api/system/enums/batch` | `curl -X POST "http://localhost:8080/api/system/enums/batch" -H "Content-Type: application/json" -d "[\"userStatus\",\"orderType\"]"` |
| **受控业务异常 (轻量日志)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/business` | `curl "http://localhost:8080/sample/web/contract/error/business"` |
| **因果链底层驱动异常 (完整堆栈)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/causal` | `curl "http://localhost:8080/sample/web/contract/error/causal"` |
| **未知系统异常 (生产强制脱敏)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/unexpected` | `curl "http://localhost:8080/sample/web/contract/error/unexpected"` |
| **支付通道领域异常 (SPI 转译)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/payment` | `curl "http://localhost:8080/sample/web/contract/error/payment"` |
| **媒体类型约束验证 (415/406)** | `WebContractAndExceptionController` | `POST /sample/web/contract/error/media-type` | `curl -X POST "http://localhost:8080/sample/web/contract/error/media-type" -H "Content-Type: text/plain" -d "invalid-type"` |
| **分片文件上传参数缺失/正常校验** | `WebContractAndExceptionController` | `POST /sample/web/contract/error/upload` | `curl -X POST "http://localhost:8080/sample/web/contract/error/upload" -F "file=@README.md"` |
| **大文件超限异常 (413 拦截)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/payload-too-large` | `curl "http://localhost:8080/sample/web/contract/error/payload-too-large"` |
| **异步处理超时 (503 拦截)** | `WebContractAndExceptionController` | `GET /sample/web/contract/error/async-timeout` | `curl "http://localhost:8080/sample/web/contract/error/async-timeout"` |
| **协议契约解包视图** | `WebContractAndExceptionController` | `GET /sample/web/contract/unpack` | `curl "http://localhost:8080/sample/web/contract/unpack"` |

### 3.2 全链路追踪、请求体缓存与双向可观测审计 (`WebObservabilityAndAuditController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **全链路 TraceId 提取** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/trace` | `curl "http://localhost:8080/sample/web/observability/trace"` |
| **自定义 TraceId 传入** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/trace` | `curl "http://localhost:8080/sample/web/observability/trace" -H "X-Trace-Id: custom-trace-888"` |
| **虚拟线程与 Trace 状态探测** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/virtual-thread` | `curl "http://localhost:8080/sample/web/observability/virtual-thread"` |
| **请求体多次重复读取验证** | `WebObservabilityAndAuditController` | `POST /sample/web/observability/body-cache` | `curl -X POST "http://localhost:8080/sample/web/observability/body-cache" -H "Content-Type: application/json" -d "{\"action\":\"test-cache\",\"amount\":100}"` |
| **双向报文采样与凭据掩码** | `WebObservabilityAndAuditController` | `POST /sample/web/observability/payload-echo` | `curl -X POST "http://localhost:8080/sample/web/observability/payload-echo" -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"Secret123\",\"token\":\"tok-xyz\"}"` |
| **免采样白名单路径验证** | `WebObservabilityAndAuditController` | `POST /sample/web/observability/payload-excluded` | `curl -X POST "http://localhost:8080/sample/web/observability/payload-excluded" -H "Content-Type: application/json" -d "{\"exempt\":\"data\"}"` |
| **新增操作员审计 (动态脱敏)** | `WebObservabilityAndAuditController` | `POST /sample/web/observability/audit/operator` | `curl -X POST "http://localhost:8080/sample/web/observability/audit/operator" -H "Content-Type: application/json" -d "{\"username\":\"operator_a\",\"password\":\"AdminPass123\",\"email\":\"op@example.com\",\"phone\":\"13812345678\"}"` |
| **查询操作员审计记录** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/audit/operator/10086` | `curl "http://localhost:8080/sample/web/observability/audit/operator/10086"` |
| **操作异常审计状态捕获** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/audit/error` | `curl "http://localhost:8080/sample/web/observability/audit/error"` |
| **接口调用耗时日志审计** | `WebObservabilityAndAuditController` | `POST /sample/web/observability/weblog` | `curl -X POST "http://localhost:8080/sample/web/observability/weblog" -H "Content-Type: application/json" -d "{\"username\":\"audited_user\",\"password\":\"pass456\"}"` |
| **慢报表查询耗时告警模拟** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/slow-query` | `curl "http://localhost:8080/sample/web/observability/slow-query?delayMs=60"` |
| **慢请求白名单免告警放行** | `WebObservabilityAndAuditController` | `GET /sample/web/observability/slow-excluded` | `curl "http://localhost:8080/sample/web/observability/slow-excluded?delayMs=60"` |

### 3.3 接口防刷限流与防重幂等治理 (`WebTrafficGovernanceController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **全局默认接口限流 (2次/10秒)** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/ratelimit/default` | `curl "http://localhost:8080/sample/web/traffic/ratelimit/default"` |
| **短信验证码 IP 限流防刷与 XFF 防伪造** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/ratelimit/ip` | `curl -H "X-Forwarded-For: 203.0.113.50" "http://localhost:8080/sample/web/traffic/ratelimit/ip"` (非授信代理仅取直连 `remoteAddr` 限流；本机已在 `trusted-proxies` 白名单内可解析代理链) |
| **登录用户配额限流 (2次/10秒)** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/ratelimit/user` | `curl "http://localhost:8080/sample/web/traffic/ratelimit/user"` |
| **IP 与租户复合维度限流** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/ratelimit/composite` | `curl "http://localhost:8080/sample/web/traffic/ratelimit/composite?tenantId=tenant-A"` |
| **SaaS 多租户原生隔离限流** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/ratelimit/tenant` | `curl "http://localhost:8080/sample/web/traffic/ratelimit/tenant"` |
| **电商订单支付 SpEL 防重** | `WebTrafficGovernanceController` | `POST /sample/web/traffic/idempotent/order` | `curl -X POST "http://localhost:8080/sample/web/traffic/idempotent/order" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD_1001\",\"amount\":99.9}"` |
| **表单特征指纹自动哈希防抖** | `WebTrafficGovernanceController` | `POST /sample/web/traffic/idempotent/button` | `curl -X POST "http://localhost:8080/sample/web/traffic/idempotent/button?formToken=TOKEN_A1B2"` |
| **营销优惠券领取精准防重** | `WebTrafficGovernanceController` | `POST /sample/web/traffic/idempotent/coupon` | `curl -X POST "http://localhost:8080/sample/web/traffic/idempotent/coupon?couponCode=CPN_8888"` |
| **业务异常幂等锁自动释放** | `WebTrafficGovernanceController` | `POST /sample/web/traffic/idempotent/fail-retry` | `curl -X POST "http://localhost:8080/sample/web/traffic/idempotent/fail-retry?bizId=BIZ_001&fail=false"` |
| **幂等锁定状态主动探针** | `WebTrafficGovernanceController` | `GET /sample/web/traffic/idempotent/is-locked` | `curl "http://localhost:8080/sample/web/traffic/idempotent/is-locked?key=order:pay:ORD_1001"` |
| **限流状态重置管理** | `WebTrafficGovernanceController` | `POST /sample/web/traffic/ratelimit/reset` | `curl -X POST "http://localhost:8080/sample/web/traffic/ratelimit/reset?key=rate_limit:default:/sample/web/traffic/ratelimit/default"` |

### 3.4 传输安全防护网、双向加签与边界防火墙 (`WebSecurityAndSignController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **实时计算签名头 (一键体验利器)** | `WebSecurityAndSignController` | `POST /sample/web/security/sign/prepare-headers` | `curl -X POST "http://localhost:8080/sample/web/security/sign/prepare-headers" -H "Content-Type: application/json" -d "{\"uri\":\"/sample/web/security/sign/orders\",\"body\":\"{\\\"orderNo\\\":\\\"ORD_001\\\",\\\"amount\\\":99.9}\"}"` |
| **商户提单双向加验签 (HMAC-SHA256)** | `WebSecurityAndSignController` | `POST /sample/web/security/sign/orders` | `curl -X POST "http://localhost:8080/sample/web/security/sign/orders" -H "Content-Type: application/json" -H "X-App-Id: test-app" -H "X-Timestamp: <ts>" -H "X-Nonce: <nonce>" -H "X-Signature: <sig>" -d "{\"orderNo\":\"ORD_001\",\"amount\":99.9}"` (先调 `/prepare-headers` 生成动态签名头) |
| **国密 SM2/SM3 双向加验签** | `WebSecurityAndSignController` | `POST /sample/web/security/sign/sm2` | `curl -X POST "http://localhost:8080/sample/web/security/sign/sm2" -H "Content-Type: application/json" -H "X-App-Id: test-app" -H "X-Timestamp: <ts>" -H "X-Nonce: <nonce>" -H "X-Signature: <sig>" -d "{\"orderNo\":\"ORD_SM2_001\",\"amount\":199.0}"` (需在 `sm2` 签名配置与公钥/私钥模式下测试) |
| **GET 查询参数签名验证** | `WebSecurityAndSignController` | `GET /sample/web/security/sign/query` | `curl "http://localhost:8080/sample/web/security/sign/query?keyword=vip"` (需配合 `/prepare-headers` 携带请求签名头) |
| **仅入站验签 (关闭响应回签)** | `WebSecurityAndSignController` | `POST /sample/web/security/sign/inbound` | `curl -X POST "http://localhost:8080/sample/web/security/sign/inbound" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD_IN_001\",\"amount\":50.0}"` (需配合签名头触发入站验签) |
| **敏感报表出站强制加签** | `WebSecurityAndSignController` | `GET /sample/web/security/sign/outbound` | `curl "http://localhost:8080/sample/web/security/sign/outbound"` |
| **资讯发布与富文本豁免 (XSS)** | `WebSecurityAndSignController` | `POST /sample/web/security/xss/article` | `curl -X POST "http://localhost:8080/sample/web/security/xss/article" -H "Content-Type: application/json" -d "{\"title\":\"<script>alert(1)</script>安全资讯\",\"content\":\"<p>富文本HTML内容</p>\",\"author\":\"admin\"}"` |
| **类级 XSS 豁免公告发布** | `WebSecurityAndSignController` | `POST /sample/web/security/xss/announcement` | `curl -X POST "http://localhost:8080/sample/web/security/xss/announcement" -H "Content-Type: application/json" -d "{\"title\":\"<h1>全站系统公告</h1>\",\"content\":\"<div>全站公告富文本</div>\"}"` |
| **Query 搜索参数 XSS 清洗** | `WebSecurityAndSignController` | `GET /sample/web/security/xss/query` | `curl "http://localhost:8080/sample/web/security/xss/query?keyword=%3Cscript%3Ealert(1)%3C/script%3E关键词"` |
| **Form 表单参数 XSS 清洗** | `WebSecurityAndSignController` | `POST /sample/web/security/xss/form` | `curl -X POST "http://localhost:8080/sample/web/security/xss/form" -d "title=<script>alert(1)</script>标题&summary=<b>摘要</b>"` |
| **请求头 XSS 安全清洗验证** | `WebSecurityAndSignController` | `GET /sample/web/security/xss/header` | `curl "http://localhost:8080/sample/web/security/xss/header" -H "X-Custom-Title: <script>alert(1)</script>"` |
| **白名单 URL 排除 XSS 清洗** | `WebSecurityAndSignController` | `POST /sample/web/security/xss/excluded/article` | `curl -X POST "http://localhost:8080/sample/web/security/xss/excluded/article" -H "Content-Type: application/json" -d "{\"title\":\"<script>alert(1)</script>免洗标题\",\"content\":\"<p>原文内容</p>\",\"author\":\"admin\"}"` |
| **方法级 IP 白名单放行** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/whitelist` | `curl "http://localhost:8080/sample/web/security/ip/whitelist"` |
| **方法级 IP 黑名单校验** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/blacklist` | `curl "http://localhost:8080/sample/web/security/ip/blacklist"` |
| **CIDR 子网掩码与 IPv6 网段** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/cidr-ipv6` | `curl "http://localhost:8080/sample/web/security/ip/cidr-ipv6"` |
| **自定义拒绝文案 (HTTP 200)** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/custom-message` | `curl "http://localhost:8080/sample/web/security/ip/custom-message"` |
| **物理 403 切断模式** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/http-403` | `curl -i "http://localhost:8080/sample/web/security/ip/http-403"` |
| **路径级 IP 过滤器受保护管理页** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/filter-admin/dashboard` | `curl "http://localhost:8080/sample/web/security/ip/filter-admin/dashboard"` (需开启 `ip-access-control.filter.enabled=true`) |
| **路径级 IP 过滤器排除放行探测** | `WebSecurityAndSignController` | `GET /sample/web/security/ip/filter-admin/public-ping` | `curl "http://localhost:8080/sample/web/security/ip/filter-admin/public-ping"` |
| **业务安全响应头防覆盖** | `WebSecurityAndSignController` | `GET /sample/web/security/headers/custom` | `curl -i "http://localhost:8080/sample/web/security/headers/custom"` |
| **ETag 协商缓存与 304 校验** | `WebSecurityAndSignController` | `GET /sample/web/security/etag/dict` | `curl -i "http://localhost:8080/sample/web/security/etag/dict"` |
| **CORS 动态跨域凭证反射** | `WebSecurityAndSignController` | `GET /sample/web/security/cors/ping` | `curl -i "http://localhost:8080/sample/web/security/cors/ping" -H "Origin: http://localhost:3000"` |

### 3.5 API 多版本路由分流与 Servlet 运行时工具集 (`WebRoutingAndUtilityController` & `WebVersionV1/V2Controller`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **URL 前缀路由至 V1 版本** | `WebVersionV1Controller` | `GET /v1/sample/web/routing/member/info` | `curl "http://localhost:8080/v1/sample/web/routing/member/info"` |
| **URL 前缀路由至 V2 版本** | `WebVersionV2Controller` | `GET /v2/sample/web/routing/member/info` | `curl "http://localhost:8080/v2/sample/web/routing/member/info"` |
| **请求头分流至 V2 版本** | `WebVersionV2Controller` | `GET /sample/web/routing/member/info` | `curl "http://localhost:8080/sample/web/routing/member/info" -H "X-API-Version: 2"` |
| **缺省无头路由回退默认版本** | `WebVersionV1Controller` | `GET /sample/web/routing/member/info` | `curl "http://localhost:8080/sample/web/routing/member/info"` |
| **方法级细粒度版本覆盖 (V2.1)** | `WebVersionV2Controller` | `GET /v2.1/sample/web/routing/member/feature` | `curl "http://localhost:8080/v2.1/sample/web/routing/member/feature"` |
| **客户端真实 IP 提取** | `WebRoutingAndUtilityController` | `GET /sample/web/routing/client-ip` | `curl "http://localhost:8080/sample/web/routing/client-ip" -H "X-Forwarded-For: 203.0.113.195"` |
| **可信反向代理真实 IP 逆向提取** | `WebRoutingAndUtilityController` | `GET /sample/web/routing/client-ip/trusted` | `curl "http://localhost:8080/sample/web/routing/client-ip/trusted?trustedProxies=10.0.0.0/8" -H "X-Forwarded-For: 203.0.113.195, 10.0.0.1"` |
| **RFC 6266 安全防乱码下载** | `WebRoutingAndUtilityController` | `GET /sample/web/routing/download` | `curl -i "http://localhost:8080/sample/web/routing/download"` |
| **CIDR 子网掩码范围匹配** | `WebRoutingAndUtilityController` | `GET /sample/web/routing/ip-check` | `curl "http://localhost:8080/sample/web/routing/ip-check?ip=192.168.1.50&cidr=192.168.1.0/24"` |

### 3.6 云原生生命周期治理与安全防呆自检 (`WebCloudLifecycleController` & 内置端点)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **就绪探针与防呆自检聚合视图** | `WebCloudLifecycleController` | `GET /sample/web/lifecycle/ready-and-sanity` | `curl "http://localhost:8080/sample/web/lifecycle/ready-and-sanity"` |
| **就绪状态卡片格式化视图** | `WebCloudLifecycleController` | `GET /sample/web/lifecycle/ready-card` | `curl "http://localhost:8080/sample/web/lifecycle/ready-card"` |
| **高危配置主动防呆安全自检** | `WebCloudLifecycleController` | `GET /sample/web/lifecycle/sanity-check` | `curl "http://localhost:8080/sample/web/lifecycle/sanity-check"` |
| **K8s 优雅停机就绪探针翻转探测 (GET)** | `PreStopController` (内置) | `GET /pre-stop` | `curl -i "http://localhost:8080/pre-stop"` (返回 `inFlightRequestsAtStart` 与 `inFlightRequestsRemaining` 在途排空计数) |
| **K8s preStop 钩子触发流量排空 (POST)** | `PreStopController` (内置) | `POST /pre-stop` | `curl -i -X POST "http://localhost:8080/pre-stop"` (触发幂等排空并返回在途请求快照) |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-web,ateng-boot-samples/ateng-boot-sample-web -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-web README](../../ateng-boot-web/README.md)。
