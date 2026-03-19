package org.javaup.questionrewrite.controller;

import org.javaup.ai.questionrewrite.model.QuestionRewriteAnswerResponse;
import org.javaup.ai.questionrewrite.model.QuestionRewritePreviewResponse;
import org.javaup.ai.questionrewrite.model.QuestionRewriteRequest;
import org.javaup.ai.questionrewrite.service.QuestionRewriteDemoService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring AI 问题改写支持示例接口。
 */
@RestController
@RequestMapping("/rag/question-rewrite")
public class QuestionRewriteDemoController {

    private final QuestionRewriteDemoService questionRewriteDemoService;

    public QuestionRewriteDemoController(QuestionRewriteDemoService questionRewriteDemoService) {
        this.questionRewriteDemoService = questionRewriteDemoService;
    }

    /**
     * 只做问题改写和检索预览，方便观察“原问题 -> 改写后问题 -> 命中文档”。
     */
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionRewritePreviewResponse preview(@RequestBody QuestionRewriteRequest request) {
        return questionRewriteDemoService.preview(request);
    }

    /**
     * 执行完整链路：问题改写 + 检索增强 + 回答生成。
     */
    @PostMapping(value = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public QuestionRewriteAnswerResponse ask(@RequestBody QuestionRewriteRequest request) {
        return questionRewriteDemoService.ask(request);
    }
}
