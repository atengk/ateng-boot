package io.github.atengk.boot.mqtt.credentials;

import java.util.Arrays;

/**
 * MQTT 客户端动态认证凭据快照
 * <p>
 * 承载由 {@link MqttCredentialsProvider} 动态解析的用户名、密码（或 JWT / STS 短期令牌）
 * 以及 MQTT 5.0 增强认证（Enhanced Authentication）字段。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
public class MqttCredentials {

    private final String username;
    private final String password;
    private final String authMethod;
    private final byte[] authData;

    private MqttCredentials(Builder builder) {
        this.username = builder.username;
        this.password = builder.password;
        this.authMethod = builder.authMethod;
        this.authData = builder.authData != null ? Arrays.copyOf(builder.authData, builder.authData.length) : null;
    }

    /**
     * 快速构造基于用户名与密码/令牌的认证凭据
     *
     * @param username 用户名（可为 null）
     * @param password 密码或短期令牌（可为 null）
     * @return 不可变认证凭据实例
     */
    public static MqttCredentials of(String username, String password) {
        return builder().username(username).password(password).build();
    }

    /**
     * 创建认证凭据构建器
     *
     * @return 构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public byte[] getAuthData() {
        return authData != null ? Arrays.copyOf(authData, authData.length) : null;
    }

    /**
     * {@link MqttCredentials} 构建器
     */
    public static final class Builder {

        private String username;
        private String password;
        private String authMethod;
        private byte[] authData;

        private Builder() {}

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder authMethod(String authMethod) {
            this.authMethod = authMethod;
            return this;
        }

        public Builder authData(byte[] authData) {
            this.authData = authData != null ? Arrays.copyOf(authData, authData.length) : null;
            return this;
        }

        public MqttCredentials build() {
            return new MqttCredentials(this);
        }
    }
}
