package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U1 静态部分：解析 PG、DM、Kingbase 迁移脚本得到表/列集合，与 PG 终态比较。
 * DM 由 Oracle 转换、Kingbase 由 PG 复制，因此只需与 PG 终态对齐。
 */
@DisplayName("U1：DM/Kingbase 脚本表结构静态比对")
class StaticSchemaParityTest {

    private static final Set<String> IGNORED = Set.of("sw_knowledge_chunk.embedding");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.`\"\\[\\]]+)\\s*\\((.*)\\)\\s*$");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+(?:ONLY\\s+)?([\\w.`\"\\[\\]]+)\\s+(.*)$");
    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "(?is)ALTER\\s+TABLE\\s+(?:ONLY\\s+)?([\\w.`\"\\[\\]]+)\\s+RENAME\\s+COLUMN\\s+([\\w`\"\\[\\]]+)\\s+TO\\s+([\\w`\"\\[\\]]+)");

    @Test
    @DisplayName("DM/Kingbase 的表与列集合和 PG 一致（embedding 白名单）")
    void staticSchemasMatchPostgres() throws IOException {
        Path base = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration");
        Map<String, Set<String>> pg = parse(base.resolve("postgresql"));
        Map<String, Set<String>> dm = parse(base.resolve("dm"));
        Map<String, Set<String>> kb = parse(base.resolve("kingbase"));
        assertSameSchema("DM", pg, dm);
        assertSameSchema("Kingbase", pg, kb);
    }

    private static void assertSameSchema(String label, Map<String, Set<String>> pg,
                                         Map<String, Set<String>> target) {
        assertEquals(new TreeSet<>(pg.keySet()), new TreeSet<>(target.keySet()), label + " 表集合与 PG 不一致");
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : pg.entrySet()) {
            String table = entry.getKey();
            Set<String> expected = new TreeSet<>(entry.getValue());
            Set<String> actual = new TreeSet<>(target.getOrDefault(table, Set.of()));
            for (String ignored : IGNORED) {
                String[] parts = ignored.split("\\.");
                if (parts[0].equals(table)) {
                    expected.remove(parts[1]);
                    actual.remove(parts[1]);
                }
            }
            if (!expected.equals(actual)) {
                diffs.add(table + " PG=" + expected + " " + label + "=" + actual);
            }
        }
        assertTrue(diffs.isEmpty(), label + " 列集合与 PG 不一致: " + diffs);
    }

    private static Map<String, Set<String>> parse(Path dir) throws IOException {
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> sortKey(p.getFileName().toString())))
                    .toList();
        }
        StringBuilder all = new StringBuilder();
        for (Path file : files) {
            all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
        }
        String sql = stripComments(all.toString());
        Map<String, Set<String>> schema = new TreeMap<>();
        for (String statement : splitStatements(sql)) {
            String normalized = statement.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            Matcher create = CREATE_TABLE.matcher(normalized);
            if (create.matches()) {
                String table = normalize(create.group(1));
                if (table.equals("flyway_schema_history")) {
                    continue;
                }
                Set<String> columns = new LinkedHashSet<>();
                for (String part : splitTopLevel(create.group(2))) {
                    String column = columnName(part);
                    if (column != null) {
                        columns.add(column);
                    }
                }
                schema.put(table, columns);
                continue;
            }
            Matcher alter = ALTER_TABLE.matcher(normalized);
            if (alter.matches()) {
                String table = normalize(alter.group(1));
                String rest = alter.group(2).strip();
                applyAlter(schema, table, rest);
            }
        }
        // R__ rename 可能包在 DO $$ 中，单独扫描。
        Matcher rename = RENAME_COLUMN.matcher(sql);
        while (rename.find()) {
            Set<String> columns = schema.get(normalize(rename.group(1)));
            if (columns != null) {
                String oldName = normalize(rename.group(2));
                String newName = normalize(rename.group(3));
                if (columns.remove(oldName)) {
                    columns.add(newName);
                }
            }
        }
        return schema;
    }

    private static void applyAlter(Map<String, Set<String>> schema, String table, String rest) {
        Matcher add = Pattern.compile(
                        "(?is)ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w`\"\\[\\]]+)")
                .matcher(rest);
        boolean added = false;
        while (add.find()) {
            String name = normalize(add.group(1));
            if (name.equals("constraint") || name.equals("primary") || name.equals("unique")
                    || name.equals("foreign") || name.equals("check")) {
                continue;
            }
            schema.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(name);
            added = true;
        }
        if (added) {
            return;
        }
        Matcher addGroup = Pattern.compile("(?is)^ADD\\s*\\((.*)\\)").matcher(rest);
        if (addGroup.find()) {
            Set<String> columns = schema.computeIfAbsent(table, k -> new LinkedHashSet<>());
            for (String part : splitTopLevel(addGroup.group(1))) {
                String column = columnName(part);
                if (column != null) {
                    columns.add(column);
                }
            }
            return;
        }
        Matcher drop = Pattern.compile("(?is)^DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?([\\w`\"\\[\\]]+)")
                .matcher(rest);
        if (drop.find()) {
            Set<String> columns = schema.get(table);
            if (columns != null) {
                columns.remove(normalize(drop.group(1)));
            }
        }
    }

    private static String columnName(String part) {
        String trimmed = part.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CONSTRAINT") || upper.startsWith("PRIMARY KEY")
                || upper.startsWith("UNIQUE") || upper.startsWith("FOREIGN KEY")
                || upper.startsWith("CHECK")) {
            return null;
        }
        int space = indexOfWhitespace(trimmed);
        String name = space < 0 ? trimmed : trimmed.substring(0, space);
        return normalize(name);
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String raw) {
        String value = raw.strip();
        int dot = value.lastIndexOf('.');
        if (dot >= 0) {
            value = value.substring(dot + 1);
        }
        value = value.replace("\"", "").replace("`", "")
                .replace("[", "").replace("]", "");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)--.*$", " ");
    }

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
            }
            if (c == ';' && !inSingle) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inSingle = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inSingle = !inSingle;
            }
            if (!inSingle) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String sortKey(String filename) {
        Matcher versioned = Pattern.compile("^V([0-9][0-9A-Za-z_.]*)__").matcher(filename);
        if (versioned.find()) {
            StringBuilder key = new StringBuilder("0");
            for (String part : versioned.group(1).split("[._]")) {
                try {
                    key.append(String.format("%08d", Integer.parseInt(part)));
                } catch (NumberFormatException e) {
                    key.append(part);
                }
                key.append('.');
            }
            return key.toString();
        }
        return "1" + filename;
    }
}
