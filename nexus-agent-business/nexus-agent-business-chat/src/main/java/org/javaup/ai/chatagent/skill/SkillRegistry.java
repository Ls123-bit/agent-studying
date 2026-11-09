package org.javaup.ai.chatagent.skill;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/*
技能注册器（Skills 接入的核心，Q1b + Q2b 决策的落地）。

工作机制（两阶段按需加载）：
1. 启动时扫描 app.skills.base-dir 下的每个子目录，解析其中的 SKILL.md
   （frontmatter 声明 name / description，正文是完整指令），缓存成 SkillDefinition。
2. 系统提示词里只注入"技能目录"（每条 = 名称 + 一句话描述，见 catalogSection()），
   模型判断某次请求命中某个技能时，调用 load_skill 工具取回完整指令（见 LoadSkillTool）。
   这样 prompt 成本随技能数量增长最平缓，这正是 SKILL.md 机制的标准玩法。

容错约定（与 MCP 侧一致：降级不阻塞）：
- 目录不存在、扫描失败、单个 SKILL.md 解析失败都只 WARN/INFO 并跳过，不影响应用启动。
- 技能名重复时先到先得（按目录名排序保证结果确定）。
- 技能文件修改后需重启应用生效（v1 不做热加载）。
 */
@Slf4j
@Component
public class SkillRegistry {

    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final SkillProperties properties;

    /*
    已加载的技能表：技能名 → 定义。
    只在 @PostConstruct 扫描时写入，之后只读，线程安全。
     */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry(SkillProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void scan() {
        if (!properties.isEnabled()) {
            log.info("技能体系未启用（app.skills.enabled=false），跳过技能扫描");
            return;
        }

        Path baseDir = Path.of(properties.getBaseDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(baseDir)) {
            // 目录不存在是最常见的“技能静默失效”原因，必须把解析后的绝对路径打出来，
            // 否则使用者只能看到“没有可用技能”，无法判断是工作目录不对还是路径配错。
            log.warn("技能目录不存在: {}（配置值 {}）。已解析为绝对路径 {}，"
                    + "如果这不是预期位置，请检查应用启动工作目录，或用环境变量 SKILLS_BASE_DIR / app.skills.base-dir 指定。",
                baseDir, properties.getBaseDir(), baseDir);
            return;
        }

        try (Stream<Path> directories = Files.list(baseDir)) {
            // 按目录名排序保证多技能同名时"先到先得"的结果确定。
            directories.filter(Files::isDirectory)
                .sorted()
                .forEach(this::parseSkillDirectory);
        } catch (IOException exception) {
            log.warn("技能目录扫描失败: {}（技能体系将不可用，但不影响应用启动）", baseDir, exception);
            return;
        }

        if (skills.isEmpty()) {
            log.info("技能扫描完成：{} 下没有加载到任何技能", baseDir);
        } else {
            log.info("技能扫描完成：共加载 {} 个技能 {}", skills.size(), skills.keySet());
        }
    }

    /*
    解析单个技能目录：目录内必须有 SKILL.md，frontmatter 里的 name 缺失时用目录名兜底。
     */
    private void parseSkillDirectory(Path directory) {
        Path skillFile = directory.resolve(SKILL_FILE_NAME);
        if (!Files.isRegularFile(skillFile)) {
            log.warn("技能目录 {} 缺少 {}，已跳过", directory, SKILL_FILE_NAME);
            return;
        }

        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            Frontmatter frontmatter = parseFrontmatter(content);
            String name = StrUtil.isNotBlank(frontmatter.name())
                ? frontmatter.name().trim()
                : directory.getFileName().toString();

            if (skills.containsKey(name)) {
                log.warn("技能名 [{}] 重复（目录 {} 与 {}），按先到先得原则保留先加载的定义，跳过 {}",
                    name, skills.get(name).directoryName(), directory.getFileName(), directory.getFileName());
                return;
            }

            String directoryName = directory.getFileName().toString();
            SkillDefinition definition = new SkillDefinition(
                name,
                StrUtil.blankToDefault(frontmatter.description(), ""),
                directoryName,
                StrUtil.blankToDefault(frontmatter.body(), ""),
                loadReferenceFiles(directory)
            );
            skills.put(name, definition);
        } catch (Exception exception) {
            log.warn("解析 {} 失败，该技能已跳过", skillFile, exception);
        }
    }

    /*
    解析 SKILL.md 的 frontmatter：
    ---               ← 文件必须以这一行开头
    name: weekly_report
    description: ...
    ---               ← 结束标记必须独占一行
    正文...
    没有 frontmatter 或格式不完整时，整个文件按正文处理（name 由调用方用目录名兜底）。
     */
    private Frontmatter parseFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new Frontmatter(null, null, normalized.trim());
        }

        // 找到独占一行的关闭标记 "---"。
        int closing = normalized.indexOf("\n---", 4);
        while (closing > 0) {
            boolean lineEnd = closing + 4 >= normalized.length() || normalized.charAt(closing + 4) == '\n';
            if (lineEnd) {
                break;
            }
            closing = normalized.indexOf("\n---", closing + 1);
        }
        if (closing < 0) {
            return new Frontmatter(null, null, normalized.trim());
        }

        String header = normalized.substring(4, closing);
        int bodyStart = normalized.indexOf('\n', closing + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1).trim();

        String name = null;
        String description = null;
        for (String line : header.split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = stripQuotes(line.substring(separator + 1).trim());
            if ("name".equals(key) && StrUtil.isNotBlank(value)) {
                name = value;
            } else if ("description".equals(key) && StrUtil.isNotBlank(value)) {
                description = value;
            }
        }
        return new Frontmatter(name, description, body);
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /*
    加载技能目录里除 SKILL.md 之外的所有 .md 参考文件（Q3a 决策）。
    单个文件读取失败只 WARN 跳过，不影响技能主体。
     */
    private Map<String, String> loadReferenceFiles(Path directory) {
        Map<String, String> referenceFiles = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return fileName.endsWith(".md") && !SKILL_FILE_NAME.equalsIgnoreCase(path.getFileName().toString());
                })
                .sorted()
                .forEach(path -> {
                    try {
                        referenceFiles.put(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException exception) {
                        log.warn("读取技能参考文件失败，已跳过: {}", path, exception);
                    }
                });
        } catch (IOException exception) {
            log.warn("列举技能参考文件失败: {}", directory, exception);
        }
        return Collections.unmodifiableMap(referenceFiles);
    }

    /*
    渲染注入系统提示词的技能目录段。
    只注入 name + description 的"目录"，完整指令由模型通过 load_skill 按需取回；
    没有任何技能时返回空串，系统提示词保持原样。
     */
    public String catalogSection() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        section.append("\n\n# 可用技能目录\n");
        section.append("当用户请求与下列技能相关时，先调用 load_skill 工具（参数 JSON：{\"skillName\":\"技能名\"}）获取该技能的完整执行指令，再严格按指令完成任务。\n");
        section.append("只能使用下面列出的技能名，禁止编造目录之外的技能。\n");
        // 技能目录只在本次对话里出现时才注入，所以工具优先级规则写在这里最贴使用场景，
        // 也避免污染不加载任何技能的普通对话。
        section.append("工具优先级：技能规则优先于其他工具规则。"
            + "当用户请求属于技能覆盖的产出型任务（例如撰写周报、总结、汇报）时，"
            + "先加载技能并直接按技能指令完成，不需要为了“时效性”去调用联网搜索；"
            + "只有技能指令明确要求外部实时信息时，才调用搜索工具。\n");
        for (SkillDefinition skill : skills.values()) {
            section.append("- ").append(skill.name());
            if (StrUtil.isNotBlank(skill.description())) {
                section.append("：").append(skill.description());
            }
            section.append('\n');
        }
        return section.toString();
    }

    public SkillDefinition find(String skillName) {
        return skillName == null ? null : skills.get(skillName.trim());
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public Collection<SkillDefinition> all() {
        return List.copyOf(skills.values());
    }

    private record Frontmatter(String name, String description, String body) {
    }
}
