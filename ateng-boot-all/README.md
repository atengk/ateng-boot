# ateng-boot-all 一键全家桶聚合套件

> 面向单体业务或快速起步项目，提供零配置、开箱即用的一键依赖打包交付。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-all` 是纯依赖聚合模块。只需引入这一个依赖，即可自动传递引入 `ateng-boot-core`、`ateng-boot-web`、`ateng-boot-mybatis-plus`、`ateng-boot-redis`、`ateng-boot-excel` 与 `ateng-boot-satoken` 全部六大核心 Starter 构件，无需手动分别声明各个模块。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-all</artifactId>
</dependency>
```

---

## 2. 传递依赖拓扑

```
ateng-boot-all
  ├── ateng-boot-core          (统一响应契约、分布式发号器、全场景脱敏、加解密工具)
  ├── ateng-boot-web           (全链路追踪、全局异常处理、自适应幂等防重、限流防刷、动态 CORS)
  ├── ateng-boot-mybatis-plus  (实体继承体系、审计填充、多租户隔离、数据权限、分批批量插入)
  ├── ateng-boot-redis         (双核融合装配、安全 JSON 序列化、四阶分布式锁、延时队列、发布订阅、增强缓存)
  ├── ateng-boot-excel         (流式防 OOM 读写、导出动态脱敏、悬浮批注回写、防呆模板、双向转义、链式构建)
  └── ateng-boot-satoken       (安全鉴权、强类型 LoginUser 会话、两级权限缓存、零信任保护、Knife4j 弹窗)
```

---

## 3. 使用场景与选型指引

- **推荐使用 `ateng-boot-all`**：
  - 新建标准单体 Spring Boot 业务应用；
  - 快速搭建原型验证系统、Demo 或中后台管理系统；
  - 团队希望最小化 `pom.xml` 依赖声明，享受一站式基础设施开箱即用体验。
- **推荐按需使用各个独立子 Starter（配合 `ateng-boot-bom`）**：
  - 微服务分布式架构工程（如只使用 core 的轻量基础库，或只需要持久层的纯批处理离线服务）；
  - 对打包体积、依赖范围有严苛精细化管控的场景。
