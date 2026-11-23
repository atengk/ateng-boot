package io.github.atengk.boot.sample.flyway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.sample.flyway.dto.TargetOperationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受保护生产 Profile 下 ProductionSafetyGuard 强熔断端到端验证
 *
 * @author Ateng
 * @since 2026-11-17
 */
@ActiveProfiles("prod")
@SpringBootTest(properties = {
        "spring.flyway.clean-disabled=false",
        "spring.datasource.dynamic.datasource.master.url=jdbc:h2:mem:flyway_prod_master;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.dynamic.datasource.slave.url=jdbc:h2:mem:flyway_prod_slave;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.dynamic.datasource.log_db.url=jdbc:h2:mem:flyway_prod_log;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.dynamic.datasource.tenant_1001.url=jdbc:h2:mem:flyway_prod_t1001;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.dynamic.datasource.tenant_1002.url=jdbc:h2:mem:flyway_prod_t1002;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
@AutoConfigureMockMvc
class FlywayProtectedProfileGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("prod Profile 下即使误配 spring.flyway.clean-disabled=false，clean 端点仍返回 62003 拦截码")
    void shouldRejectCleanInProtectedProfileDespiteCleanDisabledFalse() throws Exception {
        mockMvc.perform(post("/sample/flyway/governance/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TargetOperationRequest.builder().targetKey("master").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("prod Profile 下高危 DDL 预检演练端点应拦截未授权 DROP TABLE（62010）并放行带豁免标记脚本")
    void shouldRejectDestructiveDdlAndAllowAnnotatedExemptionInProdProfile() throws Exception {
        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlScript\":\"CREATE TABLE demo_tmp(id BIGINT); DROP TABLE demo_tmp;\",\"allowExemptionAnnotation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode()));

        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlScript\":\"-- @ateng-allow-destructive-ddl\\nCREATE TABLE demo_tmp(id BIGINT); DROP TABLE demo_tmp;\",\"allowExemptionAnnotation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.passed").value(true));
    }
}
