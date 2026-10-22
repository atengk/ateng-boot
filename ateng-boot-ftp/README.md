# ateng-boot-ftp 基础设施 Starter

> 企业级轻量 FTP / FTPS 客户端 Starter，基于 Apache Commons Net 与 Commons Pool2 受管对象池构建。提供连接池心跳保活断线自愈、双重工作目录复位防串目录、`FtpContextHolder` 线程级动态切源路由、表单与文件原子上传守护（`.tmp` + `rename`）、RFC 3659 MLSD 机器可读目录解析、级联多级目录递归创建（`mkdir -p`）、严格路径穿越安全清洗、50MB 内存防爆阈值守护与原生逃逸舱机制。

---

## 0. 特性与公共 API 全景速查矩阵 (Feature & API Panorama Matrix)

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.ftp.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础上传** | 流式/文件/字节/Resource 对称上传 | `FtpTemplate` | `enabled` | `upload(String, InputStream)` / `upload(String, File)` / `upload(String, byte[])` / `upload(String, Resource)`，全对称读写，支持流式传输与 Spring Resource 资源桥接 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **原子上传守护** | 临时文件缓冲与原子重命名 | `FtpTemplate` | `atomic-upload`<br>`temp-file-suffix` | 先写入 `.tmp` 临时文件，完整传输后原子执行 `rename`；上传中断或异常时在独立会话中物理清理残留脏文件，杜绝下游消费残缺半截文件 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **自动级联建目录** | 父级目录自动递归创建 | `FtpTemplate` | `auto-create-directories` | `makeDirectories(String)`，在写入文件或移动前自动探测并递归级联创建不存在的多级父目录（`mkdir -p` 对称能力） | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **便捷文本存取** | 字符串便捷写入与读取 | `FtpTemplate` | `control-encoding` | `writeString(String, String)` / `writeString(String, String, Charset)` / `readString(String)` / `readString(String, Charset)`，默认 UTF-8 编码，免除样板流代码 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **文件对称下载** | 输出流/本地文件/字节数组下载 | `FtpTemplate` | `max-download-bytes` | `download(String, OutputStream)` / `download(String, File)` / `downloadToBytes(String)`，内置 50MB 堆内存防爆阈值守护，超限立即熔断抛出异常 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **受管流式安全消费** | 闭包式受管输入流消费 | `FtpTemplate`<br>`Consumer<InputStream>` | - | `downloadStream(String, Consumer<InputStream>)`，强制在回调闭包内完成流消费并在 finally 确定性闭合，安全调用 `completePendingCommand` 闭合会话 | `FtpTemplateTest` |
| **路径存在探测** | 文件与目录存在性探测 | `FtpTemplate` | - | `exists(String)`，优先探测 RFC 3659 `MLST` 指令，平滑降级为 `LIST` 与 `CWD` 校验工作目录并可靠复位 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **强类型元数据获取** | 单文件/目录详细元数据 | `FtpTemplate`<br>`FtpFileInfo` | - | `getFileInfo(String)`，优先通过 RFC 3659 获取标准化文件名、绝对路径、字节大小、时间戳（`Instant`）与目录标志 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **机器可读目录解析** | RFC 3659 MLSD 目录列举 | `FtpTemplate`<br>`FtpFileInfo` | - | `listFiles(String)` / `listFiles(String, Predicate<FtpFileInfo>)` / `listDirectories(String)`，解决传统 LIST 跨操作系统语言解析格式差异，支持条件过滤 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **文件与目录删除** | 确定性删除与目录级联清空 | `FtpTemplate` | - | `deleteFile(String)` / `deleteDirectory(String)` / `deleteDirectory(String, boolean)`，物理删除文件并支持级联递归清空删除非空目录树 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **重命名与跨目录移动** | 跨路径秒级重命名与移动 | `FtpTemplate` | `auto-create-directories` | `rename(String, String)` / `move(String, String)`，同级重命名或移动到目标父目录，自动完成目标父目录探测创建 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **路径穿越安全清洗** | 路径遍历漏洞前置拦截 | `FtpTemplate` | - | `cleanAndValidatePath(String)`，统一转义分隔符，严格拦截 `..` 越权逃逸序列与 `\0` 空字符注入，防范任意文件读取或覆盖 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **原生会话逃逸舱** | 底层原生 FTPClient 会话透传 | `FtpTemplate`<br>`FtpClientCallback<T>` | - | `execute(FtpClientCallback<T>)`，从受管池安全借用原生客户端，支持执行任意底层协议指令并在 finally 绝对归还 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |
| **动态切源路由上下文** | 线程级多数据源动态切换 | `FtpContextHolder` | `servers.*` | `setServer(String)` / `getServer()` / `clear()` / `runWith(String, Runnable)` / `runWith(String, Supplier<T>)`，支持栈式嵌套作用域并在 finally 确定性复位现场 | `FtpContextHolderTest` / `SampleFtpIntegrationTest` |
| **多节点受管注册表** | 具名连接池与模板注册表 | `FtpClientRegistry` | `servers.*` | `getTemplate(String)` / `getDefaultTemplate()` / `getPool(String)` / `register(...)` / `destroy(...)` / `close()`，支持停机确定性销毁所有连接池 | `FtpClientRegistryTest` / `FtpDynamicRoutingAndAutoConfigurationTest` |
| **连接池心跳与复位** | 借还生命周期与断线自愈 | `FtpClientPooledObjectFactory`<br>`FtpPoolProperties` | `pool.*` | 基于 Commons Pool2 实现，借出归还双重 `cwd("/")` 复位防串目录；借出校验 `NOOP` 存活心跳；IO 异常主动标记 `invalidateObject` 剔除坏连接 | `FtpPoolAndFactoryTest` |
| **FTPS 安全加密传输** | SSL / TLS 传输层安全通道 | `FtpsProperties`<br>`FtpClientPooledObjectFactory` | `ftps.*` | 支持显式与隐式 FTPS（`AUTH TLS`）、支持客户端证书双向认证与 TrustStore/KeyStore 配置 | `FtpPoolAndFactoryTest` |
| **因果异常体系** | 语义化异常与统一错误码 | `FtpException`<br>`FtpResultCode` | - | 转译底层 Socket/Commons Net 异常为语义化业务异常，赋予规范错误码（`51001`~`51007`）并 100% 保留原始因果堆栈 | `FtpTemplateTest` / `SampleFtpIntegrationTest` |

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-ftp` 是面向生产环境的工业级 FTP/FTPS 协议基础设施 Starter：
- **纯原生依赖**：以 Apache Commons Net 与 Commons Pool2 为核心底座，拒绝引入任何臃肿第三方工具包；
- **长连接池化治理**：彻底终结短连接频繁握手与野线程创建，提供高并发连接池复用与心跳存活探针；
- **零目录污染不变量**：借出与归还双重执行 `cwd("/")` 复位，彻底消除传统 FTP 状态机串目录隐患；
- **传输安全与数据防护**：前置拦截路径穿越漏洞，50MB 内存防爆守护，临时文件原子写入（`.tmp` + `rename`）。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-ftp</artifactId>
</dependency>
```

---

## 2. 最小起步体验 (Happy Path & Out-of-the-Box)

### 场景痛点
业务系统通常需要与银行、政府机构或内部文件服务器通过 FTP 交换数据；传统方式需手写繁琐的 Socket 连接、目录切换、ASCII/BINARY 模式选择以及流关闭逻辑，极易引发连接泄露或目录状态错乱。

### 1. 最小可用配置
在 `application.yml` 中添加最少 4 项核心连接信息：
```yaml
ateng:
  boot:
    ftp:
      enabled: true
      host: 127.0.0.1
      port: 2121
      username: admin
      password: YOUR_PASSWORD
```

### 2. 开箱即用注入操作
```java
@Service
@RequiredArgsConstructor
public class DocumentSyncService {

    private final FtpTemplate ftpTemplate;

    // 1. 纯文本快捷写入与读取
    public void syncConfig() {
        ftpTemplate.writeString("/configs/app.json", "{\"env\":\"production\"}");
        String content = ftpTemplate.readString("/configs/app.json");
    }

    // 2. 表单文件原子上传
    public void uploadFile(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            ftpTemplate.upload("/uploads/" + file.getOriginalFilename(), in);
        }
    }

    // 3. 流式下载到响应输出流
    public void downloadToResponse(String remotePath, OutputStream out) {
        ftpTemplate.download(remotePath, out);
    }
}
```

---

## 3. 生产扩展接管 (Customization & Extensibility)

### 1. 声明式配置多数据源节点
支持在 `application.yml` 中配置主节点（default）与多个具名备用节点：
```yaml
ateng:
  boot:
    ftp:
      enabled: true
      host: 127.0.0.1
      port: 2121
      username: admin
      password: password123
      encoding: UTF-8
      passive-mode: true
      pool:
        max-total: 10
        max-idle: 8
        min-idle: 2
      servers:
        backup:
          host: 127.0.0.1
          port: 2122
          username: backup_user
          password: backup_password
          pool:
            max-total: 5
```

### 2. 编程式动态切源 (FtpContextHolder)
使用 `FtpContextHolder.runWith` 在指定的 FTP 节点下执行业务逻辑，支持栈式嵌套切换并在 `finally` 确定性复位：
```java
// 切换至 backup 备用节点上传并读取
String result = FtpContextHolder.runWith("backup", () -> {
    ftpTemplate.writeString("/backup/data.log", "Backup Content");
    return ftpTemplate.readString("/backup/data.log");
});
```

### 3. 自定义 Bean 接管 (@ConditionalOnMissingBean)
框架对外暴露的核心 Bean 均标配 `@ConditionalOnMissingBean`。开发者可在业务工程中声明自定义的 `FtpTemplate` 或 `FtpClientRegistry` 平滑覆盖：
```java
@Configuration
public class CustomFtpConfig {

    @Bean
    public FtpTemplate customFtpTemplate(FtpClientRegistry registry) {
        // 自定义包装或装饰门面
        return registry.getDefaultTemplate();
    }
}
```

---

## 4. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 1. 连接池生命周期与心跳保活
- **存活探针**：连接从池借出时，若开启 `pool.test-on-borrow=true`，工厂自动发送 `NOOP` 指令检测心跳；若服务端已主动断开，连接池平滑销毁并重新初始化物理连接，保证调用方拿到的连接 100% 可用；
- **坏连接主动剔除**：操作过程中若捕获到底层 `IOException`，当前连接状态标记为不可信，`finally` 中调用 `pool.invalidateObject(client)` 予以物理销毁，杜绝故障连接被归还再次污染连接池。

### 2. 工作目录双重复位防串目录
- FTP 协议属于有状态网络协议，`CWD`（切换工作目录）为会话级全局状态；
- `FtpClientPooledObjectFactory` 在 `passivateObject`（归还连接）与 `activateObject`（借出连接）时均强制调用 `cwd("/")`，彻底杜绝上一个业务操作切换了目录未还原而导致后续业务误写或误读。

### 3. 原子上传守护与异常清理
- **临时文件中转**：开启 `atomic-upload=true` 时，文件首先写入以 `.tmp` 为后缀的临时路径；数据完全写入且服务端确认后，原子执行 `RNFR` + `RNTO` 重命名为目标文件名；
- **异常物理清理**：若网络中断或上传抛出异常，门面在独立的干净连接会话中物理删除残留的 `.tmp` 临时文件，杜绝下游消费残缺文件。

### 4. 50MB 内存防爆阈值
- `downloadToBytes` 与 `readString` 门面内置容量防爆守护（默认阈值 50MB，由 `max-download-bytes` 配置）；
- 若文件超过安全阈值，立即熔断截断传输并抛出 `FtpException(DOWNLOAD_FAILED)`，严防大文件击穿 JVM 堆内存（大文件请使用 `download(String, OutputStream)` 或 `downloadStream`）。

### 5. 路径穿越安全防卫
- 所有文件与目录操作入口强制经过 `cleanAndValidatePath` 清洗；
- 统一替换反斜杠为正斜杠，严格防御包含 `..` 的越权逃逸序列（例如 `/../../etc/passwd`）与 `\0` 空字符截断注入，拦截即抛出 `FtpResultCode.INVALID_PATH`（`51007`）。

---

## 5. 完整配置属性速查表 (YAML Reference)

```yaml
ateng:
  boot:
    ftp:
      enabled: true                               # 是否启用 FTP 自动装配（默认 false）
      host: 127.0.0.1                             # FTP 服务端主机地址或域名（默认 localhost）
      port: 21                                    # FTP 服务端端口（标准 FTP 默认 21，隐式 FTPS 默认 990）
      username: anonymous                         # 认证用户名（默认 anonymous）
      password: ""                                # 认证密码
      mode: PASSIVE                               # 传输模式：PASSIVE(本地被动, 推荐) / ACTIVE(本地主动)
      file-type: BINARY                           # 传输文件类型：BINARY(二进制, 推荐) / ASCII(文本)
      control-encoding: UTF-8                     # 控制通道编码（默认 UTF-8，支持 GBK、ISO-8859-1）
      connect-timeout: 10s                        # 连接握手超时时间（默认 10s）
      data-timeout: 30s                           # 数据通道传输与 Socket 读取超时时间（默认 30s）
      control-keep-alive-timeout: 30s             # 控制通道空闲保活超时（传输期间自动发送 NOOP 防止防火墙截断）
      buffer-size: 64KB                           # 数据传输缓冲区大小（默认 64KB）
      max-download-bytes: 50MB                    # 内存下载最大允许容量（默认 50MB，超限熔断防 OOM）
      atomic-upload: true                         # 是否开启原子上传机制（先写入 .tmp 临时文件，成功后原子 rename）
      temp-file-suffix: .tmp                      # 原子上传临时文件后缀名（默认 .tmp）
      auto-create-directories: true               # 上传或移动时是否自动递归创建不存在的父目录（mkdir -p）
      
      # Commons Pool2 连接池精细调优
      pool:
        max-total: 8                              # 最大连接数（默认 8）
        max-idle: 8                               # 最大空闲连接数（默认 8）
        min-idle: 0                               # 最小空闲连接数（默认 0）
        max-wait: -1                              # 从连接池借出连接的最大等待时间（负数表示无限等待）
        min-evictable-idle-time: 30m              # 连接在池中保持空闲而不被逐出的最小时间（默认 30m）
        time-between-eviction-runs: -1            # 空闲连接逐出线程运行间隔（负数表示不启动逐出线程）
        test-on-borrow: true                      # 借出连接时是否发送 NOOP 验证存活性（默认 true，推荐生产开启）
        test-on-return: false                     # 归还连接时是否验证存活性（默认 false）
        test-while-idle: true                     # 空闲逐出时是否验证存活性（默认 true）
        
      # FTPS 安全传输加密扩展（可选）
      ftps:
        protocol: TLS                             # FTPS 协议版本（TLS / SSL，默认 TLS）
        implicit: false                           # 是否为隐式 FTPS（默认 false 为显式 AUTH TLS）
        key-store-path: ""                        # 客户端 KeyStore 路径（双向认证时配置）
        key-store-password: ""                    # KeyStore 访问密码
        trust-store-path: ""                      # TrustStore 证书库路径
        trust-store-password: ""                  # TrustStore 访问密码
        need-client-auth: false                   # 是否强制要求客户端证书认证
        
      # 多数据源节点映射字典
      servers:
        backup:
          host: 192.168.1.100
          port: 21
          username: backup_user
          password: YOUR_PASSWORD
          mode: PASSIVE
          pool:
            max-total: 5
```
