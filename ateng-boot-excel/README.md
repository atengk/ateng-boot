# ateng-boot-excel 基础设施 Starter

> 基于新一代 Apache Fesod (Incubating) 流式电子表格引擎打造，提供极致内存防爆（OOM 防御）、Web 安全防乱码下载、强类型同步读取与分批流式消费等基础设施能力。

---

## 0. 特性与公共 API 全景速查矩阵

| 特性分类 | 特性名称 | 核心门面 / 注解 / SPI | 关键配置项 (`ateng.boot.*`) | 关键 API 签名 / 核心行为 | 锚定验证源头 (*Test / Sample) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **基础运维** | 自动装配与主配置 | `ExcelProperties` / `AtengExcelAutoConfiguration` | `ateng.boot.excel.enabled`<br>`ateng.boot.excel.default-batch-size`<br>`ateng.boot.excel.max-sync-rows`<br>`ateng.boot.excel.temp-dir` | 统管 Starter 自动装配、流式批次阈值与同步读取防 OOM 最大行数上限 | `AtengExcelAutoConfigurationTest`<br>`ExcelUtilTest` |
| **核心导出** | 强类型流式导出 | `ExcelUtil` | - | `export(out, list, clazz)` / `export(response, fileName, list, clazz)`：流式写出并自适应配置 RFC 5987 / RFC 6266 标准防乱码响应头 | `ExcelUtilTest`<br>`SampleExcelIntegrationTest` |
| **核心读取** | 同步全量与流式分批读取 | `ExcelUtil` | `ateng.boot.excel.default-batch-size`<br>`ateng.boot.excel.max-sync-rows` | `read(in, clazz)`（超限自动 fail-fast 阻断防 OOM）；`readBatch(in, clazz, batchSize, consumer)`（每次达到批次回调并即刻清空内存引用） | `ExcelUtilTest`<br>`SampleExcelIntegrationTest` |
| **数据校验** | JSR-303 行级校验导入 | `ExcelUtil` / `BatchValidationListener` | - | `readWithValidation(in, clazz, failFast)` / `readWithResult(in, clazz)` / `readBatchWithValidation(in, clazz, size, dataConsumer, errConsumer)`：精准捕获行号与列字段并聚合错误 | `ExcelValidationTest`<br>`SampleExcelIntegrationTest` |
| **错题集隔离** | 差错数据隔离提取与标红回写 | `ExcelUtil` / `ExcelFeedbackUtil` / `FailSplitRowWriteHandler` | - | `exportFailedRows(out, clazz, errorRows)`（仅将失败数据提取生成纠错文件并高亮）；`exportErrorFeedback(in, out, errorRows)`（原表单元格粉色高亮并嵌入浮动批注） | `ExcelFailSplitTest`<br>`SampleExcelIntegrationTest` |
| **数据安全** | 导出动态脱敏 | `@ExcelSensitive` / `SensitiveCellWriteHandler` | - | `@ExcelSensitive(type, keepHead, keepTail)`：写出时拦截掩码，源实体在 JVM 内存中 100% 保持原始明文不变量，杜绝掩码脏写 | `ExcelSensitiveTest`<br>`SampleExcelIntegrationTest` |
| **企业主题** | 模版主题与智能列宽 | `@ExcelTheme` / `ExcelThemeEnum` / `ExcelThemeWriteHandler` / `SmartAutoColumnWidthWriteHandler` | `ateng.boot.excel.style.enabled`<br>`ateng.boot.excel.style.theme`<br>`ateng.boot.excel.style.zebra-stripe`<br>`ateng.boot.excel.style.auto-column-width` | 5 套企业级内置主题（`minimal-clean`, `business-blue`, `classic-grey`, `fresh-green`, `warm-orange`）；中英文双字节补偿列宽防数值变 `###` | `ExcelThemeWriteHandlerTest`<br>`SampleExcelIntegrationTest` |
| **链式编排** | 多 Sheet 链式构建器 | `ExcelBuilder` | - | `ExcelUtil.builder(out).theme(theme).sheet(name, clazz, list).finish()`：实现了 `AutoCloseable`，支持在单个工作簿内连续编排实体与动态 Sheet | `ExcelAdvancedFeaturesTest`<br>`SampleExcelIntegrationTest` |
| **枚举转义** | 字段级枚举映射 | `@ExcelEnum` / `ExcelEnumConverter` | - | `@ExcelEnum(valueField = "code", textField = "text")`：双向自动转换实体枚举与表格文字，内置双向无锁并发缓存 | `ExcelEnumAndDictTest`<br>`SampleExcelIntegrationTest` |
| **字典翻译** | 动态数据字典 SPI | `@ExcelDict` / `DictProvider` / `DictProviderHolder` | - | `@ExcelDict(dictType = "user_status")`：解耦具体字典存储（数据库/Redis/枚举），导入导出时双向透明翻译 | `ExcelEnumAndDictTest`<br>`SampleExcelIntegrationTest` |
| **防呆设计** | 表头指南与下拉选单约束 | `@ExcelPrompt` / `@ExcelDropdown` / `DropdownProvider` | - | `@ExcelPrompt(title, content)`（自动写入单元格浮动提示批注）；`@ExcelDropdown`（自动生成工作表级下拉列表限制，超过 255 字符自动外挂隐藏字典工作表） | `ExcelTemplateTest`<br>`SampleExcelIntegrationTest` |
| **安全审计** | 防拍照暗水印与密码保护 | `ExcelUtil.exportWithWatermark` / `protectSheet` / `WatermarkSheetWriteHandler` | - | `exportWithWatermark(out, list, clazz, watermarkText, password)`：全屏 30° 倾斜半透明平铺暗水印，支持工作表只读密码锁定 | `ExcelWatermarkAndProtectTest`<br>`SampleExcelIntegrationTest` |
| **多媒体导出** | 图片流式导出与行高自适应 | `@ExcelImage` / `SmartImageConverter` / `ImageRowHeightWriteHandler` | - | `@ExcelImage(width, height)`：支持 `byte[]`、`InputStream`、`File` 与 URL 网络图片，自动根据最大图片像素推算行高并精准锚定单元格居中 | `ExcelImageTest` |
| **结构排版** | 单元格纵向合并 | `@ExcelMerge` / `MergeCellWriteHandler` | - | `@ExcelMerge(value, dependParent)`：对连续相同的数据单元格自动纵向合并，支持依赖前序关联列联动合并 | `ExcelAdvancedFeaturesTest`<br>`SampleExcelIntegrationTest` |
| **复杂报表** | 模板数据填充 | `ExcelUtil.fill` | - | `fill(out, templateIn, data, fillConfig)`：基于预设 `.xlsx` 模板，支持单对象占位符 `{name}` 与列表集合循环 `{.list}` 复合填充 | `ExcelTemplateTest` |
| **动态导出** | 无实体动态流式导出 | `ExcelUtil.exportDynamic` | - | `exportDynamic(out, headList, dataList)`：支持动态表头与 `List<Map>` / `List<List<?>>` 二维数据直接流式写出 | `ExcelAdvancedFeaturesTest`<br>`SampleExcelIntegrationTest` |
| **异步中心** | 大文件异步导出任务中心 | `AsyncExportTaskService` / `InMemoryAsyncExportTaskService` / `RedisAsyncExportTaskService` / `ExportFileStorage` | `ateng.boot.excel.async-export.enabled`<br>`ateng.boot.excel.async-export.retention-period`<br>`ateng.boot.excel.async-export.lease-timeout`<br>`ateng.boot.excel.async-export.core-pool-size`<br>`ateng.boot.excel.async-export.queue-capacity`<br>`ateng.boot.excel.async-export.redis.enabled`<br>`ateng.boot.excel.async-export.redis.key-prefix`<br>`ateng.boot.excel.async-export.redis.ttl` | `submit(taskName, executor)` / `getTask(taskId)` / `cancel(taskId)`：受管线程池后台写出、四维上下文快照透传、进度感知、内存/Redis 集群双模驱动、租约防宕机悬挂与过期文件定时自动清理 | `AsyncExportTaskServiceTest`<br>`RedisAsyncExportTaskServiceTest`<br>`SampleExcelIntegrationTest` |
| **流式分页** | 海量数据游标分页与跨表轮转导出 | `StreamingPageExportTemplate` / `PageDataReader<T>` / `StreamingExportOptions` | `ateng.boot.excel.streaming-export.max-rows-per-sheet`<br>`ateng.boot.excel.streaming-export.default-page-size` | `export(out, dataClass, pageReader, options)`：游标分页流式消费拉取，单 Sheet 达到物理行上限自动开辟新工作表续写，突破百万行 OOM 瓶颈 | `StreamingPageExportTemplateTest`<br>`SampleExcelIntegrationTest` |
| **契约校验** | 导入预读首行表头校验 | `ExcelHeaderValidator` / `HeaderValidationStrategy` / `ExcelHeaderValidationException` | `ateng.boot.excel.validation.header-strategy` | `validate(inputStream, modelClass, allowExtraColumns)` / `validate(inputStream, modelClass, strategy)`：解析前轻量预读首行比对 `@ExcelProperty` 契约，顺序、名称与缺失列不符立即 Fail-Fast 阻断 | `ExcelHeaderValidatorTest`<br>`SampleExcelIntegrationTest` |
| **错误熔断** | 行级校验错误阈值自动熔断 | `BatchValidationListener` / `ExcelValidationException` | `ateng.boot.excel.validation.max-error-rows` | `BatchValidationListener(clazz, validator, failFast, maxErrorRows)`：累计校验错误行达到阈值时主动中断解析，防止全量解析垃圾数据耗尽系统 CPU 与内存 | `ExcelValidationTest`<br>`SampleExcelIntegrationTest` |
| **注入防护** | CSV / Excel 公式注入主动防御 | `ExcelSecurityUtil` / `FormulaInjectionWriteHandler` / `@ExcelFormulaAllowed` | `ateng.boot.excel.security.formula-protection` | 自动拦截 `=,+,-,@,\t,\r` 前缀文本并转义为安全单引号 `'`，彻底防御 DDE 远程执行命令高危漏洞；支持 `@ExcelFormulaAllowed` 字段级白名单豁免 | `ExcelFormulaDefenseTest`<br>`SampleExcelIntegrationTest` |
| **列锁防呆** | 模板列级锁定与保护 | `@ExcelColumnLock` / `ColumnLockWriteHandler` / `ExcelUtil.protectSheet` | - | `@ExcelColumnLock(locked = true)`：配合工作表只读保护密码，精准锁定固定列/序号列防篡改，其余可录入列保持编辑权限 | `ExcelColumnLockTest`<br>`SampleExcelIntegrationTest` |
| **多语言表头** | 国际化动态表头解析 | `I18nHeadWriteHandler` | `ateng.boot.excel.i18n.enabled` | 拦截 `@ExcelProperty("{i18n:key}")` 占位符，结合 Spring `MessageSource` 与请求 `LocaleContextHolder.getLocale()` 动态转译中英文多语言表头 | `I18nHeadWriteHandlerTest`<br>`SampleExcelIntegrationTest` |

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
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class SensitiveUserVO {

    @ExcelProperty("用户编号")
    private Long id;

    @ExcelProperty("用户名称")
    @ExcelSensitive(SensitiveType.CHINESE_NAME)
    private String name;

    @ExcelProperty("手机号码")
    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    @ExcelProperty("身份证号")
    @ExcelSensitive(SensitiveType.ID_CARD)
    private String idCard;

    @ExcelProperty("电子邮箱")
    @ExcelSensitive(SensitiveType.EMAIL)
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
> - 支持内置脱敏策略：`CHINESE_NAME`（中文名）、`ID_CARD`（身份证）、`MOBILE_PHONE`（手机号）、`FIXED_PHONE`（座机号）、`EMAIL`（电子邮箱）、`BANK_CARD`（银行卡）、`IPV4`、`IPV6`、`CAR_LICENSE`（车牌号）、`KEEP_HEAD_TAIL`（基于 `keepHead`/`keepTail`/`maskChar` 首尾保留）、`CUSTOM`（基于 `startInclude`/`endExclude`/`maskChar` 自定义区间）。
> - **通用底座原语兼容**：除了专属的 `@ExcelSensitive` 注解，`ateng-boot-excel` 亦原生兼容识别 `ateng-boot-core` 中的通用的视图层脱敏注解 `@FieldSensitive`。若同一个 VO 同时用于 Controller JSON 接口返回与 Excel 导出，可直接标注 `@FieldSensitive` 达成一套配置全场景生效。

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

## 7. 防呆模板智能生成与下拉/提示约束 (`@ExcelDropdown` & `@ExcelPrompt`)

### 场景痛点
在传统的后台导入业务中，开发人员必须在代码库或对象存储维护大量静态 `.xlsx` 模板文件，一旦字段变更就必须重新制作并上传；且用户在手工录入性别、状态等枚举字段时极易敲错，导致导入时反复校验失败。

### 核心代码范式
只需在实体字段上标注 `@ExcelDropdown`（支持静态字符数组或动态 `DropdownProvider`）与 `@ExcelPrompt`，调用 `ExcelUtil.exportTemplate` 即可 1 行代码动态生成带原生防呆约束的空白模板：

```java
import io.github.atengk.boot.excel.annotation.DropdownProvider;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class UserTemplateVO {

    @ExcelProperty("用户名称")
    @ExcelPrompt("请填写 2-20 位真实中文姓名")
    private String name;

    @ExcelProperty("用户性别")
    @ExcelDropdown({"男", "女", "保密"})
    private String gender;

    @ExcelProperty("所属部门")
    @ExcelDropdown(source = DeptDropdownProvider.class)
    private String department;

    @ExcelProperty("手机号码")
    @ExcelPrompt(title = "联系方式", value = "仅支持 11 位有效手机号码")
    private String mobile;

    public static class DeptDropdownProvider implements DropdownProvider {
        @Override
        public String[] getOptions() {
            return new String[]{"技术研发中心", "产品设计部", "市场运营部", "综合财务部"};
        }
    }
}

// 1. Controller 中 1 行代码下载动态防呆模板
@GetMapping("/template")
public void downloadTemplate(HttpServletResponse response) {
    ExcelUtil.exportTemplate(response, "用户导入模板", UserTemplateVO.class);
}

// 2. 本地或离线文件生成
try (FileOutputStream out = new FileOutputStream("用户导入模板.xlsx")) {
    ExcelUtil.exportTemplate(out, "员工填报表", UserTemplateVO.class);
}
```

### 预期导出的模板防呆呈现
- **表头单元格浮动批注**：表头单元格右上角标红，鼠标悬浮时展示提示说明（如 `[联系方式] 仅支持 11 位有效手机号码`）；
- **数据列原生输入提示框 (Prompt Box)**：光标落在数据列单元格时，右侧自动浮出黄色填写指导小窗；
- **数据列原生下拉菜单 (DataValidation)**：点击性别与部门单元格时，右侧自动出现下拉选择箭头，严格限定仅能选择候选列表项，从源头杜绝敲错。

---

## 8. 业务数据双向转义 (`@ExcelEnum` 与 `@ExcelDict` SPI)

### 场景痛点
数据库与微服务传输通常使用数字编码（如 `gender=1`）或字典代号（如 `role="ADMIN"`、`status="ENABLE"`），而面向业务运营人员的 Excel 报表必须呈现为通俗易懂的中文描述（如“男”、“超级管理员”、“已启用”）。传统做法需要在 Service 层编写大量死板冗长的 `switch-case` 或批量查字典打补丁，导入时反向翻译更是一团乱麻。

### 核心代码范式
`ateng-boot-excel` 提供内置的枚举与字典双向映射体系：
- **枚举双向转义 (`@ExcelEnum`)**：枚举字段仅需标注 `@ExcelEnum` 即可零配置自动生效，导出时自动将枚举或底层编码转换为中文文本，导入时自动反查还原为枚举实例或编码；
- **字典双向转义 (`@ExcelDict` & `DictProvider`)**：通过轻量 SPI 接口一键桥接业务现有字典中心、Redis 缓存或数据库表。

```java
import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.converter.ExcelDictConverter;
import io.github.atengk.boot.excel.converter.ExcelEnumConverter;
import io.github.atengk.boot.excel.spi.DictProvider;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

// 1. 定义实体模型
@Data
public class UserBusinessVO {

    @ExcelProperty("用户名称")
    private String username;

    /**
     * 强类型枚举：仅标注 @ExcelEnum，无需显式指定 converter 即可全自动双向互转
     */
    @ExcelProperty("用户性别")
    @ExcelEnum
    private UserGenderEnum gender;

    /**
     * 基础编码字段：通过 @ExcelEnum 绑定枚举 Class，实现 code 与中文文本的双向互转
     */
    @ExcelProperty(value = "状态编码", converter = ExcelEnumConverter.class)
    @ExcelEnum(value = UserStatusEnum.class, codeField = "code", textField = "desc")
    private String statusCode;

    /**
     * 字典代码字段：标注 @ExcelDict 并指定 ExcelDictConverter 实现双向导入导出互转
     */
    @ExcelProperty(value = "用户角色", converter = ExcelDictConverter.class)
    @ExcelDict("sys_user_role")
    private String roleCode;

    /**
     * 字典数值字段：标注 @ExcelDict 并指定 ExcelDictConverter 实现双向导入导出互转
     */
    @ExcelProperty(value = "会员等级", converter = ExcelDictConverter.class)
    @ExcelDict("sys_user_level")
    private Integer level;
}

// 2. 业务工程接入 DictProvider SPI（注入为 Spring Bean 即可自动装配）
@Configuration
public class ExcelDictConfig {

    @Bean
    public DictProvider customDictProvider(SysDictService dictService) {
        return new DictProvider() {
            @Override
            public String toLabel(String dictType, Object code) {
                return dictService.getLabel(dictType, String.valueOf(code));
            }

            @Override
            public String toCode(String dictType, String label) {
                return dictService.getCode(dictType, label);
            }
        };
    }
}

// 3. 极速导出与导入调用
// 导出：自动转义为中文描述输出到 Excel
ExcelUtil.export(outputStream, userList, UserBusinessVO.class);

// 导入：自动将 Excel 中的中文标签还原为枚举常量或原始数字/字符串编码
List<UserBusinessVO> importedList = ExcelUtil.read(file.getInputStream(), UserBusinessVO.class);
```

### 预期呈现与还原契约
1. **导出的 Excel 单元格呈现**：
   | 用户名称 | 用户性别 | 状态编码 | 用户角色 | 会员等级 |
   | :--- | :--- | :--- | :--- | :--- |
   | 张三 | 男 | 已启用 | 超级管理员 | 黄金会员 |
   | 李四 | 女 | 已禁用 | 普通用户 | 钻石会员 |

2. **反向读取还原的 Java 对象**：
   - `importedList.get(0).getGender()` -> `UserGenderEnum.MALE`（强类型枚举常量）
   - `importedList.get(0).getStatusCode()` -> `"ENABLE"`（原始状态编码）
   - `importedList.get(0).getLevel()` -> `Integer` 值 `1`（还原为目标数值类型编码）

---

## 9. 复杂报表填充、单元格合并与多 Sheet 链式构建器

### 场景痛点
在企业级经营分析与财务报表场景中，开发者往往面临五大棘手诉求：
1. **模板格式复杂**：带有公司 Logo、公式计算、复杂合并表头的发票与预算表，手写代码排版极其耗时；
2. **业务转义与展示脱敏**：模板中的字段涉及枚举（如状态、性别）与字典（如部门、渠道）时，需要优雅呈现为中文描述（`desc`）或字典标签，而非生硬的代码或常量名；
3. **相同数据折叠合并**：同部门或同类别的相邻行数据零散罗列，需要根据层级关系纵向合并单元格；
4. **无实体与动态 SQL 导出**：BI 自定义报表或聚合统计场景无法预先定义固定 Java VO 类；
5. **多工作表编排繁琐**：单文件包含“明细”、“汇总”、“参数说明”等多个 Sheet 时，传统代码需要繁复的 Writer 与 Sheet 对象管理。

### 核心代码范式
`ateng-boot-excel` 提供声明式合并注解、模板填充门面、动态流式导出以及 Fluent 链式构建器：

```java
import io.github.atengk.boot.excel.annotation.ExcelDict;
import io.github.atengk.boot.excel.annotation.ExcelMerge;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.converter.ExcelDictConverter;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import lombok.Data;
import lombok.AllArgsConstructor;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

// 1. 声明式单元格合并模型：通过 @ExcelMerge 启用相邻行合并，dependParent=true 绑定父级防串列
@Data
@AllArgsConstructor
public class DepartmentStaffVO {
    @ExcelMerge
    @ExcelProperty("部门")
    private String department;

    @ExcelMerge(dependParent = true)
    @ExcelProperty("岗位")
    private String role;

    @ExcelProperty("员工姓名")
    private String name;

    @ExcelProperty("薪资")
    private Double salary;
}

// 2. 报表模板填充数据模型：支持枚举派生 desc 与字典标签自动转义
@Data
@AllArgsConstructor
public class BudgetExpenseVO {
    private String itemName;
    private Double amount;

    // 字典字段：标注 @ExcelDict 并挂载 ExcelDictConverter，模板填充 {.status} 时自动翻译为中文标签
    @ExcelDict("sys_budget_status")
    @ExcelProperty(converter = ExcelDictConverter.class)
    private String status;

    // 枚举字段：推荐提供 getGenderDesc() 派生 Getter，模板占位符直接声明 {.genderDesc}
    private UserGenderEnum gender;

    public String getGenderDesc() {
        return gender != null ? gender.getDesc() : "";
    }
}

// 3. 导出带自动纵向合并的报表（在 ExcelUtil.export 中全自动识别生效）
ExcelUtil.export(response, "部门花名册", staffList, DepartmentStaffVO.class);

// 4. 复杂模板填充：同时支持单变量占位符 {title} 与列表循环占位符 {.itemName}、{.status}、{.genderDesc}
Map<String, Object> variables = Map.of("title", "2026年度预算执行表", "date", "2026-10-16", "author", "Ateng");
List<BudgetExpenseVO> items = List.of(
    new BudgetExpenseVO("云服务器扩容", 50000.0, "0", UserGenderEnum.MALE),
    new BudgetExpenseVO("光纤专线接入", 12000.0, "1", UserGenderEnum.FEMALE)
);
// 使用 forceNewRow(true) 保证列表填充时自动下移模板后续行，防止覆盖底部的汇总公式与签字栏
FillConfig fillConfig = FillConfig.builder().forceNewRow(true).build();
ExcelUtil.fill(response, "预算执行报表", templateInputStream, variables, items, fillConfig);

// 5. 无实体动态导出：基于表头列表与 List<List<Object>> 或 List<Map<String, Object>> 直接导出
List<String> headers = List.of("工号", "商品名称", "销售金额");
List<List<Object>> dynamicData = List.of(
    List.of(101, "机械键盘", 499.0),
    List.of(102, "人体工学椅", 1299.0)
);
ExcelUtil.exportDynamic(response, "实时销售报表", headers, dynamicData);

// 6. 多 Sheet 链式构建器：连续配置多个 Sheet 并借助 try-with-resources 自动冲刷关闭
try (ExcelBuilder builder = ExcelUtil.builder(response, "企业综合运营报表")) {
    builder.sheet("员工信息", DepartmentStaffVO.class, staffList)
           .sheetDynamic("销售动态", headers, dynamicData);
}
```

> [!TIP] **模板填充中枚举与字典的映射契约**
> - **字典自动转义**：实体字段标注 `@ExcelDict("dictType")` 并显式声明 `@ExcelProperty(converter = ExcelDictConverter.class)`，模板变量 `{.status}` 填充时将自动触发 `DictProvider` SPI 转换为中文描述标签（如 `"0"` -> `"正常"`）；
> - **枚举呈现 `desc`（推荐派生 Getter）**：模板填充属于无表头注册的表达式属性反射提取流程。当需要将枚举（如 `gender`）输出为中文描述时，最直观可靠的规范是在 VO 中声明 `getGenderDesc()` 派生 Getter并在模板中使用占位符 `{.genderDesc}`；若在模板中直接声明 `{.gender}`，底层反射将回退调用枚举的 `toString()`，若枚举重写了 `toString()` 返回 `desc` 亦可自动呈现中文。

### 预期呈现契约
1. **复杂模板填充呈现**：
   - **预设 Excel 模板内容**：
     ```text
     【{title}】       编制人: {author}       制表日期: {date}
     +-----------------+-----------------+-----------------+-----------------+
     | 费用项目        | 经办人性别      | 报销状态        | 金额 (元)       |
     +-----------------+-----------------+-----------------+-----------------+
     | {.itemName}     | {.genderDesc}   | {.status}       | {.amount}       |
     +-----------------+-----------------+-----------------+-----------------+
     | 财务审批签字:                     | 部门总监签字:                     |
     ```
   - **导出生成 Excel 呈现效果**：
     ```text
     【2026年度预算执行表】   编制人: Ateng          制表日期: 2026-10-16
     +-----------------+-----------------+-----------------+-----------------+
     | 费用项目        | 经办人性别      | 报销状态        | 金额 (元)       |
     +-----------------+-----------------+-----------------+-----------------+
     | 云服务器扩容    | 男              | 正常            | 50000.0         |
     | 光纤专线接入    | 女              | 待审批          | 12000.0         |
     +-----------------+-----------------+-----------------+-----------------+
     | 财务审批签字:                     | 部门总监签字:                     |
     ```

2. **单元格纵向合并呈现**：
   | 部门 | 岗位 | 员工姓名 | 薪资 |
   | :--- | :--- | :--- | :--- |
   | 技术部 (合并 3 行) | 后端研发 (合并 2 行) | 张三 | 10000.0 |
   | | | 李四 | 12000.0 |
   | | 前端研发 | 王五 | 11000.0 |
   | 市场部 (合并 2 行) | 商务经理 (合并 2 行) | 赵六 | 8500.0 |
   | | | 钱七 | 9000.0 |

3. **多 Sheet 导出呈现**：
   生成的 `.xlsx` 文件底部同时呈现多个标签页（`员工信息`、`销售动态`），各 Sheet 分别持有独立的格式、表头与脱敏/合并策略。

---

## 10. 企业级多模版主题与自适应列宽 (@ExcelTheme & SmartAutoColumnWidth)

### 场景痛点
默认导出的 Excel 表格通常存在三大视觉与排版痛点：
1. **视觉粗糙老旧**：原生 Fesod/POI 导出无正文样式、表头采用陈旧宋体、纯白底色毫无层次感，难以直接向企业客户或管理层呈现；
2. **列宽计算失真**：原生自适应列宽按 UTF-8 字节计算导致中文字符宽度按 3 字符严重膨胀；且数字单元格由于缺少安全内边距，在 Excel 中经常因宽度不足显示为 `###`；
3. **样式侵入与代码繁琐**：开发者手动编写 POI `CellStyle`、`Font` 会导致大段样板代码侵入业务逻辑，且极易因样式对象超标引发 Excel 报错。

### 核心代码范式
`ateng-boot-excel` 内置 5 套企业级商务主题色系、奇偶行斑马纹与中英文精准加权自适应列宽：
- **内置 5 套主题**：`MINIMAL_CLEAN`（现代极简白，默认推荐）、`BUSINESS_BLUE`（经典商务蓝）、`CLASSIC_GREY`（稳重石墨灰）、`FRESH_GREEN`（清新薄荷绿）、`WARM_ORANGE`（活力温暖橙）；
- **三级平滑优先级**：`ExcelBuilder` 链式设置 > 类级 `@ExcelTheme` 注解 > `application.yml` 全局配置；
- **100% 兼容原生**：零冲突继承 Apache Fesod 原生 `@HeadStyle`、`@ContentStyle`、`@ColumnWidth` 等字段级定制注解；
- **纯原生零依赖**：基于 Fesod 原生 `HorizontalCellStyleStrategy` 实现，支持一键 `enabled: false` 彻底旁路关闭。

#### 1. 类级声明式主题与自适应列宽 (`@ExcelTheme`)
```java
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

// 声明该实体导出时采用清新薄荷绿主题，开启斑马纹与精准列宽（支持 "FRESH_GREEN" 或 "fresh-green"）
@Data
@ExcelTheme(value = "FRESH_GREEN", zebraStripe = true, autoColumnWidth = true)
public class EcoReportVO {

    @ExcelProperty("项目编号")
    private Long projectId;

    @ExcelProperty("项目名称")
    private String projectName;

    @ExcelProperty("碳减排量 (吨)")
    private Double carbonReduction;
}

// 正常导出，自动应用 FRESH_GREEN 绿色系样式与自适应列宽
ExcelUtil.export(response, "碳减排统计报表", dataList, EcoReportVO.class);
```

#### 2. Fluent 链式多 Sheet 样式定制 (`ExcelBuilder`)
```java
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.util.ExcelUtil;

try (ExcelBuilder builder = ExcelUtil.builder(response, "集团跨部门经营报表")) {
    builder.theme(ExcelThemeEnum.BUSINESS_BLUE)
           .zebraStripe(true)
           .autoColumnWidth(true)
           .sheet("国内业务汇总", DomesticVO.class, domesticList)
           .theme(ExcelThemeEnum.WARM_ORANGE) // 第二个 Sheet 独立切换为温暖橙主题
           .zebraStripe(false)
           .sheet("海外业务拓展", OverseasVO.class, overseasList);
}
```

#### 3. 字段级原生定制无缝兼容
```java
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.write.style.ContentStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import lombok.Data;

@Data
@ExcelTheme("BUSINESS_BLUE")
public class FinancialOrderVO {

    @ExcelProperty("订单流水号")
    private String orderNo;

    // 字段级通过 Fesod 原生 @ContentStyle 覆盖特定列样式（如金额列高亮或居右）
    @ExcelProperty("实付金额")
    @ContentStyle(fillPatternType = FillPatternType.SOLID_FOREGROUND, fillForegroundColor = 13)
    private Double paidAmount;
}
```

#### 预期主题呈现效果
| 主题代码 | 主题名称 | 表头背景色 | 表头字体 | 偶数行斑马纹 | 适用场景 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `MINIMAL_CLEAN` | 极简白 (默认) | 纯净无底色 (`NO_FILL`) | 微软雅黑 11pt 黑色加粗 | 纯白 / 细实线网格边框 | 默认通用、设计简约风、极简无彩色打印、轻量清单 |
| `CLASSIC_GREY` | 石墨灰 | 厚重石墨灰 (`#B0B7C0`) | 微软雅黑 11pt 黑色加粗 | 清晰浅灰 (`#DEE2E6`) 斑马纹交替 | 财务对账、严谨审计、公文报表、立体商务质感 |
| `BUSINESS_BLUE` | 商务蓝 | 沉稳海蓝 (`PALE_BLUE`) | 微软雅黑 11pt 黑色加粗 | 浅淡蓝 (`LIGHT_TURQUOISE`) | 企业级经典报表、系统后台数据清单 |
| `FRESH_GREEN` | 清新绿 | 活力翠绿 (`LIGHT_GREEN`) | 微软雅黑 11pt 黑色加粗 | 极淡薄荷黄绿 (`LEMON_CHIFFON`) | 环保能源、生鲜农业、健康医疗 |
| `WARM_ORANGE` | 温暖橙 | 活力暖橙 (`LIGHT_ORANGE`) | 微软雅黑 11pt 黑色加粗 | 极浅暖杏 (`LEMON_CHIFFON`) | 电商大促、营销获客、销售业绩排名 |

> [!TIP]
> **精准中英文自适应列宽算法说明**：
> `SmartAutoColumnWidthWriteHandler` 算法中，每个汉字/全角字符计 **2.0** 字符宽度，英文字母/半角数字/符号计 **1.0** 字符宽度，换行文本取各段最长分段，并在最终列宽基础上额外补偿 **2.0** 字符的单元格左右内边距（Padding），防止 Excel/WPS 因边距不足出现 `###` 遮挡。同时设置全局列宽上下限（默认最小 12 字符，最大 60 字符），防止极短字段缩紧变形或极长备注撑爆屏幕。

---

---

## 11. 海量数据异步导出任务中心 (`AsyncExportTaskService`)

### 场景痛点
数十万至百万级大数据量导出耗时长达数分钟，若采用普通 HTTP 同步接口，极易触发网络反向代理（Nginx/Gateway）`504 Gateway Timeout` 超时断连或浏览器标签页挂起；且缺乏后台生成进度可视化感知与物理文件生命周期管理。

### 核心代码范式
`ateng-boot-excel` 内置生产级开箱即用的异步导出任务中心抽象与基于受管线程池的内存调度器 `InMemoryAsyncExportTaskService`（支持未来无缝扩展 Redis/MQ/分布式集群架构）：
- **四维上下文全链路透传**：基于 `AsyncContext` 自动将请求线程的 $\langle\text{MDC}, \text{User}, \text{TenantId}, \text{IgnoreTenant}\rangle$ 状态无缝透传至后台线程，保证日志 traceId 贯通与多租户数据隔离，且任务执行后自动清理；
- **线程池过载拒绝防崩**：采用有界任务队列与快速失败保护，队列满时抛出语义化 `TOO_MANY_REQUESTS` 业务异常，杜绝任务倒灌阻塞 Web 容器（如 Tomcat）线程；
- **定时清理与平滑停机**：内置单线程定时清理调度器周期清理过期任务与物理临时文件，容器停止时提供平滑等待期，杜绝产物损坏。

```java
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.async.AsyncExportTaskService;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/excel/async")
public class AsyncExportController {

    @Autowired
    private AsyncExportTaskService asyncExportTaskService;

    @Autowired
    private OrderService orderService;

    // 1. 提交长耗时异步导出任务（秒级响应返回 taskId）
    @PostMapping("/export-orders")
    public Result<String> submitExportTask(@RequestParam String exportYear) {
        String taskId = asyncExportTaskService.submit("订单全量分析报表-" + exportYear, out -> {
            // 分批游标查询并流式写出，有效防御 OOM
            orderService.streamQueryByYear(exportYear, batchData -> {
                ExcelUtil.export(out, batchData, OrderReportVO.class);
            });
        });
        return Result.success(taskId, "任务已提交后台异步生成");
    }

    // 2. 轮询任务执行状态与进度
    @GetMapping("/task/{taskId}")
    public Result<ExportTask> getTaskProgress(@PathVariable String taskId) {
        ExportTask task = asyncExportTaskService.getTask(taskId);
        if (task == null) {
            return Result.fail(404, "导出任务不存在或已过期清理");
        }
        return Result.success(task);
    }

    // 3. 取消正在执行的导出任务
    @PostMapping("/task/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable String taskId) {
        boolean cancelled = asyncExportTaskService.cancel(taskId);
        return cancelled ? Result.success() : Result.fail("任务已完成或无法取消");
    }
}
```

### 预期任务进度查询 JSON 响应契约
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "taskId": "72f104c1a6ed446f8b4010e2a5d9cd0b",
    "taskName": "订单全量分析报表-2026",
    "status": "SUCCESS",
    "progress": 100,
    "filePath": "/data/tmp/ateng-excel-async/72f104c1a6ed446f8b4010e2a5d9cd0b.xlsx",
    "downloadUrl": null,
    "fileSize": 18452096,
    "errorMsg": null,
    "createTime": "2026-10-17T10:15:30",
    "finishTime": "2026-10-17T10:17:12"
  }
}
```

---

## 12. 批量导入差错隔离与红标纠错导出 (`FailSplitFeedback`)

### 场景痛点
业务人员批量导入几万条外部报表时，若包含几十处必填缺失或格式错误，传统系统要么“全量回滚”报个笼统的弹窗提示，要么仅给出难以对应位置的纯文本日志，业务人员必须在庞大原表中肉眼逐行排查，返工成本极高。

### 核心代码范式
基于 JSR-380 注解校验结果自动实施“合规数据即刻入库、差错数据隔离提取”，并在原有字段末尾自动追加【失败原因】汇总说明，对具体出错的单元格实施浅粉红背景高亮（`IndexedColors.ROSE`）与悬浮批注挂载。

```java
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/excel/import")
public class UserImportController {

    @Autowired
    private UserService userService;

    @PostMapping("/users-split")
    public void importWithFailSplit(
            @RequestParam("file") MultipartFile file,
            HttpServletResponse response
    ) throws Exception {
        // 1. 流式同步解析并执行规则校验（自动捕获并封装原始实体）
        ExcelReadResult<UserImportDTO> result = ExcelUtil.readWithResult(file.getInputStream(), UserImportDTO.class);

        // 2. 合规数据即刻入库（无须等待错误数据排查）
        if (!result.getData().isEmpty()) {
            userService.batchInsert(result.getData());
        }

        // 3. 若存在非法差错数据，一键生成结构化【纠错清单】下载响应供业务人员直接修改并重新上传
        if (!result.getErrors().isEmpty()) {
            ExcelUtil.exportFailedRows(response, "用户批量导入纠错清单", UserImportDTO.class, result.getErrors());
        }
    }
}
```

### 预期纠错工作表呈现效果
- **工作表名**：自动命名为 `纠错清单`；
- **动态追加列**：在实体最后一列自动追加【失败原因】表头，内容自动聚合该行所有违反的业务规则（如 `手机号: 格式不正确；年龄: 不能低于 18 岁`）；
- **单元格红标高亮**：对发生错误的具体单元格填充 `IndexedColors.ROSE` 浅粉色背景，光标悬浮时显示包含具体校验提示的 POI 批注。

---

## 13. 多媒体图片智能自适应与行高联动 (`@ExcelImage`)

### 场景痛点
商品封面、人脸证件照等多媒体图片导出时，传统方式需要手动编写繁琐的字节转换器与 POI Drawing Anchor 坐标计算；由于普通数据行行高默认仅约 15pt，图片会被强行挤压成一条细线完全无法辨认，手工逐行拉高耗时费力。

### 核心代码范式
使用 `@ExcelImage` 注解修饰实体中的 `String` 类型图片字段，系统全自动智能探测：
1. 网络 `http://` / `https://` 图片 URL（内置连接与读取超时防御，安全直达）；
2. 标准 Base64 数据流（带前缀 `data:image/...;base64,` 或纯 Base64）；
3. 本地绝对文件路径或 `file://` URI；
4. 自动感知并撑高所在数据行行高（默认适配 `60pt`，支持按需注解覆盖），未标注的常规字符串毫秒级直通输出文本。

```java
import io.github.atengk.boot.excel.annotation.ExcelImage;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class ProductCatalogVO {

    @ExcelProperty("商品编码")
    private String productCode;

    @ExcelProperty("商品名称")
    private String productName;

    // 声明为多媒体图片列，自适应撑高数据行至 75pt
    @ExcelProperty("商品展示图")
    @ExcelImage(rowHeight = 75, connectTimeout = 3000, readTimeout = 5000)
    private String coverImageUrl; // 支持网络 URL、Base64 或本地路径

    @ExcelProperty("商品简介")
    private String summary;
}

// 正常导出，自动挂载 ImageRowHeightWriteHandler 与 SmartImageConverter
ExcelUtil.export(response, "商品多媒体图册", productList, ProductCatalogVO.class);
```

### 预期控制台与视觉契约
- 数据行行高由系统自动设为 `75pt`，图片按单元格边界等比居中自适应绘制；
- 工作簿图片资源表中自动嵌入图片二进制流，脱离网络环境离线浏览仍完整可见。

---

## 14. 防拍照半透明全屏暗水印与工作表只读保护

### 场景痛点
企业级财务薪酬、高管业绩等敏感 Excel 文件极易被翻拍泄露；普通绘图层水印会阻挡用户点击单元格或查看公式，且未锁定的工作表在流转过程中极易被篡改核心数字。

### 核心代码范式
`ateng-boot-excel` 采用 POI 底层关系注入全屏平铺背景暗水印（Background Picture）：
- **全屏平铺**：文字动态生成为 -30° 倾斜、半透明（Alpha ~ 0.18）的防伪 PNG 切片，屏幕浏览与打印 100% 全屏平铺；
- **零点击遮挡**：不占用 Drawing 绘图层，100% 不妨碍单元格选择、编辑与公式输入；
- **防篡改只读锁定**：协同配置密码锁定工作表结构，阻止他人随意修改数字。

#### 1. 静态门面一键导出水印与保护
```java
import io.github.atengk.boot.excel.util.ExcelUtil;

// 导出带全屏防拍照暗水印与只读保护密码的 Excel
String watermarkText = "绝密 · 张三 (E1001) · 2026-10-17";
String protectPassword = "FinSecurity@2026";

ExcelUtil.exportWithWatermark(
        response,
        "年度薪资决算明细",
        salaryList,
        SalaryDetailVO.class,
        watermarkText,
        protectPassword
);
```

#### 2. Fluent 链式多 Sheet 独立配置
```java
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.util.ExcelUtil;

try (ExcelBuilder builder = ExcelUtil.builder(response, "年度经营决算")) {
    builder.watermark("核心机密 · 严禁外传")
           .protect("pwdSecret888")
           .sheet("机密预算汇总", BudgetVO.class, budgetList)
           // 后续 Sheet 独立解绑水印与只读锁定
           .sheet("公开经营通告", NoticeVO.class, noticeList);
}
```

---

## 15. 海量数据游标分页与跨 Sheet 自动轮转导出 (`StreamingPageExportTemplate`)

### 场景痛点
单次查询数十万乃至百万行数据进行导出时，若一次性将全量数据查出载入 List 集合，会瞬间撑爆 JVM 堆内存导致服务 OOM；此外，单个 Excel 工作表（Sheet）存在 1,048,576 行物理极限，超出阈值时传统写入会直接报错崩溃。

### 核心代码范式
`StreamingPageExportTemplate` 抽象了基于游标的分页拉取流水线，由开发者提供 `PageDataReader<T>` 分页回调函数；引擎每读取一页立即流式写出到底层流并清空该页内存对象引用；当单表写入行数达到配置的上限（默认 1,000,000 行）时，自动新建下一个 Sheet（如 `用户数据_1`、`用户数据_2`）平滑续写：

```java
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.template.StreamingExportOptions;
import io.github.atengk.boot.excel.template.StreamingPageExportTemplate;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/excel")
public class ExcelSampleController {

    @Autowired
    private StreamingPageExportTemplate streamingPageExportTemplate;

    @GetMapping("/export-streaming-page")
    public void exportStreamingPage(
            HttpServletResponse response,
            @RequestParam(defaultValue = "1000") int pageSize,
            @RequestParam(defaultValue = "10000") int maxRowsPerSheet) throws IOException {
        
        ExcelUtil.configureResponse(response, "海量用户游标分页导出");
        
        StreamingExportOptions options = StreamingExportOptions.builder()
                .pageSize(pageSize)
                .maxRowsPerSheet(maxRowsPerSheet)
                .sheetNamePrefix("用户明细")
                .build();

        streamingPageExportTemplate.export(
                response.getOutputStream(),
                UserExcelVO.class,
                (pageIndex, size) -> fetchUserPageFromDatabase(pageIndex, size),
                options
        );
    }
}
```

---

## 16. 导入前置表头契约校验与行校验错误熔断 (`ExcelHeaderValidator` & Circuit Breaker)

### 场景痛点
- **错版模板全量穿透**：用户上传了格式错乱、列名不匹配或缺少核心列的错误模板，传统方式往往一行行解析并抛出大量无意义转换异常，严重浪费服务端 CPU 算力；
- **错误行穿透与内存耗尽**：当用户误传了数万行脏数据时，若逐行积累错误集，会导致错题集对象内存急剧膨胀，最终引爆堆内存。

### 核心代码范式
#### 1. 前置轻量表头校验 (`ExcelHeaderValidator`)
在解析全量数据前，预读首行表头比对 `@ExcelProperty` 契约，支持 `STRICT`（严格匹配列名与顺序）与 `LOOSE`（宽松全包含匹配）策略，不匹配立即抛出 `ExcelHeaderValidationException` 快速失败：

```java
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.util.ExcelHeaderValidator;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.springframework.web.multipart.MultipartFile;

// 1. 预读并校验表头，失败立即抛出 ExcelHeaderValidationException
ExcelHeaderValidator.validate(file.getInputStream(), UserExcelVO.class, HeaderValidationStrategy.STRICT);

// 2. 表头契约合法后，执行后续正常读取与业务入库
List<UserExcelVO> userList = ExcelUtil.read(file.getInputStream(), UserExcelVO.class);
```

#### 2. 错误行数阈值紧急熔断 (`BatchValidationListener` 熔断机制)
通过在监听器中配置 `maxErrorRows`（如 500 行），当累计校验失败错误行数达到该阈值时触发熔断，立即中断解析并抛出 `ExcelValidationException`：

```java
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.Validator;

BatchValidationListener<UserExcelVO> listener = new BatchValidationListener<>(
        UserExcelVO.class,
        validator,
        false, // 是否单行失败即 failFast
        500    // maxErrorRows: 错误行数达到 500 行时紧急熔断终止
);
ExcelUtil.readWithListener(file.getInputStream(), UserExcelVO.class, listener);
```

---

## 17. CSV/Excel 公式注入主动防御与列级只读锁定 (`@ExcelFormulaAllowed` & `@ExcelColumnLock`)

### 场景痛点
- **DDE 公式注入漏洞 (CSV/Excel Formula Injection)**：用户在输入框恶意输入 `=cmd|'/C calc'!A0`、`@SUM(...)` 等公式前缀，被系统导出至 Excel 后，管理员打开该文件即可被触发远程命令执行或数据泄露；
- **模板固定列被恶意篡改**：在分发给业务员填报的模版中，系统预生成的“用户ID”或“业务编号”若被随意修改，将导致回传导入时持久层外键关联错乱。

### 核心代码范式
#### 1. 公式注入主动防御与 `@ExcelFormulaAllowed` 白名单
Starter 默认开启公式安全防护，自动拦截以 `=, +, -, @, \t, \r` 开头的字符串文本并前置单引号 `'`（Excel 仅按纯文本渲染，不执行任何 DDE 公式计算）；如确有字段需要合法导出 Excel 计算公式（如自动小计、求和），可显式挂载 `@ExcelFormulaAllowed` 白名单豁免：

```java
import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import io.github.atengk.boot.excel.annotation.ExcelFormulaAllowed;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class SecurityUserExcelVO {

    @ExcelProperty("用户编号")
    @ExcelColumnLock(locked = true)
    private Long id;

    @ExcelProperty("用户姓名")
    // 若输入内容为 "=cmd|' /C calc'!A0"，导出时自动安全转义为 "'=cmd|' /C calc'!A0"
    private String username;

    @ExcelProperty("结算计算公式")
    @ExcelFormulaAllowed // 显式豁免公式防御，允许合法公式计算（如 =SUM(C2:C10)）
    private String formula;

    @ExcelProperty("系统只读备注")
    @ExcelColumnLock(locked = true)
    private String remark;
}
```

#### 2. 列级锁定与防呆填报模板生成
结合 `@ExcelColumnLock(locked = true)` 与工作表密码保护，精准锁定指定列禁止修改：

```java
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;

@GetMapping("/template-security")
public void downloadSecurityTemplate(HttpServletResponse response) {
    List<SecurityUserExcelVO> templateData = List.of(
            new SecurityUserExcelVO(1L, "=cmd|' /C calc'!A0", "=SUM(10,20)", "系统只读项")
    );
    // 配置保护密码，未标记 @ExcelColumnLock 的列保持解锁可编辑，锁定列只读
    ExcelUtil.exportWithWatermark(
            response,
            "防呆录入模板",
            templateData,
            SecurityUserExcelVO.class,
            "防呆模板",
            "lockSecret888"
    );
}
```

---

## 18. 国际化多语言动态表头 (`I18nHeadWriteHandler`)

### 场景痛点
跨国或多语言企业应用中，同一份报表需要根据当前登录用户的前端语言偏好（中文、英文等）动态渲染表头文字，而 `@ExcelProperty` 默认只支持静态字符串硬编码。

### 核心代码范式
在 VO 对象的 `@ExcelProperty` 中声明 `{i18n:key}` 格式占位符，框架自动结合 Spring `MessageSource` 与当前线程请求的 `LocaleContextHolder.getLocale()`（支持由 Accept-Language、URL 参数或用户会话提供）动态转译表头：

```java
import org.apache.fesod.sheet.annotation.ExcelProperty;
import lombok.Data;

@Data
public class I18nUserExcelVO {

    @ExcelProperty("{i18n:user.id}")
    private Long id;

    @ExcelProperty("{i18n:user.name}")
    private String name;

    @ExcelProperty("{i18n:user.email}")
    private String email;
}
```

配套国际化资源文件配置（`messages.properties` / `messages_en_US.properties`）：
```properties
# messages_zh_CN.properties
user.id=用户编号
user.name=用户姓名
user.email=电子邮箱

# messages_en_US.properties
user.id=User ID
user.name=User Name
user.email=Email Address
```

```java
@GetMapping("/export-i18n")
public void exportI18nUsers(HttpServletResponse response) {
    // 自动根据当前请求线程的 Locale 动态解析表头占位符
    ExcelUtil.export(response, "多语言用户清单", userList, I18nUserExcelVO.class);
}
```

---

## 19. 配置属性参考

在 `application.yml` 中按需定制全局行为、样式、异步任务中心、流式分页与安全策略：

```yaml
ateng:
  boot:
    excel:
      # 是否启用自动装配（默认 true）
      enabled: true
      # 流式读取默认批次大小（默认 1000）
      default-batch-size: 1000
      # 同步全量读取最大允许行数上限（默认 100,000，超过时快速失败以防御内存 OOM；大数据量请使用 readBatch 分批流式读取）
      max-sync-rows: 100000
      # 大文件磁盘溢出临时目录（留空时使用系统默认临时目录）
      temp-dir: /data/tmp/fesod
      
      # 异步导出任务中心配置
      async-export:
        # 是否启用异步导出中心（默认 true）
        enabled: true
        # 异步导出产物物理文件落盘目录（留空时使用 java.io.tmpdir/ateng-excel-async）
        temp-dir: /data/tmp/ateng-excel-async
        # 核心线程池大小（默认 4）
        core-pool-size: 4
        # 最大线程池大小（默认 8）
        max-pool-size: 8
        # 缓冲队列容量（默认 200）
        queue-capacity: 200
        # 产物物理文件默认保留过期时长（默认 24h，支持 24h、1d 等标准 Duration 格式）
        retention-period: 24h
        # 任务执行租约超时时间（默认 30m，执行中定期自动续期，节点宕机超期后查询自动原子标记为 FAILED）
        lease-timeout: 30m
        # Redis 分布式任务中心配置
        redis:
          # 是否启用基于 Redis 的分布式异步导出任务中心（默认 true，当应用引入 Redis 时自适应激活）
          enabled: true
          # 任务在 Redis 中的键前缀（默认 "ateng:excel:async-task:"）
          key-prefix: "ateng:excel:async-task:"
          # 任务元数据在 Redis 中的保留与有效时长（默认 24h）
          ttl: 24h

      # 海量数据流式分页导出配置
      streaming-export:
        # 单个工作表最大允许写入行数上限（超过时自动换 Sheet 分割，默认 1,000,000 行）
        max-rows-per-sheet: 1000000
        # 流式分页拉取默认步长大小（默认 1000 行/页）
        default-page-size: 1000

      # 导入数据校验与错误熔断配置
      validation:
        # 导入表头契约校验策略（STRICT: 严格匹配列名与顺序; LOOSE: 宽松全包含匹配; DISABLED: 禁用表头校验；默认 LOOSE）
        header-strategy: LOOSE
        # 错误行数熔断阈值（默认 500 行；当单次导入校验失败错误行数达到该阈值时触发熔断终止解析，避免 OOM）
        max-error-rows: 500

      # 安全防护与公式注入防御配置
      security:
        # 是否开启公式注入安全防御（默认开启，导出时对以 =, +, -, @, \t, \r 开头的字符串前置单引号）
        formula-protection: true

      # 国际化多语言表头转译配置
      i18n:
        # 是否开启多语言国际化表头动态转译（默认开启）
        enabled: true

      # 样式与主题配置
      style:
        # 是否启用全局样式拦截增强（默认 true；设置为 false 时完全关闭样式与列宽增强，零开销）
        enabled: true
        # 默认商务主题：minimal-clean / business-blue / classic-grey / fresh-green / warm-orange（默认 minimal-clean）
        theme: minimal-clean
        # 是否默认开启奇偶行斑马纹交替底色（默认 true）
        zebra-stripe: true
        # 是否默认启用中英文加权自适应列宽（默认 true）
        auto-column-width: true
        # 自适应最小列宽字符数（默认 12，防止极短列挤压变形）
        min-column-width: 12
        # 自适应最大列宽字符数（默认 60，防止大文本列撑裂屏幕）
        max-column-width: 60
        # 自适应内边距补偿字符数（默认 2，预留左右安全间隙，杜绝数值变 ###）
        padding-column-width: 2
        # 默认全局字体名称（默认 微软雅黑）
        font-name: "微软雅黑"
```

---

## 20. 生产扩展与高阶定制 (Customization & Extensibility)

`ateng-boot-excel` 遵循模块解耦与 SPI 扩展原则，支持企业级核心能力的平滑自定义接管：

### 20.1 自定义动态数据字典提供者 (`DictProvider` SPI)
当系统使用自定义数据库表、Redis 或远程 RPC 服务维护数据字典时，只需向 Spring 容器注册 `DictProvider` Bean 即可无缝接管 `@ExcelDict` 翻译：

```java
@Component
public class RemoteDictProvider implements DictProvider {

    @Autowired
    private RemoteDictFeignClient dictFeignClient;

    @Override
    public String toText(String dictType, String dictValue) {
        return dictFeignClient.queryText(dictType, dictValue);
    }

    @Override
    public String toValue(String dictType, String dictText) {
        return dictFeignClient.queryValue(dictType, dictText);
    }
}
```

### 20.2 自定义异步导出文件存储适配器 (`ExportFileStorage` SPI)
当企业集群需要将导出的 Excel 文件持久化上传至云端对象存储（如 MinIO、阿里云 OSS、腾讯云 COS）时，仅需实现 `ExportFileStorage` 并注册为 Spring Bean，即可平滑无缝替换本地磁盘存储：

```java
@Component
public class OssExportFileStorage implements ExportFileStorage {

    @Autowired
    private OssClient ossClient;

    @Override
    public OutputStream openOutputStream(String taskId, String fileName) throws IOException {
        // 返回流式分片上传至 OSS 的输出流
        return ossClient.createMultipartUploadStream(taskId, fileName);
    }

    @Override
    public InputStream openInputStream(String taskId, String storageKey) throws IOException {
        // 从 OSS 流式下载产物文件
        return ossClient.getObject(storageKey);
    }

    @Override
    public boolean delete(String taskId, String storageKey) {
        return ossClient.deleteObject(storageKey);
    }
}
```

### 20.3 分布式集群异步导出任务中心 (`RedisAsyncExportTaskService`)
当应用部署为多实例多节点微服务集群时，只需引入 `spring-boot-starter-data-redis`，框架将自动通过 `RedisAsyncExportConfiguration` 激活 `RedisAsyncExportTaskService`：
- **状态机集群可见**：任务状态保存在 Redis 哈希结构中，任意节点提交的任务，均可在任意节点查询进度；
- **宕机租约防御**：配合 `lease-timeout`（默认 30m），若某个工作节点在执行任务期间宕机，超时后查询状态将自动原子标记为 `FAILED`，避免任务永久处于 `RUNNING` 假死状态。

---

## 21. 核心生产边界与非功能语义 (Production Boundary & Failure Modes)

### 21.1 流式事件驱动与内存防爆 (OOM 防御)
- **逐行事件流读取**：底层基于 Apache Fesod 事件模型，逐行解析 XML 标记并不在堆中滞留全表模型；调用 `readBatch` 时每累积至 `batchSize`（默认 1000 行）即回调消费并清空内部缓冲区，千万级行数导入亦可维持平稳的常数级堆内存开销。
- **同步全量读取安全阈值**：对于一次性返回 List 的同步接口 `ExcelUtil.read` 与 `readWithValidation`，内置 `maxSyncRows`（默认 100,000 行）快速失败防线；超过阈值立即抛出 `BusinessException` 阻断，杜绝恶意或误传超大文件导致 JVM FullGC 与 OOM。

### 21.2 单 Sheet 百万行物理极限与自动分表轮转
- **Excel 物理极限**：Microsoft Excel 单个 Sheet 规范物理上限为 1,048,576 行；
- **自适应分表**：`StreamingPageExportTemplate` 内部维护了行数累加游标，当写入行数逼近 `maxRowsPerSheet`（默认 1,000,000 行）时，自动触发跨 Sheet 轮转并生成新工作表，有效防御由于超出 Excel 物理容量限制导致的写出崩溃。

### 21.3 CSV/Excel 公式注入 (DDE) 原理与防御边界
- **漏洞成因**：Microsoft Excel 与 WPS 等电子表格软件在打开含有 `=`, `+`, `-`, `@`, `\t`, `\r` 前缀的单元格时，会将其默认解释为公式或 DDE 外部命令（例如 `=cmd|' /C calc'!A0`）；
- **沙箱隔离**：框架在写出文本单元格时，主动探测特殊前缀并在文本前补齐单引号 `'`（如 `'=cmd...`）。这使电子表格软件强制将该单元格渲染为纯文本展示，彻底阻断公式解析与命令执行；
- **白名单机制**：对需要合法生成公式计算的单元格，开发者可使用 `@ExcelFormulaAllowed` 显式开辟白名单通道。

### 21.4 导入契约防卫与错误熔断非功能边界
- **预读表头防穿透**：通过 `ExcelHeaderValidator` 在零数据反序列化成本下轻量扫描第一行，一旦列缺失或列名不匹配立即 Fail-Fast，拦截不合规的错版模板；
- **熔断防耗尽**：`BatchValidationListener` 的 `maxErrorRows`（默认 500 行）机制保证了即使客户端上传了数万行完全非法的错误数据，系统也会在达到阈值后立即熔断终止读取并抛出异常，杜绝无休止的校验计算与错题集对象膨胀。

### 21.5 内存真明文与脱敏拦截原理
- **切面拦截写出**：脱敏统一收敛在 `SensitiveCellWriteHandler` 的 `afterCellDataConverted` 阶段，仅修改输出至 Excel 单元格的字面量文本（`cellData.setStringValue(maskedValue)`）；
- **内存真明文不变量**：内存中的实体 Bean、Service 业务逻辑与上下文保持 100% 原始真实明文，彻底根除因在业务层打星号导致掩码脏数据写回持久层的致命隐患。

### 21.6 确定性资源释放与临时文件物理清理
- **流与连接绝对清理**：所有导出、填充与反馈工具方法严格在 `try-with-resources` 或 `finally` 块中关闭 `Workbook` 与流对象；
- **异步半成品补偿清理**：异步导出任务在发生异常中断或调用 `cancel(taskId)` 时，自动物理删除磁盘上已生成的半成品临时文件；同时由守护线程定期根据 `retentionPeriod` 物理回收历史过期文件，防止磁盘物理空间耗尽。






