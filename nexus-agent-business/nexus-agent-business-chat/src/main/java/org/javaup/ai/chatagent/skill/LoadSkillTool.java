package org.javaup.ai.chatagent.skill;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.debug.ChatToolTrace;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.support.ToolTraceSupport;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/*
load_skill 工具（Skills 两阶段按需加载的执行端，Q2b 决策）。

系统提示词里只注入了技能目录（名称 + 描述）；当模型判断用户请求命中某个技能时，
就调用本工具取回该技能的完整指令（SKILL.md 正文 + 同目录参考 .md 文件），
然后严格按指令完成任务。这套渐进式披露让 prompt 成本不随技能数量线性膨胀。

与 tavily_search 一样，本工具也走 ReactAgent 的通用工具链路：
- 调用会被 ChatDebugTrace.toolTraces / USED_TOOLS / thinking 事件记录并随会话归档；
- 受 ToolCallLimitHook 的限额约束（ChatAgentConfiguration 会为每个注册的工具建一条限额）；
- 异常由 ToolErrorInterceptor 兜底转成错误消息返回给模型。
 */
@Slf4j
@Component
public class LoadSkillTool {

    public static final String TOOL_NAME = "load_skill";

    private final SkillRegistry skillRegistry;
    private final SkillProperties properties;
    private final StreamEventWriter streamEventWriter;

    public LoadSkillTool(SkillRegistry skillRegistry, SkillProperties properties, StreamEventWriter streamEventWriter) {
        this.skillRegistry = skillRegistry;
        this.properties = properties;
        this.streamEventWriter = streamEventWriter;
    }

    /*
    加载技能完整内容。
    参数校验失败直接抛异常（与 tavily_search 的风格一致），
    拦截器链会把异常转成错误消息回给模型，让模型自行纠正参数。
     */
    public String loadSkill(LoadSkillRequest request, ToolContext toolContext) {
        String skillName = request != null && StrUtil.isNotBlank(request.getSkillName())
            ? request.getSkillName().trim()
            : "";
        if (StrUtil.isBlank(skillName)) {
            throw new IllegalArgumentException("skillName 不能为空，请从系统提示词的可用技能目录中选择");
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("技能体系当前已禁用");
        }
        SkillDefinition skill = skillRegistry.find(skillName);
        if (skill == null) {
            throw new IllegalArgumentException("技能 " + skillName + " 不存在，请从系统提示词列出的可用技能目录中选择");
        }

        long startTime = System.currentTimeMillis();
        ChatToolTrace toolTrace = ToolTraceSupport.registerToolTrace(toolContext, ChatToolTrace.builder()
            .toolName(TOOL_NAME)
            .status("RUNNING")
            .inputSummary(skill.name())
            .build());
        ToolTraceSupport.markToolUsed(toolContext, TOOL_NAME);
        ToolTraceSupport.publishThinking(toolContext, streamEventWriter, "🧩 正在加载技能: " + skill.name());

        try {
            String content = renderSkillContent(skill);
            ToolTraceSupport.completeToolTrace(toolTrace, startTime, "技能指令已返回，共 " + content.length() + " 字符");
            ToolTraceSupport.publishThinking(toolContext, streamEventWriter,
                "🧩 技能加载完成: " + skill.name() + "（" + content.length() + " 字符）");
            return content;
        } catch (RuntimeException exception) {
            ToolTraceSupport.failToolTrace(toolTrace, exception, startTime);
            ToolTraceSupport.publishThinking(toolContext, streamEventWriter, "⚠️ 技能加载失败: " + skill.name());
            log.warn("load_skill 加载技能失败, skill={}", skillName, exception);
            throw exception;
        }
    }

    /*
    拼装返回内容：技能正文 + 参考文件；
    超过 app.skills.max-load-chars 时截断，防止超长技能把模型上下文吃满（Q2b 默认值③）。
     */
    private String renderSkillContent(SkillDefinition skill) {
        StringBuilder content = new StringBuilder();
        content.append("技能名称：").append(skill.name()).append('\n');
        if (StrUtil.isNotBlank(skill.description())) {
            content.append("技能简介：").append(skill.description()).append('\n');
        }
        content.append("技能指令：\n").append(StrUtil.blankToDefault(skill.instruction(), "").trim()).append('\n');

        for (Map.Entry<String, String> reference : skill.referenceFiles().entrySet()) {
            content.append("\n参考资料 [").append(reference.getKey()).append("]：\n")
                .append(StrUtil.blankToDefault(reference.getValue(), "").trim())
                .append('\n');
        }

        String rendered = content.toString();
        int maxLoadChars = properties.getMaxLoadChars();
        if (maxLoadChars > 0 && rendered.length() > maxLoadChars) {
            rendered = rendered.substring(0, maxLoadChars) + "\n...(技能内容超过 max-load-chars 上限，已截断)";
        }
        return rendered;
    }
}
