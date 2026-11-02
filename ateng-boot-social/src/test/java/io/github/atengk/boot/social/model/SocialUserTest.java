package io.github.atengk.boot.social.model;

import org.dromara.anduin.enums.AuthUserGender;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SocialUser 领域模型与微信跨端身份双维转换单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class SocialUserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("验证由 Anduin AuthUser 转换：Token 携带 openId 与 unionId 优先提取")
    void testFromAuthUser_WithTokenOpenIdAndUnionId() {
        AuthToken token = AuthToken.builder()
                .accessToken("mock-access-token")
                .openId("wx_open_12345")
                .unionId("wx_union_99999")
                .build();

        AuthUser authUser = AuthUser.builder()
                .uuid("uuid-fallback")
                .source("WECHAT_OPEN")
                .username("atengUser")
                .nickname("阿腾")
                .avatar("https://example.com/avatar.png")
                .email("ateng@example.com")
                .gender(AuthUserGender.MALE)
                .token(token)
                .build();

        SocialUser socialUser = SocialUser.fromAuthUser(authUser);

        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getOpenId()).isEqualTo("wx_open_12345");
        assertThat(socialUser.getUnionId()).isEqualTo("wx_union_99999");
        assertThat(socialUser.getSource()).isEqualTo("WECHAT_OPEN");
        assertThat(socialUser.getUsername()).isEqualTo("atengUser");
        assertThat(socialUser.getNickname()).isEqualTo("阿腾");
        assertThat(socialUser.getGender()).isEqualTo("MALE");
        assertThat(socialUser.getRawToken()).isSameAs(token);
    }

    @Test
    @DisplayName("验证由 Anduin AuthUser 转换：探测 rawUserInfo JsonNode 中的 openid 与 unionid")
    void testFromAuthUser_WithRawUserInfoFallback() {
        ObjectNode rawJson = objectMapper.createObjectNode();
        rawJson.put("openid", "raw_open_888");
        rawJson.put("unionid", "raw_union_777");
        rawJson.put("city", "Chengdu");

        AuthUser authUser = AuthUser.builder()
                .uuid("uuid-111")
                .source("WECHAT_MP")
                .nickname("公众号粉丝")
                .rawUserInfo(rawJson)
                .build();

        SocialUser socialUser = SocialUser.fromAuthUser(authUser);

        assertThat(socialUser).isNotNull();
        assertThat(socialUser.getOpenId()).isEqualTo("raw_open_888");
        assertThat(socialUser.getUnionId()).isEqualTo("raw_union_777");
        assertThat(socialUser.getRawUserInfo()).containsEntry("city", "Chengdu");
    }

    @Test
    @DisplayName("验证边界防御：空对象与字段回退保障不崩")
    void testFromAuthUser_NullAndFallback() {
        assertThat(SocialUser.fromAuthUser(null)).isNull();

        AuthUser emptyUser = AuthUser.builder().uuid("only-uuid").build();
        SocialUser result = SocialUser.fromAuthUser(emptyUser);

        assertThat(result).isNotNull();
        assertThat(result.getOpenId()).isEqualTo("only-uuid");
        assertThat(result.getUnionId()).isNull();
        assertThat(result.getRawUserInfo()).isNotNull().isEmpty();
    }
}
