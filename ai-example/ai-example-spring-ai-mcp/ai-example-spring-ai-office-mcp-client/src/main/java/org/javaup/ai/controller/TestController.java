package org.javaup.ai.controller;

import org.javaup.ai.service.DirectToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
public class TestController {
    
    private DirectToolService directToolService;

    public TestController(DirectToolService directToolService) {
        this.directToolService = directToolService;
    }

    @GetMapping("/chat")
    public String chat() {
        return directToolService.checkAttendance("0001","04:10");
    }

}
