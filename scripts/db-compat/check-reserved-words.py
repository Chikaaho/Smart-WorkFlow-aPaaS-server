#!/usr/bin/env python3
"""B1 保留字复核：按 10 个数据库的官方保留字清单扫描迁移 DDL 与 MyBatis-Plus 实体列名。

清单来源与取值口径见同目录 ``reserved-words/SOURCES.md``。只比对完整标识符
（大小写不敏感），不把 ``sys_user`` 因含 ``user`` 判为命中。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATION = os.path.join(ROOT, "sw-bootstrap", "src", "main", "resources", "db", "migration")
WORD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "reserved-words")

WORD_FILES = {
    "MySQL 5.7": "mysql57.txt",
    "MySQL 8.0": "mysql80.txt",
    "MariaDB 10.6": "mariadb106.txt",
    "Oracle 21c": "oracle21c.txt",
    "SQL Server 2019": "sqlserver2019.txt",
    "PostgreSQL": "postgresql.txt",
    "H2": "h2.txt",
    "DM8": "dm8.txt",
    "KingbaseES V8": "kingbaseesv8.txt",
    "openGauss 5": "opengauss5.txt",
}

# 已改名或已按方言引用处理的标识符：命中时打印原因，不计为失败。
RENAMED = {
    "comment": "0.3.2 的 sw_bpm_task_opinion_draft.comment 已由 R__0.4.0 改名为 opinion_comment",
}


def load_words(filename):
    path = os.path.join(WORD_DIR, filename)
    words = set()
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            word = line.strip()
            if word and not word.startswith("#"):
                words.add(word.lower())
    return words


def ddl_names():
    names = set()
    for vendor in ("postgresql", "h2"):
        directory = os.path.join(MIGRATION, vendor)
        if not os.path.isdir(directory):
            continue
        for filename in sorted(os.listdir(directory)):
            if not filename.endswith(".sql"):
                continue
            text = open(os.path.join(directory, filename), encoding="utf-8").read()
            current = False
            for line in text.splitlines():
                stripped = line.strip()
                match = re.match(r"(?i)create\s+table\s+(?:if\s+not\s+exists\s+)?([a-z0-9_]+)\s*\(", stripped)
                if match:
                    names.add(match.group(1).lower())
                    current = True
                    continue
                if current:
                    if stripped.startswith(");"):
                        current = False
                        continue
                    column = re.match(r"([a-z][a-z0-9_]*)\s+[a-z]+(?:\([^)]*\))?", stripped, re.I)
                    if column and column.group(1).lower() not in (
                            "primary", "unique", "constraint", "key", "index", "foreign", "check"):
                        names.add(column.group(1).lower())
            for match in re.finditer(
                    r"(?i)alter\s+table\s+[a-z0-9_]+\s+add\s+(?:column\s+)?(?:if\s+not\s+exists\s+)?([a-z][a-z0-9_]*)",
                    text):
                candidate = match.group(1).lower()
                if candidate not in ("constraint", "primary", "unique", "foreign", "check"):
                    names.add(candidate)
    return names


def entity_names():
    names = set()
    for base in ("sw-basic", "sw-biz", "sw-framework", "sw-bootstrap"):
        for dirpath, _, filenames in os.walk(os.path.join(ROOT, base)):
            if "target" in dirpath.split(os.sep) or f"{os.sep}src{os.sep}main{os.sep}java" not in dirpath + os.sep:
                continue
            for filename in filenames:
                if not filename.endswith(".java"):
                    continue
                text = open(os.path.join(dirpath, filename), encoding="utf-8", errors="ignore").read()
                for match in re.finditer(r'@Table(?:Name|Field)\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', text):
                    names.add(match.group(1).lower())
    return names


def main():
    sets = {db: load_words(filename) for db, filename in WORD_FILES.items()}
    names = ddl_names() | entity_names()
    hits = []
    renamed = []
    for name in sorted(names):
        matched = [db for db, words in sets.items() if name in words]
        if matched:
            if name in RENAMED:
                renamed.append((name, matched))
            else:
                hits.append((name, matched))
    print("checked identifiers:", len(names))
    for db, words in sets.items():
        print(f"loaded {db}: {len(words)}")
    for name, matched in renamed:
        print(f"RENAMED {name}: {', '.join(matched)} — {RENAMED[name]}")
    for name, matched in hits:
        print(f"HIT {name}: {', '.join(matched)}")
    if not hits:
        print("no unhandled reserved word hits")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
