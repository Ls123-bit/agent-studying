package org.javaup.ai.chatagent.rag.core.guidance;

/**
 * 歧义检测决策结果。
 *
 * <p>当意图分类发现用户问题可能指向多个不同的章节且得分接近时，
 * 系统不猜测，而是生成一个澄清提示让用户选择。</p>
 */
public record GuidanceDecision(

        /**
         * 决策动作。
         */
        Action action,

        /**
         * 当 action 为 PROMPT 时，返回给用户的澄清提示文本。
         */
        String prompt
) {

    public enum Action {
        /** 无歧义，继续正常流程。 */
        NONE,
        /** 检测到歧义，需要向用户澄清。 */
        PROMPT
    }

    /**
     * 无歧义，继续正常流程。
     */
    public static GuidanceDecision none() {
        return new GuidanceDecision(Action.NONE, null);
    }

    /**
     * 检测到歧义，返回澄清提示。
     */
    public static GuidanceDecision prompt(String prompt) {
        return new GuidanceDecision(Action.PROMPT, prompt);
    }

    /**
     * 是否需要向用户澄清。
     */
    public boolean isPrompt() {
        return action == Action.PROMPT;
    }
}
