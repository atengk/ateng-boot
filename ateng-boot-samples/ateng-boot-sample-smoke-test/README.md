# ateng-boot-sample-smoke-test 全家桶冒烟准出门禁工程

> 专职 CI 准出门禁与全家桶 Starter 无冲突自动装配端到端自验套件。

---

## 1. 场景痛点与功能定位

`ateng-boot` 提供了全家桶依赖 `ateng-boot-all`，为快速起步与单体应用提供一键打包。然而，各 Starter 之间的 Spring Boot 自动装配、条件装配（`@ConditionalOnClass`）、Filter 顺序、Jackson 定制以及 OpenAPI 契约是否存在隐式冲突，必须有一道强有力的门禁把关。

`ateng-boot-sample-smoke-test` 专为 CI 准出门禁设计：
- **聚合依赖 `ateng-boot-all`**：验证全套基础设施在同一个 Spring 容器中协同无冲突运行；
- **极速反馈**：精选最关键的主链路校验，测试执行耗时严格压制在 8 秒以内；
- **零外部资源依赖**：内置 H2 内存数据库与内存化配置，避免外部网络与端口波动引起用例闪烁（Flaky Tests）。

---

## 2. 最小可用代码与启动测试

### 本地启动
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-smoke-test spring-boot:run
```

### 全家桶端点与冒烟测试代码示例
```java
@RestController
@RequestMapping("/api/smoke")
public class SmokeSampleController {

    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("PONG");
    }
}

@SpringBootTest
@AutoConfigureMockMvc
class SampleSmokeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testMockMvcWorkflow() throws Exception {
        mockMvc.perform(get("/api/smoke/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("PONG"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
```

---

## 3. 冒烟测试执行

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-smoke-test -am -T 1C
```
在发版、合并或重大底座变更时，秒级完成全家桶装配冒烟准出。
