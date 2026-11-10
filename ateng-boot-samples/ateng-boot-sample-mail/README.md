# ateng-boot-sample-mail 邮件通信与场景治理体验工程

> 专注于演示与自验 `ateng-boot-mail` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleMailApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-mail
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置基于嵌入式 GreenMail SMTP 服务器与内存桩自洽配置，无需任何外部真实邮件服务器即可秒级启动与 100% 跑通测试；
- **Local 真实模式**：若需直连真实 SMTP 服务商（如 QQ 邮箱、163 邮箱或企业内部邮件网关），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实认证凭据（已被 Git 忽略，CI 零风险），激活 `local` Profile 启动即可直连测试。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **发送简单纯文本邮件** | `MailBasicController` | `POST /sample/mail/basic/simple-text` | `curl -X POST "http://localhost:8080/sample/mail/basic/simple-text" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"欢迎加入","content":"欢迎体验 ateng-boot-mail 基础设施！"}'` |
| **发送富文本 HTML 邮件** | `MailBasicController` | `POST /sample/mail/basic/html-content` | `curl -X POST "http://localhost:8080/sample/mail/basic/html-content" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"安全登录提醒","htmlContent":"<h2>安全登录通告</h2><p>您的账号于新设备完成登录，若非本人操作请立即核验。</p>"}'` |
| **发送动态 Thymeleaf 模板邮件** | `MailBasicController` | `POST /sample/mail/basic/template` | `curl -X POST "http://localhost:8080/sample/mail/basic/template" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"邮箱验证码","username":"Ateng","verifyCode":"689102"}'` |
| **多语言国际化验证码模板发信** | `MailBasicController` | `POST /sample/mail/basic/i18n-template` | `curl -X POST "http://localhost:8080/sample/mail/basic/i18n-template?language=en_US" -H "Content-Type: application/json" -d '{"to":"user@example.com","username":"Alex","verifyCode":"952701"}'` |
| **发送流式电子账单附件邮件** | `MailMediaController` | `POST /sample/mail/media/attachment` | `curl -X POST "http://localhost:8080/sample/mail/media/attachment" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"月度电子账单","content":"请查收附件中的电子账单明细。","fileName":"statement-202610.pdf"}'` |
| **正交内联资源 (Inline CID) 图文混排** | `MailMediaController` | `POST /sample/mail/media/inline` | `curl -X POST "http://localhost:8080/sample/mail/media/inline" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"企业品牌公告","title":"2026 季度企业通报"}'` |
| **指定运维通道隔离发信** | `MailAccountController` | `POST /sample/mail/account/ops-channel` | `curl -X POST "http://localhost:8080/sample/mail/account/ops-channel" -H "Content-Type: application/json" -d '{"to":"ops-team@example.com","subject":"基础设施告警","content":"只读数据库节点 CPU 利用率超 85% 预警。"}'` |
| **外部介质动态账号发信 (懒加载)** | `MailAccountController` | `POST /sample/mail/account/dynamic-send` | `curl -X POST "http://localhost:8080/sample/mail/account/dynamic-send" -H "Content-Type: application/json" -d '{"account":"dynamic-db-provider","to":"client@example.com","subject":"动态租户通知","content":"基于外部提供商懒加载账号投递专属通知。","from":"vip@atengk.org","fromName":"VIP客服组"}'` |
| **精准驱逐动态账号缓存 (单机刷新)** | `MailAccountController` | `POST /sample/mail/account/evict-cache` | `curl -X POST "http://localhost:8080/sample/mail/account/evict-cache?accountName=dynamic-db-provider"` |
| **广播动态账号驱逐领域事件 (集群刷新)** | `MailAccountController` | `POST /sample/mail/account/broadcast-evict` | `curl -X POST "http://localhost:8080/sample/mail/account/broadcast-evict?accountName=dynamic-db-provider"` |
| **管理端 SMTP 物理连通性一键测试** | `MailAccountController` | `POST /sample/mail/account/test-connection` | `curl -X POST "http://localhost:8080/sample/mail/account/test-connection?accountName=default"` |
| **批量个性化合并发送员工工资条** | `MailReliableController` | `POST /sample/mail/reliable/batch-salary` | `curl -X POST "http://localhost:8080/sample/mail/reliable/batch-salary" -H "Content-Type: application/json" -d '{"items":[{"employeeName":"张三","email":"zhangsan@example.com","salaryPeriod":"2026年10月","netSalary":18500.00},{"employeeName":"李四","email":"lisi@example.com","salaryPeriod":"2026年10月","netSalary":21000.00}]}'` |
| **受管异步发信 (Core 线程池+四维上下文)** | `MailReliableController` | `POST /sample/mail/reliable/async-send` | `curl -X POST "http://localhost:8080/sample/mail/reliable/async-send" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"异步非阻塞投递","content":"邮件由 Core 受管线程池异步外发，MDC TraceId 自动透传。"}'` |
| **本地事务感知发信 (事务提交触发外发)** | `MailReliableController` | `POST /sample/mail/reliable/transaction-commit` | `curl -X POST "http://localhost:8080/sample/mail/reliable/transaction-commit" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"订单付款成功","content":"本地事务提交成功后由 sendAfterCommit 触发邮件投递。"}'` |
| **本地事务感知发信 (事务回滚防幽灵撤销)** | `MailReliableController` | `POST /sample/mail/reliable/transaction-rollback` | `curl -X POST "http://localhost:8080/sample/mail/reliable/transaction-rollback" -H "Content-Type: application/json" -d '{"to":"user@example.com","subject":"订单异常回滚","content":"事务抛出异常回滚，邮件自动防幽灵取消投递。"}'` |
| **紧急告警发信 (优先级标头+回执+密送)** | `MailReliableController` | `POST /sample/mail/reliable/urgent-alert` | `curl -X POST "http://localhost:8080/sample/mail/reliable/urgent-alert" -H "Content-Type: application/json" -d '{"serviceName":"payment-gateway","alertLevel":"P0","errorMessage":"支付通道超时熔断","to":"oncall@example.com","requestReadReceipt":true,"archiveBcc":"audit-archive@example.com"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-mail,ateng-boot-samples/ateng-boot-sample-mail -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-mail README](../../ateng-boot-mail/README.md)。
