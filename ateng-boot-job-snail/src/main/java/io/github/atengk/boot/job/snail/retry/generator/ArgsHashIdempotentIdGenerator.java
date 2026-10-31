package io.github.atengk.boot.job.snail.retry.generator;

import com.aizuda.snailjob.client.core.IdempotentIdGenerate;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.core.util.JsonUtil;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 基于参数 SHA-256 哈希摘要的 SnailJob 重试幂等键生成器
 *
 * <p>纯原生标准库实现（依托 JDK {@link MessageDigest}），对场景、执行类、方法名与参数序列化内容
 * 进行确定性 SHA-256 摘要哈希计算，具备强抗碰撞性与极高计算吞吐，彻底杜绝重试任务重复上报。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class ArgsHashIdempotentIdGenerator implements IdempotentIdGenerate {

    @Override
    public String idGenerate(IdempotentIdContext context) throws Exception {
        if (context == null) {
            return UUID.randomUUID().toString().replace("-", "");
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
        return sha256Hex(rawContent);
    }

    /**
     * 计算输入字符串的 SHA-256 64 位十六进制散列值
     *
     * @param input 输入字符串
     * @return 64 位小写十六进制散列字符串
     */
    public static String sha256Hex(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256 算法", e);
        }
    }
}
