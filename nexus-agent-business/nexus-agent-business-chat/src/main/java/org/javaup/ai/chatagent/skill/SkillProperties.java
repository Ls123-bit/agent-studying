package org.javaup.ai.chatagent.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
SKILL.md 技能体系配置（app.skills）。
技能以文件目录形式维护（Q1b 决策）：每个技能一个子目录，内含 SKILL.md 声明文件，
子目录内其他 .md 文件是该技能的参考资料。启动时扫描，修改文件后重启生效。
 */
@ConfigurationProperties(prefix = "app.skills")
public class SkillProperties {

    /*
    是否启用技能体系。
    关闭后 SkillRegistry 不扫描目录，系统提示词不注入技能目录，load_skill 工具不可用。
     */
    private boolean enabled = true;

    /*
    技能根目录。相对路径相对应用启动时的工作目录。
    默认值指向仓库根的 skills 目录：本模块的启动工作目录是 nexus-agent-business-chat，
    仓库根在它的上两级，因此用 ../../skills；部署到容器时用环境变量 SKILLS_BASE_DIR 覆盖。
     */
    private String baseDir = "../../skills";

    /*
    load_skill 单次返回给模型的最大字符数（Q2b 默认值③）。
    超长技能会被截断，防止把模型上下文窗口吃满。
     */
    private int maxLoadChars = 8000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public int getMaxLoadChars() {
        return maxLoadChars;
    }

    public void setMaxLoadChars(int maxLoadChars) {
        this.maxLoadChars = maxLoadChars;
    }
}
