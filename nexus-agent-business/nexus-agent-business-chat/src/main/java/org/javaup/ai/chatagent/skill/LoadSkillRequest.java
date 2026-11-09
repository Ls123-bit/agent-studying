package org.javaup.ai.chatagent.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
load_skill 工具入参定义。
模型从系统提示词的"可用技能目录"中选择技能名，通过 {"skillName":"xxx"} 传入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoadSkillRequest {

    private String skillName;
}
