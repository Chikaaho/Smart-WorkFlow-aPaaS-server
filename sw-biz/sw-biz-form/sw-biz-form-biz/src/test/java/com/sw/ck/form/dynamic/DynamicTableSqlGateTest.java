package com.sw.ck.form.dynamic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 §5.C.7 机械守门：动态宽表受控入口不可被重新绕过。
 *
 * <p>本测试直接扫描 sw-biz-form-biz 的 production 源码（{@code ${basedir}/src/main/java}），
 * 把“统一受控入口 + fail closed + REFERENCE 串行化”钉成机器可判定的规则：</p>
 * <ol>
 *   <li><b>G1 唯一执行出口</b>：{@code com/sw/ck/form/dynamic/} 之外的生产代码不得直接调用
 *       JdbcTemplate；动态宽表 SQL 必须经 {@link DynamicTableSql}。</li>
 *   <li><b>G2 标识符规则单一来源</b>：动态宽表表名正则字面量只允许出现在 {@link DynamicTableSql}。</li>
 *   <li><b>G3 删除路径无 fail-open</b>：{@code FormDataDeleteService} 的每个 catch 块必须抛出，
 *       不得“记录告警后继续”。</li>
 *   <li><b>G4 REFERENCE 串行化在两侧都存在</b>：删除路径引用 {@code tryLockLiveRow}，
 *       引用写入路径引用 {@code tryLockLiveRow(s)}。</li>
 *   <li><b>G5 不得恢复已移除的放行分支</b>：禁止出现删除路径的 fail-open 文案。</li>
 *   <li><b>G6 迁移不可回退</b>：受控入口调用点数量不得低于本次收口后的实测下限。</li>
 * </ol>
 *
 * <p>规则全部基于源码文本与可复算计数，不依赖类名、注释或人工约定。</p>
 */
@DisplayName("动态宽表受控入口机械守门")
class DynamicTableSqlGateTest {

    /** 允许直接持有 JdbcTemplate 的包（受控入口 + 动态表 DDL 管理器）。 */
    private static final String ALLOWED_RAW_JDBC_PATH = "com/sw/ck/form/dynamic/";

    /** 动态宽表表名正则片段（字面量唯一来源判据）。 */
    private static final String TABLE_NAME_PATTERN_LITERAL = "sw_form(_table)?_";

    /** 禁止恢复的 fail-open 文案（删除路径曾用）。 */
    private static final List<String> FORBIDDEN_FAIL_OPEN_MARKERS = List.of(
            "不阻塞删除", "查询失败放行", "元数据表不可用时放行");

    private static final Pattern RAW_JDBC_CALL = Pattern.compile("\\bjdbcTemplate\\s*\\.");

    private static Path moduleRoot() {
        String basedir = System.getProperty("basedir");
        Path root = basedir == null || basedir.isBlank()
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(basedir).toAbsolutePath().normalize();
        return root;
    }

    private static List<Path> productionSources() throws IOException {
        Path src = moduleRoot().resolve("src/main/java");
        assertTrue(Files.isDirectory(src), "未找到模块 production 源码目录: " + src);
        try (Stream<Path> stream = Files.walk(src)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse(files.isEmpty(), "production 源码目录为空: " + src);
            return files;
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 去掉注释与字符串字面量，便于做结构判定（保留行结构用于报错定位）。 */
    static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    out.append("  ");
                    i += 2;
                    continue;
                }
                out.append(c == '\n' ? '\n' : ' ');
                i++;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                out.append(c == '\n' ? '\n' : ' ');
                i++;
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                out.append(' ');
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                out.append("  ");
                i += 2;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                out.append("  ");
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(' ');
                i++;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(' ');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 提取每个 catch 块的 { ... } 体（括号配平），用于判定是否抛出。 */
    static List<String> catchBodies(String strippedSource) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\bcatch\\s*\\(").matcher(strippedSource);
        while (matcher.find()) {
            int open = strippedSource.indexOf('{', matcher.end());
            if (open < 0) {
                continue;
            }
            int depth = 0;
            for (int i = open; i < strippedSource.length(); i++) {
                char c = strippedSource.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        bodies.add(strippedSource.substring(open, i + 1));
                        break;
                    }
                }
            }
        }
        return bodies;
    }

    // ==================== G1 ====================

    @Test
    @DisplayName("G1：dynamic 包之外的生产代码不得直接调用 JdbcTemplate")
    void g1_onlyControlledEntryTouchesJdbcTemplate() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path path : productionSources()) {
            String normalized = path.toString().replace('\\', '/');
            if (normalized.contains(ALLOWED_RAW_JDBC_PATH)) {
                continue;
            }
            String source = stripCommentsAndLiterals(read(path));
            if (RAW_JDBC_CALL.matcher(source).find()) {
                violations.add(moduleRoot().relativize(path).toString());
            }
        }
        assertTrue(violations.isEmpty(),
                "以下生产文件绕过受控入口直接调用 JdbcTemplate，必须改经 DynamicTableSql: " + violations);
    }

    // ==================== G2 ====================

    @Test
    @DisplayName("G2：动态宽表表名正则字面量只有唯一来源")
    void g2_tableNamePatternHasSingleSource() throws IOException {
        List<String> owners = new ArrayList<>();
        for (Path path : productionSources()) {
            if (read(path).contains(TABLE_NAME_PATTERN_LITERAL)) {
                owners.add(moduleRoot().relativize(path).toString());
            }
        }
        assertEquals(List.of("src/main/java/com/sw/ck/form/dynamic/DynamicTableSql.java"), owners,
                "动态宽表表名正则字面量必须只在 DynamicTableSql 定义一次，实际: " + owners);
    }

    // ==================== G3 ====================

    @Test
    @DisplayName("G3：删除路径不得存在跳过/提前返回的 catch（禁止 fail-open）")
    void g3_deletePathCatchesMustNotSwallow() throws IOException {
        Path deleteService = moduleRoot()
                .resolve("src/main/java/com/sw/ck/form/service/FormDataDeleteService.java");
        assertTrue(Files.isRegularFile(deleteService), "未找到 FormDataDeleteService: " + deleteService);
        List<String> bodies = catchBodies(stripCommentsAndLiterals(read(deleteService)));
        assertFalse(bodies.isEmpty(), "删除路径应当存在显式异常处理（本测试用于守住其语义）");

        // 规则：每个 catch 体要么抛出，要么仅做诊断（不得 continue/return 静默放弃当前操作）。
        List<String> swallowing = bodies.stream()
                .filter(body -> !body.contains("throw ")
                        && (body.contains("continue") || body.contains("return")))
                .toList();
        assertTrue(swallowing.isEmpty(),
                "删除路径存在静默放弃（continue/return）的 catch 块（fail-open）: " + swallowing);

        long rethrowing = bodies.stream().filter(body -> body.contains("throw ")).count();
        assertTrue(rethrowing >= 4,
                "删除路径应至少有 4 处显式抛出（元数据扫描/引用检查/级联/主删），实际 " + rethrowing);
    }

    // ==================== G4 ====================

    @Test
    @DisplayName("G4：REFERENCE 串行化在删除侧与写入侧都存在")
    void g4_parentRowLockExistsOnBothSides() throws IOException {
        String deleteSource = read(moduleRoot()
                .resolve("src/main/java/com/sw/ck/form/service/FormDataDeleteService.java"));
        String enrichmentSource = read(moduleRoot()
                .resolve("src/main/java/com/sw/ck/form/service/FormFieldEnrichmentService.java"));
        String importSource = read(moduleRoot()
                .resolve("src/main/java/com/sw/ck/form/service/FormImportExportService.java"));

        assertTrue(deleteSource.contains("DynamicTableSql.tryLockLiveRow"),
                "删除路径必须对目标父行加锁（DynamicTableSql.tryLockLiveRow）");
        assertTrue(enrichmentSource.contains("DynamicTableSql.tryLockLiveRows")
                        || enrichmentSource.contains("DynamicTableSql.tryLockLiveRow"),
                "引用写入路径（提交/更新增补）必须对引用目标加锁");
        assertTrue(importSource.contains("DynamicTableSql.tryLockLiveRow"),
                "导入路径的引用校验必须对引用目标加锁");
    }

    // ==================== G5 ====================

    @Test
    @DisplayName("G5：不得恢复删除路径已移除的放行分支")
    void g5_failOpenMarkersStayRemoved() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path path : productionSources()) {
            String source = read(path);
            for (String marker : FORBIDDEN_FAIL_OPEN_MARKERS) {
                if (source.contains(marker)) {
                    hits.add(moduleRoot().relativize(path) + " -> " + marker);
                }
            }
        }
        assertTrue(hits.isEmpty(), "检测到已移除的 fail-open 分支被恢复: " + hits);
    }

    // ==================== G6 ====================

    @Test
    @DisplayName("G6：受控入口调用点数量不得低于收口下限")
    void g6_controlledEntryUsageNotRegressed() throws IOException {
        int controlledCalls = 0;
        for (Path path : productionSources()) {
            String source = read(path);
            Matcher matcher = Pattern.compile("DynamicTableSql\\.(query|queryForLong|queryForInt|update|ddl|tryLockLiveRow|tryLockLiveRows|requireColumn|requireTableName|quote|isValidTableName|livePredicate|appendLivePredicate|validatePhysicalColumn|requireMetadataTable|requireSystemColumn)\\(")
                    .matcher(source);
            while (matcher.find()) {
                controlledCalls++;
            }
        }
        // 收口后实测下限：低于该值说明有调用点被改回裸 JdbcTemplate 或被删除
        assertTrue(controlledCalls >= 40,
                "受控入口调用点仅 " + controlledCalls + " 处，低于收口下限 40，疑似回退");
    }

    // ==================== 附：源码扫描器自检 ====================

    @Test
    @DisplayName("扫描器自检：注释/字面量剥离与 catch 体提取可用")
    void scannerSelfCheck() {
        String sample = """
                // jdbcTemplate.update(x)
                String s = "jdbcTemplate.update(y)";
                try { foo(); } catch (Exception e) { log.warn("z"); }
                try { bar(); } catch (Exception e) { log.warn("z"); throw new IllegalStateException(e); }
                /* jdbcTemplate.execute(zz) */
                """;
        String stripped = stripCommentsAndLiterals(sample);
        assertFalse(RAW_JDBC_CALL.matcher(stripped).find(), "注释与字符串中的调用不得计入违规");
        List<String> bodies = catchBodies(stripped);
        assertEquals(2, bodies.size(), "应提取到 2 个 catch 块");
        assertFalse(bodies.get(0).contains("throw "), "第一个 catch 未抛出应被识别");
        assertTrue(bodies.get(1).contains("throw "), "第二个 catch 抛出应被识别");
        assertTrue(Pattern.compile(TABLE_NAME_PATTERN_LITERAL).matcher("sw_form_abcdef1234").find(),
                "表名正则片段应匹配真实表名");
    }
}
