package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.web.dict.EnumDictionaryController;
import io.github.atengk.boot.web.dict.EnumDictionaryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 枚举字典内置 HTTP 端点关闭（由上游业务控制器自行接管）端到端测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest(properties = {
        "ateng.boot.web.enum.enabled=true",
        "ateng.boot.web.enum.endpoint-enabled=false"
})
@AutoConfigureMockMvc
@DisplayName("枚举字典关闭内置端点后由上游自行接管端到端测试")
class WebEnumEndpointDisabledSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private EnumDictionaryRegistry registry;

    @Autowired(required = false)
    private EnumDictionaryController builtInController;

    @Test
    @DisplayName("关闭 endpoint-enabled 后仅装配 EnumDictionaryRegistry，内置 /api/system/enums 返回 404，上游自定义控制器正常服务")
    void shouldDisableBuiltInEndpointWhileKeepingRegistryForUpstreamController() throws Exception {
        assertThat(registry).isNotNull();
        assertThat(builtInController).isNull();

        // 1. 内置 /api/system/enums 端点已物理关闭，不再暴露
        mockMvc.perform(get("/api/system/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(404)));

        // 2. 上游自定义控制器直接复用 EnumDictionaryRegistry 正常提供裁剪查询
        mockMvc.perform(get("/sample/web/contract/enums/custom-catalog")
                        .param("names", "userStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.userStatus", hasSize(3)))
                .andExpect(jsonPath("$.data.orderType").doesNotExist());
    }
}
