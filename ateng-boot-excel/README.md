# ateng-boot-excel 基础设施 Starter

> 基于新一代 Apache Fesod (Incubating) 流式电子表格引擎打造，提供极致内存防爆（OOM 防御）、Web 安全防乱码下载、强类型同步读取与分批流式消费等基础设施能力。

---

## 1. 模块定位与 Maven 坐标

`ateng-boot-excel` 是面向企业级的高内聚、轻量级、开箱即用的 Excel 基础设施模块。
- **纯净依赖**：仅必需依赖 `ateng-boot-core`，Servlet/Web 为可选依赖（optional），后台任务、离线脚本与 Web 服务皆可纯净复用；
- **底层引擎**：全面基于 Apache 基金会孵化器顶级开源项目 `org.apache.fesod:fesod-sheet:2.1.0-incubating`（修复底层 POI 历史 CVE，原生适配 JDK 17+ 与 Spring Boot 3）；
- **门面驱动**：提供显式静态门面 `ExcelUtil`，拒绝黑盒切面侵入，恪守显式契约原则。

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-boot-excel</artifactId>
</dependency>
```

---

## 2. 基础流式导出 (`ExcelUtil.export`)

### 场景痛点
传统 POI 导出需要开发者手动创建 Workbook、Sheet、Row、Cell 及格式化样式，样板代码长达几十行且极易导致堆内存暴涨 OOM。

### 核心代码范式
```java
import io.github.atengk.boot.excel.util.ExcelUtil;

// 1. 导出至 OutputStream（如本地文件或网络流）
List<UserVO> userList = userService.listUsers();
try (FileOutputStream out = new FileOutputStream("用户列表.xlsx")) {
    ExcelUtil.export(out, userList, UserVO.class);
}

// 2. 导出并指定工作表名称
try (FileOutputStream out = new FileOutputStream("用户列表.xlsx")) {
    ExcelUtil.export(out, "2026年VIP用户", userList, UserVO.class);
}
```

---

## 3. Web HTTP 安全防乱码导出

### 场景痛点
Controller 文件导出时，各浏览器对中文文件名的转码规范（如 Safari、Firefox 与 Chrome 的兼容差异）不一致，极易出现乱码或下划线；且响应头 Content-Type 设置繁琐。

### 核心代码范式
```java
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/excel")
public class ExcelExportController {

    @GetMapping("/export")
    public void exportUsers(HttpServletResponse response) {
        List<UserVO> data = userService.listUsers();
        // 自动完成 RFC 5987 / RFC 6266 UTF-8 编码与标准 XLSX MIME 设置
        ExcelUtil.export(response, "系统用户清单", data, UserVO.class);
    }
}
```

### 预期 HTTP 响应契约
```http
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8
Content-Disposition: attachment; filename="系统用户清单.xlsx"; filename*=UTF-8''%E7%B3%BB%E7%BB%9F%E7%94%A8%E6%88%B7%E6%B8%85%E5%8D%95.xlsx
```

---

## 4. 强类型同步读取与流式分批读取

### 场景痛点
用户上传几十万行超大 Excel 文件时，若一次性将全量对象 load 进 JVM 内存，会直接引发长时间 FullGC 甚至崩溃；而小文件又需要极简的列表返回接口。

### 核心代码范式
```java
import io.github.atengk.boot.excel.util.ExcelUtil;

// 1. 小文件极速全量读取
InputStream inputStream = file.getInputStream();
List<UserVO> userList = ExcelUtil.read(inputStream, UserVO.class);

// 2. 大数据量流式分批读取（每批 1000 条消费入库并立即清空内存引用）
ExcelUtil.readBatch(file.getInputStream(), UserVO.class, 1000, batchList -> {
    userMapper.insertBatch(batchList);
});

// 3. 采用全局默认配置批次大小
ExcelUtil.readBatch(file.getInputStream(), UserVO.class, batchList -> {
    userMapper.insertBatch(batchList);
});
```

---

## 5. 配置属性参考

在 `application.yml` 中按需定制：

```yaml
ateng:
  boot:
    excel:
      # 是否启用自动装配（默认 true）
      enabled: true
      # 流式读取默认批次大小（默认 1000）
      default-batch-size: 1000
      # 大文件磁盘溢出临时目录（留空时使用系统默认临时目录）
      temp-dir: /data/tmp/fesod
```
