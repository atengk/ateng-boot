package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.sample.encrypt.dto.KeyRotateRequest;
import io.github.atengk.boot.sample.encrypt.dto.RoutedDecryptRequest;
import io.github.atengk.boot.sample.encrypt.service.MultiKeyRoutingScenarioService;
import io.github.atengk.boot.sample.encrypt.vo.MultiKeyStatusVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 多 Key 别名动态路由与密钥轮换控制器
 * <p>
 * 提供多 Key 注册状态探查、携带 ENC[keyId](...) 别名路由标签的密文解密、以及运行时平滑注册与轮换密钥体验。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/encrypt/multi-key")
@RequiredArgsConstructor
public class EncryptMultiKeyController {

    private final MultiKeyRegistry multiKeyRegistry;
    private final MultiKeyRoutingScenarioService multiKeyService;

    /**
     * 1. 探查多 Key 注册表当前状态与已注册的密钥别名集合
     *
     * @return 多 Key 状态展示对象
     */
    @GetMapping("/keys")
    public Result<MultiKeyStatusVO> getKeyStatus() {
        log.info("[EncryptMultiKeyController] 探查多 Key 注册表状态");
        Set<String> keyIds = multiKeyRegistry.getKeyIds();
        boolean hasDefault = multiKeyRegistry.getDefaultKey() != null;
        int total = keyIds.size() + (hasDefault ? 1 : 0);

        MultiKeyStatusVO vo = MultiKeyStatusVO.builder()
                .defaultKeyConfigured(hasDefault)
                .registeredKeyIds(keyIds)
                .totalKeys(total)
                .build();
        return Result.success(vo);
    }

    /**
     * 2. 使用携带多 Key 路由标签的密文执行解密（如 ENC[redis-cluster](...)）
     *
     * @param request 路由解密入参
     * @return 解密还原后的真实明文
     */
    @PostMapping("/decrypt-routed")
    public Result<String> decryptRoutedToken(@Valid @RequestBody RoutedDecryptRequest request) {
        log.info("[EncryptMultiKeyController] 执行多 Key 路由密文解密: {}", request.getToken());
        String plain = multiKeyService.decryptRoutedToken(request.getToken());
        return Result.success(plain);
    }

    /**
     * 3. 运行时动态注册或平滑轮换命名密钥（自动触发解密缓存原子置换与事件通知）
     *
     * @param request 密钥轮换入参
     * @return 轮换结果（true 表示成功）
     */
    @PostMapping("/rotate")
    public Result<Boolean> rotateKey(@Valid @RequestBody KeyRotateRequest request) {
        log.info("[EncryptMultiKeyController] 动态注册/轮换命名密钥: keyId={}", request.getKeyId());
        multiKeyService.rotateKey(request.getKeyId(), request.getNewPassword());
        return Result.success(true);
    }
}
