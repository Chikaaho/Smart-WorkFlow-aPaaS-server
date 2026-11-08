#!/usr/bin/env python3
"""B1 保留字复核：按各库保留字清单扫描迁移 DDL 与 MyBatis-Plus 实体列名。

清单按 MySQL 5.7/8.0、MariaDB 10.6、Oracle 19c、SQL Server 2019、DM8、
KingbaseES V8、openGauss 5、PostgreSQL、H2 的常用保留字整理；完整官方清单
可在 B2 用 DatabaseMetaData.getSQLKeywords() 再补一轮。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATION = os.path.join(ROOT, "sw-bootstrap", "src", "main", "resources", "db", "migration")

MYSQL = set("""
accessible add all alter analyze and as asc asensitive before between bigint binary blob both by call cascade case change char character check collate column condition constraint continue convert create cross cube cume_dist current_date current_time current_timestamp current_user cursor database databases day_hour day_microsecond day_minute day_second dec decimal declare default delayed delete dense_rank desc describe deterministic distinct distinctrow div double drop dual each else elseif empty enclosed escaped except exists exit explain false fetch first_value float float4 float8 for force foreign from fulltext function generated get grant group grouping groups having high_priority hour_microsecond hour_minute hour_second if ignore in index infile inner inout insensitive insert int int1 int2 int3 int4 int8 integer interval into io_after_gtids io_before_gtids is iterate join json_table key keys kill lag last_value lateral lead leading leave left like limit linear lines load localtime localtimestamp lock long longblob longtext loop low_priority master_bind match maxvalue mediumblob mediumint mediumtext middleint minute_microsecond minute_second mod modifies natural not no_write_to_binlog nth_value ntile null numeric of on optimize optimizer_costs option optionally or order out outer outfile over partition percent_rank precision primary procedure purge range rank read reads read_write real recursive references regexp release rename repeat replace require resignal restrict return revoke right rlike row rows row_number schema schemas second_microsecond select sensitive separator set show signal smallint spatial specific sql sqlexception sqlstate sqlwarning ssl starting stored straight_join system table terminated then tinyblob tinyint tinytext to trailing trigger true undo union unique unlock unsigned update usage use using utc_date utc_time utc_timestamp values varbinary varchar varying virtual when where while window with write xor year_month zerofill
""".split())
ORACLE = set("""
access add all alter and any as asc audit between by char check cluster column comment compress connect create current date decimal default delete desc distinct drop else exclusive exists file float for from grant group having identified immediate in increment index initial insert integer intersect into is level like lock long maxextents minus mlslabel mode modify noaudit nocompress not nowait null number of offline on online option or order pctfree prior privileges public raw rename resource revoke row rowid rownum rows select session set share size smallint start successful synonym sysdate table then to trigger uid union unique update user validate values varchar varchar2 view whenever where with
""".split())
MSSQL = set("""
add all alter and any as asc authorization backup begin between break browse bulk by cascade case check checkpoint close clustered coalesce collate column commit compute constraint contains containstable continue convert create cross current current_date current_time current_timestamp current_user cursor database dbcc deallocate declare default delete deny desc disk distinct distributed double drop dump else end errlvl escape except exec execute exists exit external fetch file fillfactor for foreign freetext freetexttable from full function goto grant group having holdlock identity identity_insert identitycol if in index inner insert intersect into is join key kill left like lineno load merge national nocheck nonclustered not null nullif of off offsets on open opendatasource openquery openrowset openxml option or order outer over percent pivot plan precision primary print proc procedure public raiserror read readtext reconfigure references replication restore restrict return revert revoke right rollback rowcount rowguidcol rule save schema securityaudit select semantickeyphrasetable semanticsimilaritydetailstable semanticsimilaritytable session_user set setuser shutdown some statistics system_user table tablesample textsize then to top tran transaction trigger truncate try_convert tsequal union unique unpivot update updatetext use user values varying view waitfor when where while with writetext
""".split())
POSTGRES = set("""
all analyse analyze and any array as asc asymmetric authorization binary both case cast check collate collation column concurrently constraint create cross current_catalog current_date current_role current_schema current_time current_timestamp current_user default deferrable desc distinct do else end except false fetch for foreign freeze from full grant group having ilike in initially inner intersect into is isnull join lateral leading left like limit localtime localtimestamp natural not notnull null offset on only or order outer overlaps placing primary references returning right select session_user similar some symmetric table tablesample then to trailing true union unique user using variadic verbose when where window with
""".split())
DM = ORACLE | set("""
bit boolean text datetime timestamp blob clob nclob integer bigint tinyint mediumint smallint real double decimal numeric char varchar varchar2 nchar nvarchar nvarchar2 binary varbinary image uniqueidentifier xml hierarchyid type
""".split())
H2 = set("""
all and any array as asymmetric authorization between both case cast check constraint cross current_catalog current_date current_path current_role current_schema current_time current_timestamp current_user day default distinct else end except exists false fetch filter for foreign from full group groups having hour if ilike in inner intersect interval is join key leading left like limit localtime localtimestamp minute month natural not null offset on or order over partition primary qualify range references right row rownum rows second select session_user set some symmetric system_user table to top trailing true unbounded union unique unknown user using value values when where window with year
""".split())
SETS = {
    "MySQL 5.7/8.0": MYSQL,
    "MariaDB 10.6": MYSQL,
    "Oracle 19c": ORACLE,
    "SQL Server 2019": MSSQL,
    "DM8": DM,
    "KingbaseES V8": POSTGRES | DM,
    "openGauss 5": POSTGRES,
    "PostgreSQL": POSTGRES,
    "H2": H2,
}


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
    names = ddl_names() | entity_names()
    hits = []
    for name in sorted(names):
        matched = [db for db, words in SETS.items() if name in words]
        if matched:
            hits.append((name, matched))
    print("checked identifiers:", len(names))
    for name, matched in hits:
        print(f"HIT {name}: {', '.join(matched)}")
    if not hits:
        print("no reserved word hits")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
