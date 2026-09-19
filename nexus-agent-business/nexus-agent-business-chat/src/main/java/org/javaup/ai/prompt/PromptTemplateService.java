package org.javaup.ai.prompt;

import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/*
Prompt模板管理
所有发给大模型的提示词都通过模板引擎统一管理，不会散落在业务代码里。这样做的好处是：调整提示词不用改 Java 代码，直接改模板文件就行。
实现细节：
    模板引擎：Spring AI 的 StTemplateRenderer（基于 StringTemplate4）
    分隔符：< / >（不是默认的 $）
    模板位置：classpath:prompt/*.st
    缓存：ConcurrentHashMap，首次加载后缓存
 */
@Component
public class PromptTemplateService {

    private static final String PROMPT_DIR = "prompt/";
    private static final String TEMPLATE_SUFFIX = ".st";

    private final ResourceLoader resourceLoader;
    private final StTemplateRenderer templateRenderer;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.templateRenderer = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .validationMode(ValidationMode.THROW)
            .build();
    }



    //加载模板并渲染，变量用 Map 传入
    public String render(String templateName, Map<String, ?> variables) {
        String templatePath = normalizeTemplatePath(templateName);
        String template = templateCache.computeIfAbsent(templatePath, this::loadTemplate);
        return templateRenderer.apply(template, normalizeVariables(variables)).trim();
    }

    
    //关键私有方法 标准化变量 Map
    private Map<String, Object> normalizeVariables(Map<String, ?> variables) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) {
            return normalized;
        }
        variables.forEach((key, value) -> normalized.put(key, value == null ? "" : value));
        return normalized;
    }


    //关键私有方法 拼接模板路径
    private String normalizeTemplatePath(String templateName) {
        String normalized = templateName == null ? "" : templateName.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith(PROMPT_DIR)) {
            normalized = PROMPT_DIR + normalized;
        }
        if (!normalized.endsWith(TEMPLATE_SUFFIX)) {
            normalized = normalized + TEMPLATE_SUFFIX;
        }
        return normalized;
    }


    //关键私有方法 从 classpath 加载模板内容
    private String loadTemplate(String templatePath) {
        Resource resource = resourceLoader.getResource("classpath:" + templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Prompt 模板不存在: classpath:" + templatePath);
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
        catch (Exception exception) {
            throw new IllegalStateException("读取 Prompt 模板失败: classpath:" + templatePath, exception);
        }
    }
}
