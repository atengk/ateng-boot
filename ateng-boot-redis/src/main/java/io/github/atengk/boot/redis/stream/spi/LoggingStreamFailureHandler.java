package io.github.atengk.boot.redis.stream.spi;

import io.github.atengk.boot.redis.stream.model.StreamDeadLetterMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认 Stream 消费失败日志打印处理器
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class LoggingStreamFailureHandler implements StreamFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingStreamFailureHandler.class);

    @Override
    public void onFailure(StreamDeadLetterMessage deadLetter) {
        log.error("[RedisStream] 消息消费重试耗尽并转入死信流: stream={}, group={}, recordId={}, retries={}, error={}",
                deadLetter.getStream(), deadLetter.getGroup(), deadLetter.getRecordId(),
                deadLetter.getRetryCount(), deadLetter.getExceptionMessage());
    }
}
