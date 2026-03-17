package org.javaup.ai.controller;

import org.javaup.ai.service.AssistantService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;
    
    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }
    
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String response = assistantService.chat(request.message());
        return new ChatResponse(response);
    }
    
    public record ChatRequest(String message) {}
    public record ChatResponse(String reply) {}
}
