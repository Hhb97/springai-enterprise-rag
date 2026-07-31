package com.example.ragassitant.infrastructure.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownSectionParserTest {

    private final MarkdownSectionParser parser = new MarkdownSectionParser();

    private List<String> paths(String md) {
        return parser.parse(md).stream()
                .map(MarkdownSectionParser.Section::headingPath)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("祖先标题无正文时不产出小节，但后代仍拿到完整路径")
    void ancestorsWithoutBodyStillContributeToPath() {
        // 这正是真实文档的结构：h1/h2 只是分组，正文全在 h3
        String md = """
                # 后端管理系统操作手册

                ## 用户管理

                ### 创建新用户

                登录管理后台，进入用户管理模块。

                ### 重置用户密码

                在用户列表中找到目标用户。

                ## 角色权限

                ### 角色说明

                超级管理员拥有所有权限。
                """;

        List<MarkdownSectionParser.Section> sections = parser.parse(md);

        assertEquals(3, sections.size(), "只有带正文的 h3 才应产出小节");
        assertEquals(List.of(
                "后端管理系统操作手册 > 用户管理 > 创建新用户",
                "后端管理系统操作手册 > 用户管理 > 重置用户密码",
                "后端管理系统操作手册 > 角色权限 > 角色说明"), paths(md));
    }

    @Test
    @DisplayName("同级标题出栈，不会把兄弟串成父子")
    void siblingsDoNotNest() {
        String md = "# A\n\n## B1\n\n内容一\n\n## B2\n\n内容二\n";
        assertEquals(List.of("A > B1", "A > B2"), paths(md));
    }

    @Test
    @DisplayName("跳级标题（h1 直接到 h3）不会错乱")
    void skippedLevelsAreHandled() {
        String md = "# A\n\n### C\n\n内容\n\n## B\n\n内容二\n";
        assertEquals(List.of("A > C", "A > B"), paths(md));
    }

    @Test
    @DisplayName("围栏代码块内的 # 行不被当作标题")
    void fencedCodeIsNotHeading() {
        String md = """
                # 部署

                执行下面的命令：

                ```bash
                # 先构建
                mvn clean package
                ## 再启动
                java -jar app.jar
                ```

                完成。
                """;

        List<MarkdownSectionParser.Section> sections = parser.parse(md);
        assertEquals(1, sections.size(), "代码块把小节切碎了: " + paths(md));
        assertEquals("部署", sections.get(0).headingPath());
        assertTrue(sections.get(0).body().contains("# 先构建"), "代码块内容被吞掉了");
        assertTrue(sections.get(0).body().contains("java -jar app.jar"));
    }

    @Test
    @DisplayName("~~~ 围栏同样生效，且不与 ``` 混淆")
    void tildeFenceWorks() {
        String md = "# A\n\n~~~\n# 不是标题\n~~~\n\n正文\n";
        assertEquals(1, parser.parse(md).size());
        assertEquals("A", parser.parse(md).get(0).headingPath());
    }

    @Test
    @DisplayName("文首无标题的内容归为空路径小节")
    void preambleBecomesPathlessSection() {
        String md = "这是文档开头的说明。\n\n# 第一章\n\n章节内容。\n";
        List<MarkdownSectionParser.Section> sections = parser.parse(md);
        assertEquals(2, sections.size());
        assertEquals("", sections.get(0).headingPath());
        assertEquals(0, sections.get(0).level());
        assertEquals("第一章", sections.get(1).headingPath());
    }

    @Test
    @DisplayName("水平分割线被丢弃，不污染正文")
    void thematicBreaksAreDropped() {
        String md = "# A\n\n上半段。\n\n---\n\n下半段。\n";
        List<MarkdownSectionParser.Section> sections = parser.parse(md);
        assertEquals(1, sections.size());
        assertFalse(sections.get(0).body().contains("---"), "分割线残留: " + sections.get(0).body());
        assertTrue(sections.get(0).body().contains("上半段。"));
        assertTrue(sections.get(0).body().contains("下半段。"));
    }

    @Test
    @DisplayName("闭合式标题 ## 标题 ## 的尾部井号被剥除")
    void closedAtxHeadingIsTrimmed() {
        assertEquals(List.of("标题"), paths("## 标题 ##\n\n内容\n"));
    }

    @Test
    @DisplayName("level 与 title 正确回填")
    void levelAndTitleArePopulated() {
        List<MarkdownSectionParser.Section> sections = parser.parse("# A\n\n## B\n\n内容\n");
        assertEquals(1, sections.size());
        assertEquals(2, sections.get(0).level());
        assertEquals("B", sections.get(0).title());
    }

    @Test
    @DisplayName("空输入安全返回")
    void emptyInputIsSafe() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   \n\n ").isEmpty());
        assertTrue(parser.parse("# 只有标题没有正文\n").isEmpty());
    }
}
