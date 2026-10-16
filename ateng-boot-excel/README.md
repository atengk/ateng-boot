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

## 9. 配置属性参考

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




