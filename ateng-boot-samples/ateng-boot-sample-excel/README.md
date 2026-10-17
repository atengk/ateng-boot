# ateng-boot-sample-excel 轻量流式 Excel 处理自验工程

> 基于 Apache Fesod 的大批量流式导出、导出脱敏、导入行级校验与红字批注回写示范。

---

## 1. 场景痛点与功能定位

传统 Excel 处理框架（如 EasyExcel 或原生 POI）容易引发三大痛点：
1. 大数据量导出时极易产生堆内存溢出（OOM）；
2. 敏感数据导出时容易在内存中打星号导致破坏“内存真明文”底线；
3. 导入遇到数据格式错误时难以友好回写批注提示给业务人员。

`ateng-boot-sample-excel` 提供专一、纯粹的轻量流式解决方案：
- **零数据库依赖（Zero DB Dependency）**：纯 Web 容器模式，无需配置任何数据源；
- **Apache Fesod 极低内存开销**：流式行级写入，百万级数据平稳导出；
- **导出动态脱敏（`@ExcelSensitive`）**：严格践行 ADR-0012 规范，内存中保持真实明文，仅在写出 Excel 时进行动态脱敏；
- **JSR-303 导入校验与红字批注回写**：`BatchValidationListener` 自动拦截行级校验错误，`ExcelFeedbackUtil` 将错误原因作为单元格红字批注回写至原文件返回。

---

## 2. 最小可用代码与启动测试

### 本地启动
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-excel spring-boot:run
```

### 实体注解与导出代码示例
```java
public class UserExcelVO implements Serializable {
    @ExcelProperty(value = "用户姓名", index = 1)
    private String username;

    @ExcelSensitive(SensitiveType.MOBILE_PHONE)
    @ExcelProperty(value = "联系手机", index = 2)
    private String phone;

    @ExcelSensitive(SensitiveType.EMAIL)
    @ExcelProperty(value = "电子邮箱", index = 3)
    private String email;
}

@RestController
@RequestMapping("/api/excel")
public class ExcelSampleController {

    @GetMapping("/export")
    public void exportUsers(HttpServletResponse response) throws IOException {
        List<UserExcelVO> userList = getMockUserList();
        ExcelUtil.export(response, "用户列表.xlsx", UserExcelVO.class, userList);
    }
}
```

### 验证流式导出
```bash
curl -i http://localhost:8080/api/excel/export -o users.xlsx
```

**预期效果**：生成的 `users.xlsx` 文件中，电话号码列自动呈现为 `138****0000` 脱敏掩码格式，而后台业务内存中仍为真实原始明文。

---

## 3. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-excel -am -T 1C
```
涵盖流式导出、脱敏防呆、导入行级校验拦截与批注回写等 10 个测试用例，耗时仅 4~5 秒。
