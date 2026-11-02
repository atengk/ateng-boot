# ateng-boot-sample-admin 场景沙箱

## 1. 场景定位
本样例工程提供 Spring Boot Admin (SBA) 3.3.4 企业级监控运维中心 Starter（`ateng-boot-admin`）的端到端开箱即用沙箱环境：
- 内置高颜值拟物化原生登录页，结合 Sa-Token 路由过滤守卫看板（零 Spring Security）；
- 注册防火墙 `AdminSsrfGuardFilter` 配合无损流包装阻断云元数据与非法内网渗透；
- 动态实例准入策略 SPI（`AdminInstanceAdmissionPolicy`）实现服务名规范审查与 VPC 网络隔离；
- 控制台只读安全守卫（`AdminWriteGuardFilter`）物理拦截针对 Actuator 端点的高危变更操作；
- 异构微服务 Actuator 出站抓取多模态凭据自适应推导（Basic Auth / Bearer Auth / X-Actuator-Token）；
- 容器云离线僵尸实例自动清理调度器（`ZombieInstanceCleanupScheduler`）周期性驱逐并排空失联 Pod；
- 在线动态修改 Logback 日志级别；
- 30 秒防抖队列、K8s 优雅停机发版静默、黑白名单过滤与富文本故障探针诊断卡片呈现。

---

## 2. 快速启动与体验

### 2.1 配置文件 (`application.yml`)
```yaml
server:
  port: 8080

spring:
  application:
    name: ateng-boot-sample-admin
  boot:
    admin:
      context-path: /admin

ateng:
  boot:
    admin:
      server:
        enabled: true
        username: admin
        password: admin
        client-secret: CLUSTER_SECRET_DEMO_2026
        default-actuator-token: DEFAULT_ACTUATOR_TOKEN_DEMO
        debounce-duration: 30s
        allow-local-instances: true
        cleanup:
          enabled: true
          interval: 1h
          zombie-threshold: 24h
    observability:
      security:
        token: CLIENT_ACTUATOR_TOKEN_DEMO
      admin-client:
        enabled: true
        client-secret: CLUSTER_SECRET_DEMO_2026
```

### 2.2 启动应用
运行主类 `AdminSampleApplication.java` 或通过 Maven 启动：
```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-admin
```

### 2.3 核心体验链路
1. 打开浏览器访问 `http://localhost:8080/admin`，未登录自动 302 重定向至 `/admin/login`；
2. 输入默认账号密码 `admin / admin`，认证通过后进入 SBA 控制台首页；
3. 客户端微服务引入 `ateng-boot-observability` 与 SBA Client 时，自动携带 `X-Admin-Client-Secret` 完成握手注册；
4. 在控制台“日志配置”面板可实时动态切换各包日志级别（如 `INFO` -> `DEBUG`）；
5. 开启 `enable-write-operations: false` 后，所有对 Actuator 的写操作均返回 `403 Forbidden`。

---

## 3. 端到端集成测试场景清单

测试类 [AdminSampleIntegrationTest.java](./src/test/java/io/github/atengk/boot/sample/admin/AdminSampleIntegrationTest.java) 实现了 12 大端到端全场景实测覆盖（支持离线自洽与 Local 真实模式双模切换）：

| 场景编号 | 场景名称 | 测试核心行为与验证点 |
| :--- | :--- | :--- |
| **场景 1** | 未登录访问 302 重定向与原生登录页 | 未登录访问 `/admin` 返回 302 重定向至 `/admin/login`；访问登录页返回包含现代化样式的原生 HTML |
| **场景 2** | 原生登录表单 POST 凭据认证 | 提交合法账密 `admin / admin` 返回 302 并写入 `satoken` Cookie；错误账密返回 401 |
| **场景 3** | 恶意云元数据注册阻断 | 上报 `http://169.254.169.254/latest/meta-data/` 被 `AdminSsrfGuardFilter` 拦截，响应 403 Forbidden |
| **场景 4** | 双向安全握手穿透与 Token 映射 | 客户端携带 `X-Admin-Client-Secret` 注册成功，服务端出站自动提取 `actuator-token` 注入标头 |
| **场景 5** | 在线动态修改日志级别 | 通过 Logback 接口在线将包日志级别动态调整为 `DEBUG` 并断言生效 |
| **场景 6** | 优雅下线发版静默与告警防抖自愈 | 收到注销事件进入 60 秒发版静默缓存；故障状态进入 30 秒防抖队列；防抖期内自愈 UP 则撤销告警 |
| **场景 7** | 控制台只读安全守卫与写操作拦截 | `enableWriteOperations=false` 时，拦截针对 Actuator 的 `POST`/`PUT`/`DELETE`/`PATCH` 请求返回 403，放行 GET 读请求与生命周期注册 |
| **场景 8** | 动态实例注册准入扩展点 SPI | 自定义 `AdminInstanceAdmissionPolicy` 审查服务名，非法服务名拒绝响应 403，合法服务放行 |
| **场景 9** | Actuator 出站抓取多模态认证推导 | 自适应解析实例元数据中的 `user.name`/`user.password` 合成 `Basic` 标头，解析 `bearer-token` 合成 `Bearer` 标头 |
| **场景 10** | 离线僵尸实例自动清理调度器 | 模拟超期处于 `OFFLINE` 状态的僵尸实例，调度器自动将其注销并断言其实际从持久层注销 |
| **场景 11** | 告警规则名单过滤与探针富文本诊断 | 验证 `excluded-services` 静默、`included-services` 过滤、`notify-statuses` 状态过滤，以及生成的 `CardMessage` 包含探针错误根因并对密码脱敏 |
| **场景 12** | Local 真实模式跨网络进程注册与抓取 | 激活 `local` Profile 时，受管拉起同级客户端工程 `ateng-boot-demo-client`（8081），验证真实跨网络注册、出站 Basic Auth 抓取穿透与 Webhook 探测 |

---

## 4. Local 真实模式联调指南

如需在本地直连真实客户端微服务与群机器人 Webhook：
1. **准备同级客户端微服务**：同级目录 `../ateng-boot-demo-client` 已内置极简微服务工程；
2. **配置本地凭据**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Webhook（已被 `.gitignore` 忽略，零机密泄露风险）；
3. **运行全真联调测试**：
   ```bash
   mvn test -pl ateng-boot-samples/ateng-boot-sample-admin "-Dspring.profiles.active=local" -T 1C -nsu -B
   ```
   测试套件将自动管理 8081 客户端子进程生命周期，完成真实网络调用并在测试完成后确定性销毁。
