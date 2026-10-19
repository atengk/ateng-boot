# ateng-boot-sample-excel 轻量流式 Excel 处理自验工程

> 基于 Apache Fesod 的大批量流式导出、多 Sheet 链式编排、纵向单元格合并、导出动态脱敏、防呆模板生成、双向枚举/字典转义、导入行级校验与错题集标红批注回写示范。

---

## 1. 场景痛点与功能定位

传统 Excel 处理框架（如 EasyExcel 或原生 POI）容易引发以下痛点：
1. **内存泄漏与 OOM**：大数据量导出时一次性在堆内存创建庞大对象树导致服务崩溃；
2. **脱敏脏写污染**：在 Service 业务逻辑中直接对实体打星号，极易引发带掩码的脏数据回写数据库；
3. **导入排错成本高**：校验失败时仅返回一个干瘪的行号，业务人员难以定位具体出错单元格与修改指南；
4. **模板防呆缺失**：手工制作模板易敲错枚举与字典代号，维护成本极高；
5. **多工作表与复杂排版困难**：多 Sheet 导出样板代码长达几十行，单元格合并与多主题切换极其繁琐。

`ateng-boot-sample-excel` 提供专一、纯粹的轻量流式解决方案：
- **零数据库依赖（Zero DB Dependency）**：纯 Web 容器模式，无需配置任何数据库，开箱即用；
- **Apache Fesod 极低内存开销**：流式行级写入，百万级数据平稳导出，支持大文件后台异步受管任务；
- **内存真明文不变量（`@ExcelSensitive`）**：严格践行 ADR-0012 规范，业务实体在 JVM 内存中 100% 保持原始明文，仅在写出 Excel 时流式拦截掩码；
- **防呆模板智能生成（`@ExcelDropdown` & `@ExcelPrompt`）**：支持静态候选与动态 `DropdownProvider` SPI 下拉菜单，表头自动嵌入操作指南批注；
- **双向业务转义（`@ExcelEnum` & `@ExcelDict`）**：导出时自动转义为中文描述，导入时双向反向解析还原为强类型枚举与字典原始代码；
- **Fluent 多工作表链式构建器（`ExcelBuilder`）**：支持在单个工作簿内连续编排实体 Sheet、动态表头 Sheet，自由定制不同主题色系与斑马纹；
- **声明式相邻行纵向合并（`@ExcelMerge`）**：自动将部门、类别等相邻相同值的单元格执行纵向合并；
- **JSR-303 导入校验与错题集标红批注回写**：`BatchValidationListener` 自动拦截行级校验错误，`ExcelFeedbackUtil` 将单元格背景标红并挂载错误提示浮动批注回写给客户端。

---

## 2. 演示场景清单

| 演示场景 | 核心门面 / 注解 | 业务类与端点 | 核心特性与行为 |
| :--- | :--- | :--- | :--- |
| **基础流式脱敏导出** | `ExcelUtil.export`<br>`@ExcelSensitive` | `GET /api/excel/export` | 手机号、邮箱写出时动态掩码，内存保持真明文；RFC 5987 / 6266 标准防乱码响应头 |
| **多 Sheet 链式编排** | `ExcelBuilder`<br>`@ExcelTheme` | `GET /api/excel/export-multi-sheet` | Fluent 编排 Sheet 1（用户清单/商务蓝）与 Sheet 2（销售汇总/极简白），自由切换主题与斑马纹 |
| **纵向单元格合并** | `@ExcelMerge` | `GET /api/excel/export-merge` | 相邻行相同部门名称纵向自动合并，消除冗余重叠文字 |
| **智能防呆模板下载** | `@ExcelDropdown`<br>`@ExcelPrompt` | `GET /api/excel/template` | 性别静态下拉、部门动态 `SampleDepartmentDropdownProvider` 下拉约束与表头悬浮批注 |
| **双向枚举与字典解析** | `@ExcelEnum`<br>`@ExcelDict` | `POST /api/excel/import` | 导入时将表格中的“女”和“停用”双向反向还原为枚举 `SampleGenderEnum.FEMALE` 与字典代码 `"1"` |
| **行级校验与结构化返回** | `BatchValidationListener`<br>JSR-303 | `POST /api/excel/import` | 姓名、手机号正则、邮箱格式校验，全量聚合错误行并返回 HTTP 200 + 400 业务 Result |
| **错题集单元格标红回写** | `ExcelFeedbackUtil` | `POST /api/excel/import-feedback` | 校验失败时将出错单元格浅粉色高亮并嵌入浮动批注回写；合规时返回强类型成功 Result |
| **无实体动态表头导出** | `ExcelUtil.exportDynamic` | `GET /api/excel/export-dynamic` | 动态 `List<List<String>>` 表头与二维列表数据直接流式写出 |
| **受管异步导出任务中心** | `AsyncExportTaskService` | `POST /api/excel/async-export`<br>`GET /api/excel/async-task/{taskId}` | 受管线程池异步生成大文件、进度感知、防重复队列拦截与文件落地 |

---

## 3. 本地启动与端点自验

### 本地启动
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-excel spring-boot:run
```

### 3.1 验证基础流式脱敏导出
```bash
curl -i http://localhost:8080/api/excel/export -o 用户列表.xlsx
```
**预期效果**：
- 响应头自动配置：
  ```http
  Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8
  Content-Disposition: attachment; filename="用户列表.xlsx"; filename*=UTF-8''%E7%94%A8%E6%88%B7%E5%88%97%E8%A1%A8.xlsx
  ```
- 手机号自动掩码为 `138****8000`，邮箱脱敏掩码，性别枚举转义为 `男`，状态代码转义为 `正常`。

### 3.2 验证 Fluent 多 Sheet 复杂流式导出
```bash
curl -i http://localhost:8080/api/excel/export-multi-sheet -o 综合多工作表报表.xlsx
```
**控制台日志**：
```text
2026-10-19 16:27:23.420 INFO [main] [adaf7dbfbbdecb7c85547daa584b9117] i.g.a.b.s.e.service.ExcelSampleService : 成功流式写出多 Sheet 报表: sheets=[用户清单, 销售汇总]
```
**预期工作簿效果**：
- 包含 2 个工作表：Sheet 1 `用户清单`（商务蓝主题 + 斑马纹交替底色）、Sheet 2 `销售汇总`（动态大区季度销售指标）。

### 3.3 验证纵向单元格合并导出
```bash
curl -i http://localhost:8080/api/excel/export-merge -o 部门员工合并报表.xlsx
```
**预期效果**：第 0 列相同部门（研发部、市场部）相邻行自动纵向合并为跨行大单元格。

### 3.4 验证防呆模板下载
```bash
curl -i http://localhost:8080/api/excel/template -o 用户导入模板.xlsx
```
**预期效果**：
- 用户姓名与所属部门表头单元格右上角标红，悬浮浮现操作指南批注；
- 点击性别列弹出 `男/女/未知` 下拉选单；
- 点击部门列弹出 `研发部/市场部/财务部/运营部/人力资源部` 动态提供者下拉选单。

### 3.5 验证 JSR-303 导入校验与双向枚举/字典解析
```bash
curl -X POST "http://localhost:8080/api/excel/import" -F "file=@reverse_mapping.xlsx"
```
**合规导入与反向解析预期响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": [
    {
      "userId": "2002",
      "username": "孙俪",
      "phone": "13700137000",
      "email": "sunli@example.com",
      "gender": "FEMALE",
      "department": "财务部",
      "status": "1"
    }
  ],
  "traceId": "c8d2467e1eea09fd726f725d6f0d4f57",
  "success": true
}
```

### 3.6 验证错题集单元格标红批注回写
```bash
curl -X POST "http://localhost:8080/api/excel/import-feedback" -F "file=@invalid_users.xlsx" -o 导入错误反馈.xlsx
```
**预期效果**：未通过校验的姓名单元格填充浅粉色背景，并挂载悬浮批注：`用户姓名不能为空`。

### 3.7 验证受管异步导出任务中心
```bash
# 1. 提交异步导出任务
curl -X POST "http://localhost:8080/api/excel/async-export?taskName=全量用户导出"
```
**返回响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": "399aa0f9cad0453c8ec49212ef9b48b4",
  "traceId": "d4eb59b05a176ac02873355c2a480044",
  "success": true
}
```
```bash
# 2. 查询异步任务状态
curl "http://localhost:8080/api/excel/async-task/399aa0f9cad0453c8ec49212ef9b48b4"
```
**完成状态响应**：
```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "taskId": "399aa0f9cad0453c8ec49212ef9b48b4",
    "taskName": "全量用户导出",
    "status": "SUCCESS",
    "progress": 100,
    "fileSize": "4024"
  },
  "traceId": "d4eb59b05a176ac02873355c2a480044",
  "success": true
}
```

---

## 4. 定向集成测试

本工程集成测试通过 JUnit 5 原生 `@TempDir` 实现临时文件自动隔离与物理销毁，**100% 离线自洽独立运行**：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-excel -am -T 1C -nsu -B
```

测试集（共 14 项端到端集成用例）全面覆盖：
1. 基础流式导出用户列表：HTTP 响应头、动态脱敏掩码与枚举/字典转义验证
2. Fluent 多 Sheet 复杂流式导出：多工作表创建、表名识别与数据流式写出验证
3. 纵向相邻单元格合并导出：`@ExcelMerge` 区域与合并单元格数量验证
4. 防呆导入模板下载：表头批注指南与静态/动态下拉选单数据约束验证
5. 合规用户数据导入：JSR-303 语法校验通过并返回强类型 Result 验证
6. 双向业务枚举与字典 SPI 反向解析：中文字面量精确还原为强类型枚举与业务代码验证
7. 导入数据校验失败：结构化错误行数与业务提示聚合验证
8. 导入失败错题集回写：错误单元格浅粉色高亮与悬浮批注验证
9. 导入反馈端点合规场景：写回强类型成功 Result 验证
10. 无实体动态多列导出：动态表头与二维数据流式写出验证
11. 5 套企业级内置主题生成：`minimal-clean`、`business-blue`、`classic-grey`、`fresh-green`、`warm-orange` 验证
12. 防拍照全屏暗水印与工作表密码锁定验证
13. 异步导出任务中心 Controller 端点：任务提交、进度轮询与文件落盘全流程验证
14. 万级大数据量流式读写与内存有界分批消费仿真（5000 行分批流式验证）
