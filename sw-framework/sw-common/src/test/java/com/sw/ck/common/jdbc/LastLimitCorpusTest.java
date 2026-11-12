package com.sw.ck.common.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R7：从源码提取全部 {@code .last(...)} 站点，断言都是可移植的尾部 LIMIT 写法，
 * 并在 Oracle / SQL Server 两种方言改写路径下都不抛异常。
 * <p>
 * 该测试是新增不合规 {@code .last} 的拦截门：新增站点会改变计数，必须同步盘点；
 * 非 {@code LIMIT n [OFFSET m]} 的写法会直接失败。动态拼接允许
 * {@code "LIMIT " + <表达式>} 与 {@code " OFFSET " + <表达式>} 两种形式。
 * </p>
 */
class LastLimitCorpusTest {

    private static final int EXPECTED_SITES = 135;
    private static final Pattern LIMIT_WITH_NUMBER = Pattern.compile("(?i)^limit\\s+\\d+$");
    private static final Pattern LIMIT_PREFIX = Pattern.compile("(?i)^limit\\s+$");
    private static final Pattern OFFSET_PREFIX = Pattern.compile("(?i)^\\s*offset\\s*$");

    @Test
    @DisplayName("135 处 .last 均为 LIMIT [OFFSET] 写法，Oracle / SQL Server 改写不抛异常")
    void lastSitesArePortable() throws IOException {
        Path root = serverRoot();
        List<Site> sites = new ArrayList<>();
        for (String directory : List.of("sw-framework", "sw-biz", "sw-basic", "sw-bootstrap/src/main/java")) {
            Path base = root.resolve(directory);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                        .filter(path -> !path.toString().replace('\\', '/').contains("/src/test/"))
                        .forEach(path -> sites.addAll(extract(path)));
            }
        }
        assertEquals(EXPECTED_SITES, sites.size(),
                "`.last` 站点数从 " + EXPECTED_SITES + " 变化，请同步 B1/B2 盘点与回执；"
                        + "复跑: grep -rn --include=*.java \".last(\" sw-framework sw-biz sw-basic sw-bootstrap/src/main");

        SqlDialectRewriteInterceptor interceptor = new SqlDialectRewriteInterceptor();
        List<Site> offsetSites = new ArrayList<>();
        for (Site site : sites) {
            List<String> literals = literals(site.argument());
            assertFalse(literals.isEmpty(), "无法从 .last 参数提取字面量: " + site);
            String first = literals.get(0);
            boolean limitWithNumber = LIMIT_WITH_NUMBER.matcher(first).matches();
            assertTrue(limitWithNumber || LIMIT_PREFIX.matcher(first).matches(),
                    "非 LIMIT 尾部写法: " + site + " -> \"" + first + "\"");
            boolean hasOffset = false;
            for (int i = 1; i < literals.size(); i++) {
                String literal = literals.get(i);
                if (OFFSET_PREFIX.matcher(literal).matches()) {
                    hasOffset = true;
                } else {
                    fail("非 OFFSET 附加字面量: " + site + " -> \"" + literal + "\"");
                }
            }
            String limit = limitWithNumber ? first.trim() : "LIMIT 1";
            String sql = "SELECT * FROM t " + limit + (hasOffset ? " OFFSET 0" : "");
            String oracle = interceptor.rewrite(sql, DatabaseVendor.ORACLE);
            assertTrue(oracle.toUpperCase(Locale.ROOT).contains("FETCH"),
                    "Oracle 改写结果缺少 FETCH: " + site + " -> " + oracle);
            String sqlServer = interceptor.rewrite(sql, DatabaseVendor.SQL_SERVER);
            assertTrue(sqlServer.toUpperCase(Locale.ROOT).contains("FETCH"),
                    "SQL Server 改写结果缺少 FETCH: " + site + " -> " + sqlServer);
            if (hasOffset) {
                offsetSites.add(site);
            }
        }
        System.out.printf("LAST-LIMIT sites=%d offsetSites=%d%n", sites.size(), offsetSites.size());
        for (Site site : offsetSites) {
            System.out.println("OFFSET site: " + site);
        }
    }

    private static Path serverRoot() {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("sw-bootstrap"))
                    && Files.isRegularFile(directory.resolve("pom.xml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("无法定位 Server 仓库根目录，user.dir=" + System.getProperty("user.dir"));
    }

    private static List<Site> extract(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + file, e);
        }
        List<Site> sites = new ArrayList<>();
        int index = 0;
        while ((index = text.indexOf(".last(", index)) >= 0) {
            int start = index + ".last(".length();
            int end = matchingParenthesis(text, start);
            if (end < 0) {
                fail("无法配对 .last( 括号: " + file + " offset=" + index);
            }
            int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
            sites.add(new Site(file, line, text.substring(start, end).strip()));
            index = end + 1;
        }
        return sites;
    }

    private static int matchingParenthesis(String text, int start) {
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> literals(String argument) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < argument.length()) {
            if (argument.charAt(i) != '"') {
                i++;
                continue;
            }
            i++;
            StringBuilder value = new StringBuilder();
            while (i < argument.length()) {
                char c = argument.charAt(i);
                if (c == '\\' && i + 1 < argument.length()) {
                    value.append(argument.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                value.append(c);
                i++;
            }
            literals.add(value.toString());
            i++;
        }
        return literals;
    }

    private record Site(Path file, int line, String argument) {
        @Override
        public String toString() {
            return file.getFileName() + ":" + line;
        }
    }
}
