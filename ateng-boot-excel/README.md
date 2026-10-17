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
import io.github.atengk.boot.excel.enums.ExcelTheme;
import io.github.atengk.boot.excel.util.ExcelUtil;

try (ExcelBuilder builder = ExcelUtil.builder(response, "集团跨部门经营报表")) {
    builder.theme(ExcelTheme.BUSINESS_BLUE)
           .zebraStripe(true)
           .autoColumnWidth(true)
           .sheet("国内业务汇总", DomesticVO.class, domesticList)
           .theme(ExcelTheme.WARM_ORANGE) // 第二个 Sheet 独立切换为温暖橙主题
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
`ateng-boot-excel` 内置生产级开箱即用的异步导出任务中心抽象与基于受管线程池的内存调度器 `InMemoryAsyncExportTaskService`（支持未来无缝扩展 Redis/MQ/分布式集群架构）。

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
        return Result.success("任务已提交后台异步生成", taskId);
    }

    // 2. 轮询任务执行状态与进度
    @GetMapping("/task/{taskId}")
    public Result<ExportTask> getTaskProgress(@PathVariable String taskId) {
        ExportTask task = asyncExportTaskService.getTask(taskId);
        if (task == null) {
            return Result.failure(404, "导出任务不存在或已过期清理");
        }
        return Result.success(task);
    }

    // 3. 取消正在执行的导出任务
    @PostMapping("/task/{taskId}/cancel")
    public Result<Void> cancelTask(@PathVariable String taskId) {
        boolean cancelled = asyncExportTaskService.cancel(taskId);
        return cancelled ? Result.success() : Result.failure("任务已完成或无法取消");
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

## 15. 配置属性参考

在 `application.yml` 中按需定制全局行为、样式与异步导出中心参数：

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
      # 异步导出任务中心配置
      async-export:
        # 异步导出产物物理文件落盘目录（留空时使用 java.io.tmpdir/ateng-excel-async）
        temp-dir: /data/tmp/ateng-excel-async
        # 核心线程池大小（默认 4）
        core-pool-size: 4
        # 最大线程池大小（默认 8）
        max-pool-size: 8
        # 缓冲队列容量（默认 200）
        queue-capacity: 200
        # 产物物理文件保留小时数（默认 24 小时）
        retention-hours: 24
      # 样式与主题配置
      style:
        # 是否启用全局样式拦截增强（默认 true；设置为 false 时完全关闭样式与列宽增强，零开销）
        enabled: true
        # 默认商务主题：MINIMAL_CLEAN / BUSINESS_BLUE / CLASSIC_GREY / FRESH_GREEN / WARM_ORANGE（默认 MINIMAL_CLEAN）
        theme: MINIMAL_CLEAN
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
```





