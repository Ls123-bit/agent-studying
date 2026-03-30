package org.javaup.ai.manage;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.javaup.ai.manage.dto.TestDto;
import org.javaup.ai.manage.vo.TestVo;
import org.javaup.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {
    
    @Operation(summary  = "查看缓存中的订单")
    @PostMapping(value = "/get/cache")
    public ApiResponse<TestVo> getCache(@Valid @RequestBody TestDto testDto) {
        return ApiResponse.ok(new TestVo(testDto.getId()));
    }
}
