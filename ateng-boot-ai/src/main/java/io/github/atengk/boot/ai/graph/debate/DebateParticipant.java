package io.github.atengk.boot.ai.graph.debate;

import io.github.atengk.boot.ai.graph.BaseGraphState;

import java.util.Objects;

/**
 * 对抗式多智能体辩手契约模型
 * <p>
 * 封装辩手的身份标识、立场倾向、职责说明与论辩业务逻辑。
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-06
 */
public class DebateParticipant<S extends BaseGraphState> {

    private final String name;
    private final String stance;
    private final String description;
    private final DebateHandler<S> handler;

    public DebateParticipant(String name, String stance, String description, DebateHandler<S> handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DebateParticipant name must not be null or blank");
        }
        this.name = name.trim();
        this.stance = stance != null ? stance.trim() : "NEUTRAL";
        this.description = description != null ? description.trim() : "";
        this.handler = Objects.requireNonNull(handler, "DebateParticipant handler must not be null");
    }

    public static <S extends BaseGraphState> DebateParticipant<S> of(String name, String stance, DebateHandler<S> handler) {
        return new DebateParticipant<>(name, stance, "", handler);
    }

    public static <S extends BaseGraphState> DebateParticipant<S> of(String name, String stance, String description, DebateHandler<S> handler) {
        return new DebateParticipant<>(name, stance, description, handler);
    }

    public static <S extends BaseGraphState> Builder<S> builder() {
        return new Builder<>();
    }

    public String getName() {
        return name;
    }

    public String getStance() {
        return stance;
    }

    public String getDescription() {
        return description;
    }

    public DebateHandler<S> getHandler() {
        return handler;
    }

    @Override
    public String toString() {
        return "DebateParticipant{" +
                "name='" + name + '\'' +
                ", stance='" + stance + '\'' +
                ", description='" + description + '\'' +
                '}';
    }

    public static class Builder<S extends BaseGraphState> {
        private String name;
        private String stance = "NEUTRAL";
        private String description = "";
        private DebateHandler<S> handler;

        public Builder<S> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<S> stance(String stance) {
            this.stance = stance;
            return this;
        }

        public Builder<S> description(String description) {
            this.description = description;
            return this;
        }

        public Builder<S> handler(DebateHandler<S> handler) {
            this.handler = handler;
            return this;
        }

        public DebateParticipant<S> build() {
            return new DebateParticipant<>(name, stance, description, handler);
        }
    }
}
