# ateng-boot-sample-document

`ateng-boot-sample-document` 是 `ateng-boot-document` 模块的 1:1 独立场景自验工程，覆盖电子劳动合同动态生成与盖章、财务对账单平铺防泄密暗水印在线预览/受控下载、供应商准入 AcroForm 表单填报与矢量扁平化防篡改四大典型场景。

---

## 1. 核心场景全景

| 场景分类 | 业务痛点 | 核心技术 / 门面 | 演示端点 | 预期验证行为 |
| :--- | :--- | :--- | :--- | :--- |
| **场景一：电子劳动合同** | 劳动合同变量填充、工作经历表格循环追加、浮动公章签名与保密协议附件拼装 | `WordTemplate`、`WordWebUtil` | `GET /document/contract/download` | 流式下载带格式合规 DOCX，内含动态表格、浮动公章及拼装附件 |
| **场景二：财务对账单在线预览** | 财务报表外泄溯源，需要动态叠加登录人工号、脱敏手机号及操作时间倾斜暗水印 | `PdfTemplate`、`PdfWebUtil` | `GET /document/statement/preview` | 浏览器内联（`inline`）预览 PDF，半透明平铺暗水印倾斜 45° 覆盖无死角 |
| **场景三：财务对账单权限锁** | 敏感对账单对外归档，需防止未授权人员打印、复制文本或篡改数据 | `PdfTemplate`、`SecurityOptions` | `GET /document/statement/download` | 流式下载 256 位 AES 加密 PDF，打开需输入密码，禁用复制和修改 |
| **场景四：供应商准入登记** | 供应商在线填报交互表单，提交后需固化为静态矢量内容彻底防篡改 | `PdfTemplate`、`FormOptions` | `GET /document/supplier-form/download` | 流式下载已填报 PDF，表单控件彻底销毁扁平化（Flattened），不可二次编辑 |

---

## 2. 极速启动与验证

### 本地启动
```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-document
```

### 端到端自动化自验
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-document -am -T 1C -nsu -B
```
