package io.github.atengk.boot.job.snail.retry.generator;

import com.aizuda.snailjob.client.core.IdempotentIdGenerate;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import org.springframework.util.StringUtils;

/**
 * 基于参数 SHA-256 哈希摘要的 SnailJob 重试幂等键生成器
 *
 * <p>基于 Core 模块 {@link DigestUtil#sha256Hex(String)} 实现，对场景、执行类、方法名与参数序列化内容
 * 进行确定性 SHA-256 摘要哈希计算，具备强抗碰撞性与极高计算吞吐，彻底杜绝重试任务重复上报。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class ArgsHashIdempotentIdGenerator implements IdempotentIdGenerate {

    @Override
    public String idGenerate(IdempotentIdContext context) throws Exception {
        if (context == null) {
            return IdUtil.fastSimpleUUID();
        }

        String scene = context.getScene() != null ? context.getScene() : "";
        String targetClassName = context.getTargetClassName() != null ? context.getTargetClassName() : "";
        String methodName = context.getMethodName() != null ? context.getMethodName() : "";

        String argsPayload;
        if (StringUtils.hasText(context.getSerializeArgs())) {
            argsPayload = context.getSerializeArgs();
        } else if (context.getArgs() != null && context.getArgs().length > 0) {
            argsPayload = JsonUtil.toJsonString(context.getArgs());
        } else {
            argsPayload = "[]";
        }

        String rawContent = scene + "_" + targetClassName + "_" + methodName + "_" + argsPayload;
        return DigestUtil.sha256Hex(rawContent);
    }
}
