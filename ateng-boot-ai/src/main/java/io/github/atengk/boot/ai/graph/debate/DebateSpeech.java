package io.github.atengk.boot.ai.graph.debate;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 辩论发言快照记录对象
 * <p>
 * 纳管辩手在指定轮次的发言立场、论述内容与时间戳，构成结构化的历史论辩链路。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public class DebateSpeech implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private int round;
    private String participant;
    private String stance;
    private String content;
    private String timestamp;

    public DebateSpeech() {
    }

    public DebateSpeech(int round, String participant, String stance, String content) {
        this(round, participant, stance, content, LocalDateTime.now().format(FORMATTER));
    }

    public DebateSpeech(int round, String participant, String stance, String content, String timestamp) {
        this.round = round;
        this.participant = Objects.requireNonNull(participant, "participant must not be null");
        this.stance = stance != null ? stance : "NEUTRAL";
        this.content = content != null ? content : "";
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now().format(FORMATTER);
    }

    public static DebateSpeech of(int round, String participant, String stance, String content) {
        return new DebateSpeech(round, participant, stance, content);
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public String getParticipant() {
        return participant;
    }

    public void setParticipant(String participant) {
        this.participant = participant;
    }

    public String getStance() {
        return stance;
    }

    public void setStance(String stance) {
        this.stance = stance;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "DebateSpeech{" +
                "round=" + round +
                ", participant='" + participant + '\'' +
                ", stance='" + stance + '\'' +
                ", content='" + content + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
