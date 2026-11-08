# ateng-boot-sample-excel 流式电子表格场景体验工程

> 专注于演示与自验 `ateng-boot-excel` 核心特性在真实企业级业务场景（HR 员工花名册多维导出、入职档案智能防呆导入校验与错题集反馈）下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleExcelApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-excel
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**（未激活 `local` Profile）：内置内存自洽存储与自洽配置，无需启动任何外部依赖即可启动运行与跑通全套自动化测试；
- **Local 真实模式**（激活 `local` Profile）：若需连接本地或远程物理中间件（如 Redis 异步导出任务中心），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串，启动应用时添加 `--spring.profiles.active=local` 自动生效（该文件已被 `.gitignore` 严格忽略，保证 CI 零风险与凭据安全）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能领域 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **花名册异步流式导出 (ResponseEntity)** | `EmployeeRosterController` | `GET /sample/excel/roster/export-response-entity` | `curl -i "http://localhost:8080/sample/excel/roster/export-response-entity?includeColumns=name,phone,email" -o 花名册_裁剪列.xlsx` |
| **花名册同步流式导出 (脱敏+枚举)** | `EmployeeRosterController` | `GET /sample/excel/roster/export-servlet-response` | `curl -i "http://localhost:8080/sample/excel/roster/export-servlet-response" -o 员工花名册_同步流式.xlsx` |
| **花名册企业主题渲染导出** | `EmployeeRosterController` | `GET /sample/excel/roster/export-theme` | `curl -i "http://localhost:8080/sample/excel/roster/export-theme?theme=WARM_ORANGE" -o 花名册_暖阳橙.xlsx` |
| **花名册国际化动态表头导出** | `EmployeeRosterController` | `GET /sample/excel/roster/export-i18n` | `curl -i -H "Accept-Language: en-US" "http://localhost:8080/sample/excel/roster/export-i18n" -o 员工花名册_英文表头.xlsx` |
| **部门考勤纵向单元格合并导出** | `EmployeeRosterController` | `GET /sample/excel/roster/export-merge` | `curl -i "http://localhost:8080/sample/excel/roster/export-merge" -o 部门考勤纵向合并统计表.xlsx` |
| **入职防呆导入模板下载** | `OnboardingImportController` | `GET /sample/excel/onboarding/template` | `curl -i "http://localhost:8080/sample/excel/onboarding/template" -o 入职防呆导入模板.xlsx` |
| **下载合规入职测试样本** | `OnboardingImportController` | `GET /sample/excel/onboarding/sample-valid` | `curl -i "http://localhost:8080/sample/excel/onboarding/sample-valid" -o 入职合规测试样本.xlsx` |
| **下载含错入职测试样本** | `OnboardingImportController` | `GET /sample/excel/onboarding/sample-dirty` | `curl -i "http://localhost:8080/sample/excel/onboarding/sample-dirty" -o 入职含错测试样本.xlsx` |
| **前置表头契约静态校验** | `OnboardingImportController` | `POST /sample/excel/onboarding/validate-header` | `curl -X POST "http://localhost:8080/sample/excel/onboarding/validate-header" -F "file=@入职防呆导入模板.xlsx" -F "strategy=STRICT"` |
| **强类型同步读取与行级校验** | `OnboardingImportController` | `POST /sample/excel/onboarding/import-result` | `curl -X POST "http://localhost:8080/sample/excel/onboarding/import-result" -F "file=@入职含错测试样本.xlsx"` |
| **导入差错数据独立剥离导出** | `OnboardingImportController` | `POST /sample/excel/onboarding/export-failed-rows` | `curl -X POST "http://localhost:8080/sample/excel/onboarding/export-failed-rows" -F "file=@入职含错测试样本.xlsx" -o 入职导入失败明细.xlsx` |
| **导入错误原样标红批注回写反馈** | `OnboardingImportController` | `POST /sample/excel/onboarding/export-feedback` | `curl -X POST "http://localhost:8080/sample/excel/onboarding/export-feedback" -F "file=@入职含错测试样本.xlsx" -o 入职导入错误反馈_标红批注.xlsx` |
| **海量入职数据流式分批校验消费** | `OnboardingImportController` | `POST /sample/excel/onboarding/import-batch` | `curl -X POST "http://localhost:8080/sample/excel/onboarding/import-batch?batchSize=100" -F "file=@入职合规测试样本.xlsx"` |
| **异构多 Sheet 联合报表写出** | `WorkbookReportController` | `GET /sample/excel/report/multi-sheet` | `curl -i "http://localhost:8080/sample/excel/report/multi-sheet" -o 经营综合分析多表报表.xlsx` |
| **异构多 Sheet 联合动态解析** | `WorkbookReportController` | `POST /sample/excel/report/multi-sheet-parse` | `curl -X POST "http://localhost:8080/sample/excel/report/multi-sheet-parse" -F "file=@经营综合分析多表报表.xlsx"` |
| **动态表头与键值对 Map 导出** | `WorkbookReportController` | `GET /sample/excel/report/dynamic` | `curl -i "http://localhost:8080/sample/excel/report/dynamic" -o 动态扩展属性报表.xlsx` |
| **商品图文画册图文混排导出** | `WorkbookReportController` | `GET /sample/excel/report/image-catalog` | `curl -i "http://localhost:8080/sample/excel/report/image-catalog" -o 企业商品图文画册.xlsx` |
| **空白差旅报销单模板下载** | `WorkbookReportController` | `GET /sample/excel/report/template-blank` | `curl -i "http://localhost:8080/sample/excel/report/template-blank" -o 差旅报销结算单_空白模板.xlsx` |
| **报销单占位符与列表循环填充** | `WorkbookReportController` | `GET /sample/excel/report/fill` | `curl -i "http://localhost:8080/sample/excel/report/fill" -o 差旅报销结算单_已填充.xlsx` |
| **脱敏与公式防注入对照导出** | `PayrollSecurityController` | `GET /sample/excel/security/export-contrast` | `curl -i "http://localhost:8080/sample/excel/security/export-contrast" -o 薪酬敏感信息与公式防护对照表.xlsx` |
| **-30° 暗水印与工作表只读保护导出** | `PayrollSecurityController` | `GET /sample/excel/security/export-watermark` | `curl -i "http://localhost:8080/sample/excel/security/export-watermark" -o 薪酬机密_防拍照暗水印锁定.xlsx` |
| **整工作簿 AES-256 强加密导出** | `PayrollSecurityController` | `GET /sample/excel/security/export-encrypt` | `curl -i "http://localhost:8080/sample/excel/security/export-encrypt?password=PayrollSecret%402026" -o 薪酬机密_AES256加密.xlsx` |
| **下载加密测试样本工作簿** | `PayrollSecurityController` | `GET /sample/excel/security/sample-encrypted` | `curl -i "http://localhost:8080/sample/excel/security/sample-encrypted" -o 薪酬机密_样本加密.xlsx` |
| **密文工作簿透明对称解密导入** | `PayrollSecurityController` | `POST /sample/excel/security/import-decrypt` | `curl -X POST "http://localhost:8080/sample/excel/security/import-decrypt?password=PayrollSecret%402026" -F "file=@薪酬机密_样本加密.xlsx"` |
| **PageResult 流式跨表轮转导出** | `OrderStreamingController` | `GET /sample/excel/streaming/export-page` | `curl -i "http://localhost:8080/sample/excel/streaming/export-page?totalRows=250&pageSize=100&maxRowsPerSheet=100" -o 订单流水_分页跨表分片导出.xlsx` |
| **主键游标流式分页跨表导出** | `OrderStreamingController` | `GET /sample/excel/streaming/export-cursor` | `curl -i "http://localhost:8080/sample/excel/streaming/export-cursor?totalRows=250&pageSize=100&maxRowsPerSheet=100" -o 订单流水_游标分片导出.xlsx` |
| **流式分页模板显式写控制导出** | `OrderStreamingController` | `GET /sample/excel/streaming/export-template` | `curl -i "http://localhost:8080/sample/excel/streaming/export-template" -o 订单流水_模板显式写.xlsx` |
| **下载海量交易订单测试样本** | `OrderStreamingController` | `GET /sample/excel/streaming/sample-order-bill` | `curl -i "http://localhost:8080/sample/excel/streaming/sample-order-bill?count=300" -o 海量订单交易账单样本.xlsx` |
| **大文件常量内存分批流式解析消费** | `OrderStreamingController` | `POST /sample/excel/streaming/import-batch` | `curl -X POST "http://localhost:8080/sample/excel/streaming/import-batch?batchSize=100" -F "file=@海量订单交易账单样本.xlsx"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-excel,ateng-boot-samples/ateng-boot-sample-excel -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-excel README](../../ateng-boot-excel/README.md)。
