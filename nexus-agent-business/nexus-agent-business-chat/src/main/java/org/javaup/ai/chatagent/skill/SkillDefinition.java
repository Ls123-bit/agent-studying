package org.javaup.ai.chatagent.skill;

import java.util.Map;

/*
一个已加载的技能定义（Claude 风格 SKILL.md 的 Java 侧映射，Q1a 决策）。
- name / description：来自 SKILL.md 的 frontmatter，description 会进入系统提示词的技能目录。
- instruction：SKILL.md frontmatter 之后的正文，即技能的完整执行指令，只在 load_skill 时返回。
- referenceFiles：技能目录内除 SKILL.md 之外的其他 .md 文件（文件名 → 内容），
  load_skill 时一并返回，作为技能的补充资料（Q3a 决策：v1 只支持引用文本，不支持脚本）。
 */
public record SkillDefinition(String name,
                              String description,
                              String directoryName,
                              String instruction,
                              Map<String, String> referenceFiles) {
}
