package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.WebEnumUserRequest;
import io.github.atengk.boot.sample.web.dto.WebEnumUserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示枚举入界校验与强类型绑定控制器
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/api/sample/enum")
public class WebEnumSampleController {

    @PostMapping("/user")
    public Result<WebEnumUserResponse> createUser(@Valid @RequestBody WebEnumUserRequest request) {
        WebEnumUserResponse response = new WebEnumUserResponse(
                request.getUsername(),
                request.getStatusCode(),
                request.getStatus(),
                request.getOrderType()
        );
        return Result.success(response);
    }
}
