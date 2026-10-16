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

## 5. 数据安全与导出动态脱敏 (`@ExcelSensitive`)

### 场景痛点
根据数据安全合规要求，手机号、身份证、银行卡等隐私字段在导出 Excel 报表时必须掩码脱敏。传统做法是在 Service 业务层打星号（如 `138****0000`），这不仅污染业务实体，而且极易由于开发者疏忽将掩码脏数据回写数据库，严重破坏数据一致性与系统安全。

### 核心代码范式
只需在导出实体的字段上标注 `@ExcelSensitive` 注解，`ExcelUtil` 会在单元格写出阶段自动拦截转换并进行安全掩码，**源实体对象在 JVM 内存中始终保持 100% 原始真实明文**，严格遵守项目安全规范：

```java
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.excel.enums.DesensitizeType;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class SensitiveUserVO {

    @ExcelProperty("用户编号")
    private Long id;

    @ExcelProperty("用户名称")
    @ExcelSensitive(DesensitizeType.CHINESE_NAME)
    private String name;

    @ExcelProperty("手机号码")
    @ExcelSensitive(DesensitizeType.MOBILE_PHONE)
    private String phone;

    @ExcelProperty("身份证号")
    @ExcelSensitive(DesensitizeType.ID_CARD)
    private String idCard;

    @ExcelProperty("电子邮箱")
    @ExcelSensitive(DesensitizeType.EMAIL)
    private String email;

    @ExcelProperty("首尾保留脱敏")
    @ExcelSensitive(keepHead = 2, keepTail = 2, maskChar = '#')
    private String secretCode;
}

// 正常流式或 Web 导出，拦截器自动完成单元格无侵入脱敏
ExcelUtil.export(outputStream, userList, SensitiveUserVO.class);
```

### 预期导出的 Excel 单元格呈现
| 用户编号 | 用户名称 | 手机号码 | 身份证号 | 电子邮箱 | 首尾保留脱敏 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1001 | 张*丰 | 138\*\*\*\*0000 | 110101\*\*\*\*\*\*\*\*0001 | a\*\*\*g@example.com | AB##EF |

> [!NOTE]
> 支持内置脱敏策略：`CHINESE_NAME`（中文名）、`ID_CARD`（身份证）、`MOBILE_PHONE`（手机号）、`FIXED_PHONE`（座机号）、`EMAIL`（电子邮箱）、`BANK_CARD`（银行卡）、`IPV4`、`IPV6`、`CAR_LICENSE`（车牌号）、`KEEP_HEAD_TAIL`（基于 `keepHead`/`keepTail`/`maskChar` 首尾保留）、`CUSTOM`（基于 `startInclude`/`endExclude`/`maskChar` 自定义区间）。

---

## 6. JSR-303 行级校验与失败标红批注回写

### 场景痛点
用户上传成百上千行 Excel 进行批量导入时，若遇到错误采用改一条重传一次的体验极差；更关键的是，后台返回的一串干瘪 JSON 报错文字（如“第 58 行手机号格式不合法”）让业务运营人员很难在庞大的表格中肉眼定位修改。

### 核心代码范式
`ateng-boot-excel` 原生联动 Jakarta Validation (JSR-303/380)，支持强类型聚合校验与“一键生成带标红底色和悬浮批注的反馈 Excel”：

```java
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.*;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class UserImportVO {

    @ExcelProperty("用户姓名")
    @NotBlank(message = "用户姓名不可为空")
    private String name;

    @ExcelProperty("手机号码")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号码格式不合法")
    private String phone;

    @ExcelProperty("年龄")
    @Min(value = 18, message = "年龄须满18周岁")
    private Integer age;
}

// 1. 结构化读取与校验（不抛异常，聚合所有行级错误）
ExcelReadResult<UserImportVO> result = ExcelUtil.readWithResult(file.getInputStream(), UserImportVO.class);

if (result.hasErrors()) {
    // 2. 若存在校验错误，直接将上传的原始 Excel 标红并嵌入悬浮批注，写出供用户直接下载排查
    ExcelUtil.exportErrorFeedback(response, "导入失败_请核对修改", file.getInputStream(), result.getErrors());
    return;
}

// 3. 校验完全通过，执行合法数据批量入库
userService.saveBatch(result.getData());
```

### 预期导出的反馈 Excel 单元格呈现
- **错误单元格视觉高亮**：出错单元格自动填充警示浅粉红色背景（`ROSE`）；
- **悬浮批注浮层**：鼠标悬停在出错单元格上时，自动弹出悬浮批注框：`"1. 手机号码格式不合法"`；若单单元格多项规则校验不通过，自动换行排版呈现；
- **快速失败模式 (fail-fast)**：亦可通过 `ExcelUtil.readWithValidation(inputStream, UserImportVO.class, true)` 开启首错即抛 `ExcelValidationException` 模式。

---

## 7. 配置属性参考

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


