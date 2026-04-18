package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 回答阶段最终使用的历史上下文
 * @author: 阿星不是程序员
 **/
/**
 * 回答阶段最终使用的历史上下文。
 *
 * <p>这个对象和“会话记忆原材料”不是一回事。
 * 它表达的是：</p>
 * <p>1. 回答阶段真正保留下来的承接上下文。</p>
 * <p>2. 这份上下文只服务当前问题的指代理解，不承担事实证据职责。</p>
 * <p>3. 最终送进 Prompt 的合成结果。</p>
 *
 * <p>把它单独建模的目的，是让教学和代码职责都更清楚：</p>
 * <p>记忆服务负责提供原材料，编排层负责决定回答阶段该保留什么，
 * Prompt 层则只负责消费最终结果，不再自己二次裁剪。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerHistoryContext {

    /**
     * 最终送进回答 Prompt 的文本。
     */
    private String renderedText;

    /**
     * 回答阶段保留下来的结构化历史部分。
     */
    private String structuredContext;

    /**
     * 回答阶段保留下来的最近相关对话部分。
     */
    private String recentContext;

    /**
     * 当前问题是否被判定为承接式追问。
     */
    private boolean followUpQuestion;

    /**
     * 回答阶段总预算。
     */
    private Integer totalBudget;

    /**
     * 分配给最近相关对话的预算。
     */
    private Integer recentBudget;

    /**
     * 分配给结构化历史的预算。
     */
    private Integer structuredBudget;

    public boolean isEmpty() {
        return renderedText == null || renderedText.isBlank();
    }
}
