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

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 控制层
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/test")
public class TestController {
    
    @Operation(summary  = "查看缓存中的订单")
    @PostMapping(value = "/get/cache")
    public ApiResponse<TestVo> getCache(@Valid @RequestBody TestDto testDto) {
        return ApiResponse.ok(new TestVo(testDto.getId()));
    }
}
