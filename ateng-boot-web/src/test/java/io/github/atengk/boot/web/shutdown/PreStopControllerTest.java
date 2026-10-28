package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PreStopController Spring MVC 控制器单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("PreStopController 单元测试")
class PreStopControllerTest {

    @Test
    @DisplayName("测试 GET 与 POST 请求 /pre-stop 接口")
    void testPreStopMvcRequests() throws Exception {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(10));

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        PreStopController controller = new PreStopController(handler);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 1. 发起 GET 请求，触发排空
        mockMvc.perform(get("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ReadinessState.REFUSING_TRAFFIC.name()))
                .andExpect(jsonPath("$.data.executed").value(true));

        // 2. 发起 POST 请求，触发幂等保护
        mockMvc.perform(post("/pre-stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ReadinessState.REFUSING_TRAFFIC.name()))
                .andExpect(jsonPath("$.data.executed").value(false));
    }
}
