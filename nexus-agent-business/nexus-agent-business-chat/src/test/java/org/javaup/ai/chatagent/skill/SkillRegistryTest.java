package org.javaup.ai.chatagent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
SkillRegistry 的纯单元测试：只验证目录扫描和 SKILL.md 解析逻辑，不依赖 Spring 容器和数据库。
 */
class SkillRegistryTest {

    @TempDir
    Path tempDir;

    private SkillRegistry newRegistry(String baseDir) {
        SkillProperties properties = new SkillProperties();
        properties.setBaseDir(baseDir);
        return new SkillRegistry(properties);
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void parseSkillWithFrontmatterAndReferenceFiles() throws IOException {
        Path skillDir = tempDir.resolve("weekly-report");
        write(skillDir.resolve("SKILL.md"), """
            ---
            name: weekly_report
            description: "撰写结构化周报"
            ---
            # 周报指令
            按模板输出四个板块。
            """);
        write(skillDir.resolve("template.md"), "# 模板\n本周完成...");

        SkillRegistry registry = newRegistry(tempDir.toString());
        registry.scan();

        SkillDefinition skill = registry.find("weekly_report");
        assertNotNull(skill);
        assertEquals("撰写结构化周报", skill.description());
        assertTrue(skill.instruction().contains("周报指令"));
        assertEquals(1, skill.referenceFiles().size());
        assertEquals("# 模板\n本周完成...", skill.referenceFiles().get("template.md"));
        assertFalse(registry.isEmpty());
        String catalog = registry.catalogSection();
        assertTrue(catalog.contains("weekly_report"));
        assertTrue(catalog.contains("撰写结构化周报"));
        assertTrue(catalog.contains("load_skill"));
    }

    @Test
    void skipDirectoryWithoutSkillFile() throws IOException {
        write(tempDir.resolve("broken").resolve("readme.txt"), "no skill here");

        SkillRegistry registry = newRegistry(tempDir.toString());
        registry.scan();

        assertTrue(registry.isEmpty());
        assertEquals("", registry.catalogSection());
    }

    @Test
    void fallBackToDirectoryNameWhenFrontmatterMissing() throws IOException {
        write(tempDir.resolve("note-taker").resolve("SKILL.md"), "# 没有 frontmatter 的技能\n直接写指令");

        SkillRegistry registry = newRegistry(tempDir.toString());
        registry.scan();

        SkillDefinition skill = registry.find("note-taker");
        assertNotNull(skill);
        assertTrue(skill.instruction().startsWith("# 没有 frontmatter 的技能"));
    }

    @Test
    void duplicateSkillNameFirstDirectoryWins() throws IOException {
        write(tempDir.resolve("a-dir").resolve("SKILL.md"), "---\nname: same\n---\nfirst");
        write(tempDir.resolve("b-dir").resolve("SKILL.md"), "---\nname: same\n---\nsecond");

        SkillRegistry registry = newRegistry(tempDir.toString());
        registry.scan();

        assertEquals("first", registry.find("same").instruction());
    }

    @Test
    void missingBaseDirYieldsEmptyRegistry() {
        SkillRegistry registry = newRegistry(tempDir.resolve("not-exist").toString());
        registry.scan();

        assertTrue(registry.isEmpty());
        assertNull(registry.find(null));
    }
}
