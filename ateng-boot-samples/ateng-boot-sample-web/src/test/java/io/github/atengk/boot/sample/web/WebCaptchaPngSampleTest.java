package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PNG 模式图形验证码配置切换端到端测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@SpringBootTest(properties = {
        "ateng.boot.web.captcha.renderer=PNG",
        "ateng.boot.web.captcha.type=MATH"
})
@AutoConfigureMockMvc
@DisplayName("PNG 模式图形验证码切换端到端测试")
class WebCaptchaPngSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("切换 renderer=PNG 与 type=MATH 后输出可解码 PNG 位图 Data URL")
    void shouldGenerateDecodablePngCaptcha() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/web/captcha/image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.imageBase64", startsWith("data:image/png;base64,")))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String key = root.path("data").path("captchaKey").asText();
        String dataUrl = root.path("data").path("imageBase64").asText();
        byte[] pngBytes = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));

        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(130);
        assertThat(image.getHeight()).isEqualTo(44);

        mockMvc.perform(delete("/sample/web/captcha/{captchaKey}", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
