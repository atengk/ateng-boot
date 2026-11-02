package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import tools.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准化第三方社交平台用户画像领域模型
 * <p>
 * 支持微信等国内生态 openId + unionId 双维建模，满足跨端（公众号、小程序、PC 扫码）身份同一性识别，
 * 并提供原始平台授权凭证与 JSON 数据快照。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 第三方应用级用户唯一标识（如微信 openid、GitHub user id 等）
     */
    private String openId;

    /**
     * 第三方开发者平台级用户唯一标识（如微信 unionid，支持跨公众号、小程序与 PC 扫码打通同一账号）
     */
    private String unionId;

    /**
     * 社交授权平台来源（如 GITHUB, GITEE, WECHAT_OPEN, WECHAT_MP, QQ 等）
     */
    private String source;

    /**
     * 原始系统唯一标识（对应 Anduin AuthUser uuid）
     */
    private String uuid;

    /**
     * 第三方平台用户名 / 账号
     */
    private String username;

    /**
     * 第三方平台用户昵称
     */
    private String nickname;

    /**
     * 用户头像地址
     */
    private String avatar;

    /**
     * 用户主页 / 博客地址
     */
    private String blog;

    /**
     * 所在公司 / 组织
     */
    private String company;

    /**
     * 所在地理位置
     */
    private String location;

    /**
     * 用户邮箱
     */
    private String email;

    /**
     * 用户个人简介 / 备注
     */
    private String remark;

    /**
     * 性别描述（如 MALE, FEMALE, UNKNOWN）
     */
    private String gender;

    /**
     * 原始第三方授权 Token 凭证快照（可强转为 AuthToken 获取 accessToken、refreshToken 等）
     */
    private Object rawToken;

    /**
     * 第三方平台返回的原始完整响应 JSON 字典快照
     */
    @Builder.Default
    private Map<String, Object> rawUserInfo = Collections.emptyMap();

    /**
     * 从 Dromara Anduin 原生 AuthUser 转换为标准化 SocialUser 领域模型
     *
     * @param authUser Anduin 授权用户对象
     * @return 标准化 SocialUser
     */
    public static SocialUser fromAuthUser(AuthUser authUser) {
        if (authUser == null) {
            return null;
        }

        AuthToken token = authUser.getToken();
        String openId = null;
        String unionId = null;

        Map<String, Object> rawMap = convertJsonNodeToMap(authUser.getRawUserInfo());

        // 1. 优先从 token 中获取 openId 与 unionId
        if (token != null) {
            openId = token.getOpenId();
            unionId = token.getUnionId();
        }

        // 2. 探测 rawUserInfo 响应中的 openid / open_id
        if (openId == null || openId.isBlank()) {
            if (rawMap.get("openid") instanceof String o && !o.isBlank()) {
                openId = o;
            } else if (rawMap.get("open_id") instanceof String o && !o.isBlank()) {
                openId = o;
            }
        }

        // 3. 最后回退至 authUser.getUuid() 保底
        if (openId == null || openId.isBlank()) {
            openId = authUser.getUuid();
        }

        // 4. 探测 rawUserInfo 响应中的 unionid / union_id
        if (unionId == null || unionId.isBlank()) {
            if (rawMap.get("unionid") instanceof String u && !u.isBlank()) {
                unionId = u;
            } else if (rawMap.get("union_id") instanceof String u && !u.isBlank()) {
                unionId = u;
            }
        }

        String genderDesc = authUser.getGender() != null ? authUser.getGender().name() : null;

        return SocialUser.builder()
                .openId(openId)
                .unionId(unionId)
                .source(authUser.getSource())
                .uuid(authUser.getUuid())
                .username(authUser.getUsername())
                .nickname(authUser.getNickname())
                .avatar(authUser.getAvatar())
                .blog(authUser.getBlog())
                .company(authUser.getCompany())
                .location(authUser.getLocation())
                .email(authUser.getEmail())
                .remark(authUser.getRemark())
                .gender(genderDesc)
                .rawToken(token)
                .rawUserInfo(rawMap)
                .build();
    }

    /**
     * 将 Jackson 3 JsonNode 递归转换为原生 Map 结构，保障纯净无跨版本类依赖
     */
    private static Map<String, Object> convertJsonNodeToMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), convertNodeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Object convertNodeValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        if (valueNode.isBoolean()) {
            return valueNode.asBoolean();
        }
        if (valueNode.isInt()) {
            return valueNode.asInt();
        }
        if (valueNode.isLong()) {
            return valueNode.asLong();
        }
        if (valueNode.isDouble()) {
            return valueNode.asDouble();
        }
        if (valueNode.isObject()) {
            return convertJsonNodeToMap(valueNode);
        }
        return valueNode.toString();
    }
}
