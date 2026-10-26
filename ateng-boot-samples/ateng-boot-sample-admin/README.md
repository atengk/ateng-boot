# ateng-boot-sample-admin 场景沙箱

## 1. 场景定位
本样例工程提供 Spring Boot Admin (SBA) 3.3.4 企业级监控运维中心 Starter（`ateng-boot-admin`）的端到端开箱即用沙箱环境：
- 内置高颜值拟物化原生登录页，结合 Sa-Token 路由过滤守卫看板（零 Spring Security）；
- 注册防火墙 `AdminSsrfGuardFilter` 配合无损流包装阻断云元数据与非法内网渗透；
- `AtengActuatorHttpHeadersProvider` 自动从实例元数据中提取 Token 挂载出站标头，穿透客户端安全加固端点；
- 在线动态修改 Logback 日志级别；
- 30 秒防抖队列与 K8s 优雅停机发版静默告警状态机。

---

## 2. 快速启动与体验

### 2.1 配置文件
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
4. 在控制台“日志配置”面板可实时动态切换各包日志级别（如 `INFO` -> `DEBUG`）。
