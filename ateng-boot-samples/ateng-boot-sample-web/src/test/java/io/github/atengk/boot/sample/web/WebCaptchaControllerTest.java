package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.web.dto.CaptchaLoginDTO;
import io.github.atengk.boot.sample.web.dto.CaptchaVerifyDTO;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图形验证码生成与受管校验端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("图形验证码生成与受管校验端到端测试")
class WebCaptchaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CaptchaStore captchaStore;

    @Autowired
    private CaptchaProperties captchaProperties;

    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. GET /sample/web/captcha/image 生成默认 SVG 矢量图形验证码（无明文 text 标签）")
    void shouldGenerateDefaultSvgCaptcha() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/web/captcha/image"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.captchaKey").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64", startsWith("data:image/svg+xml;base64,")))
                .andExpect(jsonPath("$.data.expireSeconds").value(120))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String key = root.path("data").path("captchaKey").asText();
        String dataUrl = root.path("data").path("imageBase64").asText();
        String svg = new String(
                Base64.getDecoder().decode(dataUrl.substring("data:image/svg+xml;base64,".length())),
                StandardCharsets.UTF_8
        );
        assertThat(svg).contains("<svg").contains("<path").doesNotContain("<text");

        // 错误提交后即销毁，二次提交必须提示已失效
        CaptchaLoginDTO wrongDto = new CaptchaLoginDTO();
        wrongDto.setUsername("ateng");
        wrongDto.setPassword("YOUR_PASSWORD");
        wrongDto.setCaptchaKey(key);
        wrongDto.setCaptchaCode("WRONG");

        mockMvc.perform(post("/sample/web/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("图形验证码错误，请刷新重试"));

        mockMvc.perform(post("/sample/web/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("图形验证码已失效，请刷新重试"));
    }

    @Test
    @DisplayName("2. GET /sample/web/captcha/math 生成算术图形验证码")
    void shouldGenerateMathCaptcha() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/web/captcha/math"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.captchaKey").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64", startsWith("data:image/svg+xml;base64,")))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        String key = root.path("data").path("captchaKey").asText();
        mockMvc.perform(delete("/sample/web/captcha/{captchaKey}", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("3. POST /sample/web/captcha/login 正确核验成功登录并立即消费防重放")
    void shouldLoginSuccessAndPreventReplay() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        captchaStore.save(captchaProperties.getKeyPrefix() + key, "K7M2", Duration.ofMinutes(2));

        CaptchaLoginDTO dto = new CaptchaLoginDTO();
        dto.setUsername("ateng_admin");
        dto.setPassword("YOUR_PASSWORD");
        dto.setCaptchaKey(key);
        dto.setCaptchaCode("k7m2");

        mockMvc.perform(post("/sample/web/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("ateng_admin"))
                .andExpect(jsonPath("$.data.accessToken", startsWith("demo_tok_")));

        // 重放同一请求必须被拦截
        mockMvc.perform(post("/sample/web/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("图形验证码已失效，请刷新重试"));
    }

    @Test
    @DisplayName("4. POST /sample/web/captcha/verify 与 DELETE 主动失效契约验证")
    void shouldVerifyBooleanAndInvalidateExplicitly() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        captchaStore.save(captchaProperties.getKeyPrefix() + key, "12", Duration.ofMinutes(2));

        CaptchaVerifyDTO verifyDto = new CaptchaVerifyDTO();
        verifyDto.setCaptchaKey(key);
        verifyDto.setCaptchaCode("12");

        mockMvc.perform(post("/sample/web/captcha/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 二次验证返回 false
        mockMvc.perform(post("/sample/web/captcha/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));

        // 主动失效测试
        String key2 = UUID.randomUUID().toString().replace("-", "");
        captchaStore.save(captchaProperties.getKeyPrefix() + key2, "99", Duration.ofMinutes(2));
        mockMvc.perform(delete("/sample/web/captcha/{captchaKey}", key2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verifyDto.setCaptchaKey(key2);
        verifyDto.setCaptchaCode("99");
        mockMvc.perform(post("/sample/web/captcha/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    @DisplayName("5. JSR-303 参数校验拦截空验证码提交")
    void shouldRejectBlankFieldsByJsr303() throws Exception {
        CaptchaLoginDTO badDto = new CaptchaLoginDTO();
        badDto.setUsername("ateng");
        badDto.setPassword("YOUR_PASSWORD");
        badDto.setCaptchaKey("");
        badDto.setCaptchaCode("");

        mockMvc.perform(post("/sample/web/captcha/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
