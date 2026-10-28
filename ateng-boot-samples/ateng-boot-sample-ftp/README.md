# ateng-boot-sample-ftp 客户端自验集成工程

> 专一面向生产就绪的 FTP/FTPS 协议客户端自验沙箱：覆盖 Commons Pool2 连接池借还生命周期与心跳保活、工作目录双重复位防串目录、`FtpContextHolder` 线程级动态切源、表单原子上传守护（`.tmp` + `rename`）、流式下载响应、RFC 3659 MLSD 机器目录解析、级联递归创建（`mkdir -p`）、跨目录移动、路径穿越防卫与原生逃逸舱 100% 端到端实测验证。

---

## 1. 场景定位与功能矩阵

传统 FTP 客户端在业务集成中通常面临以下痛点：
1. **短连接开销巨大与长连接池化复杂**：传统单次传输频繁握手鉴权开销严重，而自行维护连接池易导致网络断线、假死悬挂或会话泄露；
2. **工作目录污染引发严重串目录事故**：FTP 状态机具备全局工作目录（CWD），若某次操作切换目录后未还原，下游操作将全线写错或读错目录；
3. **云防火墙截断控制通道**：长时间传输大文件时，控制通道无数据流动极易被云安全组或防火墙静默斩断；
4. **半截残缺文件灾难**：网络波动导致上传中断时，残余半截废文件直接暴露给下游消费系统，引发严重业务解析异常；
5. **多环境多节点切换代码侵入严重**：多 FTP 数据源切换时代码充斥 `if-else` 或硬编码配置；
6. **路径穿越（Path Traversal）安全风险**：直接拼接用户输入路径容易遭遇 `../` 越权逃逸风险；
7. **大文件下载引发堆内存 OOM**：缺乏安全阈值守护，一次性加载大文件直接撑爆 JVM 堆内存。

`ateng-boot-sample-ftp` 提供了工业级规范且完全离线自洽的集成验证沙箱：
- **纯内存 FakeFtpServer 极速自验**：零物理网络与外部 FTP 依赖，毫秒级独立离线全绿运行；
- **Apache Commons Pool2 受管对象池**：提供心跳保活（`NOOP`）、存活探针与异常自动剔除；
- **绝对工作目录隔离**：借出与归还双重 `cwd("/")` 复位，彻底消除串目录隐患；
- **原子上传守护与异常补偿**：采用 `.tmp` 临时文件写入 + 成功原子 `rename`，异常时在独立会话中物理清理脏文件；
- **线程级动态切源路由**：基于 `FtpContextHolder` 实现栈式嵌套上下文切换，`finally` 确定性复位；
- **严格路径穿越安全清洗**：`cleanAndValidatePath` 前置防御 `..` 序列与空字符注入；
- **内存防爆机制**：默认 50MB 内存下载上限保护，大文件强制推行流式受管回调消费。

---

## 2. 10 项端到端自验场景全景表

| 序号 | 自验场景 | 覆盖核心机制 / API | 验证端点 / 动作 | 预期响应 / 校验点 |
| :--- | :--- | :--- | :--- | :--- |
| **01** | 表单文件原子上传与 HTTP 流式下载 | `FtpTemplate.upload` / `FtpTemplate.download` | `POST /api/ftp/upload`<br>`GET /api/ftp/download` | 经过 `.tmp` 临时文件中转并原子重命名；下载携带标准 `Content-Disposition` 附件报头与字节内容 100% 对齐 |
| **02** | 纯文本快捷写入与读取 | `writeString` / `readString` | `POST /api/ftp/write-text`<br>`GET /api/ftp/read-text` | 便捷 UTF-8 文本写入与读取，免除底层繁琐样板流代码 |
| **03** | 详细元数据获取与存在性探测 | `exists` / `getFileInfo` (`FtpFileInfo`) | `GET /api/ftp/exists`<br>`GET /api/ftp/info` | 优先通过 RFC 3659 `MLST` 精确获取文件名、大小、修改时间与目录属性；不存在文件安全返回 false |
| **04** | 递归创建多级目录与目录列表检索 | `makeDirectories` / `listFiles` | `POST /api/ftp/mkdir`<br>`GET /api/ftp/list` | 级联递归创建多级父目录（`mkdir -p`）；通过 RFC 3659 `MLSD` 机器可读格式准确列举子目录项 |
| **05** | 文件安全重命名与跨目录移动 | `rename` / `move` (`FtpRenameRequest`) | `POST /api/ftp/rename`<br>`POST /api/ftp/move` | 同目录重命名与跨父目录移动；自动完成目标父目录探测创建与旧路径安全更新 |
| **06** | 确定性文件与目录删除 | `deleteFile` / `deleteDirectory` | `DELETE /api/ftp/delete-file`<br>`DELETE /api/ftp/delete-dir` | 物理删除单个文件；级联清空并安全删除非空目录树，释放服务端空间 |
| **07** | 多节点动态切源路由隔离 | `FtpContextHolder.runWith` | `POST /api/ftp/upload?server=backup`<br>`GET /api/ftp/routed-read?server=backup` | 线程级路由至 `backup` 节点执行文件落盘与读取；默认主节点探查该路径不存在，实现物理级多源数据隔离 |
| **08** | 逃逸舱原生会话指令 | `FtpTemplate.execute` (`FTPClientCallback`) | `GET /api/ftp/native-pwd` | 用户穿透受管池直接获取底层 `FTPClient` 执行原生指令（如 `printWorkingDirectory`），操作后连接安全归还 |
| **09** | 路径穿越高危字符安全防御 | `cleanAndValidatePath` 路径清洗 | `GET /api/ftp/exists?remotePath=/../../etc/passwd` | 拦截非法包含 `..` 的越权路径，抛出 `51007` (`INVALID_PATH`) 并由全局异常处理器转译为结构化业务错误契约 |
| **10** | JSR-303 参数格式校验 Fail-Fast | Spring MVC `@Validated` + `@NotBlank` | `GET /api/ftp/read-text?remotePath=   ` | 拦截空白入参，Fail-Fast 触发参数校验异常，统一返回 HTTP 400 规范业务错误报文 |

---

## 3. 本地启动与实测体验

### 1. 启动服务
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-ftp spring-boot:run
```

### 2. 表单文件上传
```bash
curl -X POST -F "file=@test.txt" -F "remotePath=/data/test.txt" http://localhost:8080/api/ftp/upload
```

### 3. 流式下载文件
```bash
curl -O -J "http://localhost:8080/api/ftp/download?remotePath=/data/test.txt"
```

### 4. 纯文本快捷读写
```bash
# 写入纯文本
curl -X POST "http://localhost:8080/api/ftp/write-text" -d "remotePath=/config/app.json&content={\"env\":\"prod\"}"

# 读取纯文本
curl "http://localhost:8080/api/ftp/read-text?remotePath=/config/app.json"
```

### 5. 跨多节点动态切源
```bash
# 在 backup 备用节点上传文件
curl -X POST -F "file=@backup.log" -F "remotePath=/logs/backup.log" -F "server=backup" http://localhost:8080/api/ftp/upload

# 在 backup 备用节点读取文件
curl "http://localhost:8080/api/ftp/routed-read?server=backup&remotePath=/logs/backup.log"
```

### 6. 目录操作与列表检索
```bash
# 递归创建多级目录
curl -X POST "http://localhost:8080/api/ftp/mkdir?remoteDir=/nested/tree/dir"

# 列出目录下所有文件与子目录
curl "http://localhost:8080/api/ftp/list?remoteDir=/nested/tree"
```

### 7. 重命名与移动文件
```bash
# 重命名
curl -X POST -H "Content-Type: application/json" -d '{"fromPath":"/config/app.json","toPath":"/config/app-v2.json"}' http://localhost:8080/api/ftp/rename

# 移动到目标目录
curl -X POST -H "Content-Type: application/json" -d '{"fromPath":"/config/app-v2.json","toPath":"/nested/tree/dir"}' http://localhost:8080/api/ftp/move
```

### 8. 删除文件与目录
```bash
# 删除文件
curl -X DELETE "http://localhost:8080/api/ftp/delete-file?remotePath=/nested/tree/dir/app-v2.json"

# 递归删除目录
curl -X DELETE "http://localhost:8080/api/ftp/delete-dir?remoteDir=/nested"
```
