package org.javaup.ai.chatagent.rag.core.rewrite;

import java.util.List;

/**
 * 查询改写结果。
 *
 * <p>改写阶段的唯一产出物，包含改写后的独立问题和拆分后的子问题列表。
 * 该结果不依赖任何上游阶段的输出，保证改写的独立性。</p>
 */
public record RewriteResult(

        /**
         * 改写后的完整独立问题（已消解指代、补全上下文）。
         */
        String rewrittenQuestion,

        /**
         * 拆分后的子问题列表。
         *
         * <p>如果原始问题只包含一个语义单元，
         * 则列表中只有一个元素，等于 rewrittenQuestion。</p>
         */
        List<String> subQuestions
) {
}
