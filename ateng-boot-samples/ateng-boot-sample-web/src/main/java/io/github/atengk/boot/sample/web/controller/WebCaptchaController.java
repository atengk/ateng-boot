package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.web.dto.CaptchaLoginDTO;
import io.github.atengk.boot.sample.web.dto.CaptchaVerifyDTO;
import io.github.atengk.boot.sample.web.vo.CaptchaLoginVO;
import io.github.atengk.boot.web.captcha.enums.CaptchaType;
import io.github.atengk.boot.web.captcha.model.CaptchaResult;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 纯 JDK 原生图形验证码生成与受管校验场景控制器
 * 演示与前端 {@code <AtengCaptcha />} 对接、算术验证码、登录防重放核验及主动销毁
 *
 * @author Ateng
 * @since 2026-11-17
 */
@RestController
@RequestMapping("/sample/web/captcha")
public class WebCaptchaController {

    private final CaptchaTemplate captchaTemplate;

    public WebCaptchaController(CaptchaTemplate captchaTemplate) {
        this.captchaTemplate = captchaTemplate;
    }

    /**
     * 获取图形验证码（默认按配置生成字符验证码，支持通过 type 参数切换 CHAR/MATH）
     *
     * @param type 可选验证码类型
     * @return Base64 Data URL 图形验证码结果
     */
    @GetMapping("/image")
    public Result<CaptchaResult> generateImage(@RequestParam(value = "type", required = false) CaptchaType type) {
        CaptchaResult result = type == null ? captchaTemplate.generate() : captchaTemplate.generate(type);
        return Result.success(result);
    }

    /**
     * 获取简单算术图形验证码
     *
     * @return 算术图形验证码结果
     */
    @GetMapping("/math")
    public Result<CaptchaResult> generateMath() {
        return Result.success(captchaTemplate.generate(CaptchaType.MATH));
    }

    /**
     * 账号登录并强制校验图形验证码（核验失败直接抛出业务异常并原子销毁 Key）
     *
     * @param dto 登录与验证码入参
     * @return 登录结果视图
     */
    @PostMapping("/login")
    public Result<CaptchaLoginVO> login(@Valid @RequestBody CaptchaLoginDTO dto) {
        captchaTemplate.verifyOrThrow(dto.getCaptchaKey(), dto.getCaptchaCode());
        CaptchaLoginVO vo = CaptchaLoginVO.builder()
                .username(dto.getUsername())
                .accessToken("demo_tok_" + IdUtil.fastSimpleUUID())
                .loginTime(LocalDateTime.now())
                .build();
        return Result.success(vo);
    }

    /**
     * 编程式布尔核验图形验证码（严格一次一密，无论成败即删）
     *
     * @param dto 核验请求
     * @return 校验是否通过
     */
    @PostMapping("/verify")
    public Result<Boolean> verify(@Valid @RequestBody CaptchaVerifyDTO dto) {
        return Result.success(captchaTemplate.verify(dto.getCaptchaKey(), dto.getCaptchaCode()));
    }

    /**
     * 主动失效指定图形验证码
     *
     * @param captchaKey 验证码唯一键
     * @return 空响应成功结果
     */
    @DeleteMapping("/{captchaKey}")
    public Result<Void> invalidate(@PathVariable("captchaKey") String captchaKey) {
        captchaTemplate.invalidate(captchaKey);
        return Result.success();
    }
}
