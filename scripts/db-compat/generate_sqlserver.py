from pathlib import Path
import re, sys

schema_path, data_path, out_dir = map(Path, sys.argv[1:4])
schema = schema_path.read_text(encoding='utf-8')
data = data_path.read_text(encoding='utf-8')

def q(name): return '[' + name.lower() + ']'

def conv_type(t):
    t = t.strip()
    m = re.fullmatch(r'character varying\((\d+)\)', t)
    if m:
        n = int(m.group(1))
        return f'NVARCHAR({n})' if n <= 4000 else 'NVARCHAR(MAX)'
    if t in ('text', 'jsonb'):
        return 'NVARCHAR(MAX)'
    if t == 'timestamp without time zone':
        return 'DATETIME2'
    if t == 'boolean':
        return 'BIT'
    if t == 'integer':
        return 'INT'
    if t == 'bigint':
        return 'BIGINT'
    if t == 'smallint':
        return 'SMALLINT'
    m = re.fullmatch(r'numeric\((\d+),(\d+)\)', t)
    if m:
        return f'DECIMAL({m.group(1)},{m.group(2)})'
    if t == 'public.vector':
        return None
    raise ValueError('unmapped type: ' + t)

def conv_default(d):
    d = d.strip()
    d = re.sub(r'::[a-zA-Z_ ]+(?:\[\])?$', '', d).strip()
    if d.lower() == 'true': return '1'
    if d.lower() == 'false': return '0'
    if d.lower() in ('now()', 'current_timestamp'): return 'SYSDATETIME()'
    if d.upper() == 'NULL': return None
    m = re.fullmatch(r"'(.*)'", d, re.S)
    if m:
        return "N'" + m.group(1).replace("'", "''") + "'"
    return d

# Parse table columns
tables = {}
lines = schema.splitlines()
i = 0
while i < len(lines):
    line = lines[i]
    m = re.match(r'CREATE TABLE public\.(\w+) \($', line)
    if not m:
        i += 1
        continue
    table = m.group(1)
    if table == 'flyway_schema_history':
        # skip block
        while i < len(lines) and lines[i].strip() != ');':
            i += 1
        i += 1
        continue
    cols = []
    i += 1
    while i < len(lines) and lines[i].strip() != ');':
        raw = lines[i].strip().rstrip(',')
        cm = re.match(r'(\w+) (.*)$', raw)
        if not cm:
            raise ValueError('bad column: ' + raw)
        col, rest = cm.group(1), cm.group(2)
        # extract type by known prefixes
        type_pat = re.match(r'(character varying\(\d+\)|numeric\(\d+,\d+\)|timestamp without time zone|public\.vector|bigint|integer|smallint|boolean|jsonb|text)(.*)$', rest)
        if not type_pat:
            raise ValueError('bad type: ' + rest)
        ctype, tail = type_pat.group(1), type_pat.group(2)
        nullable = 'NOT NULL' not in tail
        default = None
        dm = re.search(r'DEFAULT (.+?)(?: NOT NULL)?$', tail)
        if dm:
            default = conv_default(dm.group(1))
        cols.append({'name': col, 'type': ctype, 'nullable': nullable, 'default': default})
        i += 1
    tables[table] = cols
    i += 1

# Emit tables
out = []
out.append('-- SQL Server 首次支持：由 PostgreSQL 0.4.0 终态 schema 生成（不含 flyway_schema_history）。')
out.append('-- 类型映射：字符/大文本/布尔/时间类型已转为 SQL Server 等价类型。')
for table, cols in tables.items():
    out.append(f'CREATE TABLE [dbo].{q(table)} (')
    parts = []
    for c in cols:
        t = conv_type(c['type'])
        if t is None:
            continue
        piece = f'    {q(c["name"])} {t}'
        if c['default'] is not None:
            piece += ' DEFAULT ' + c['default']
        if not c['nullable']:
            piece += ' NOT NULL'
        parts.append(piece)
    out.append(',\n'.join(parts))
    out.append(');')
    out.append('')

def cols_list(raw):
    return ', '.join(q(x.strip()) for x in raw.split(','))

def index_cols(raw):
    parts = []
    for c in raw.split(','):
        c = c.strip()
        order = ''
        if c.upper().endswith(' DESC'):
            order, c = ' DESC', c[:-5]
        elif c.upper().endswith(' ASC'):
            order, c = ' ASC', c[:-4]
        c = re.sub(r'\s+NULLS\s+(FIRST|LAST)$', '', c, flags=re.I)
        parts.append(q(c) + order)
    return ', '.join(parts)

# PK / unique constraints
for m in re.finditer(r'ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) (PRIMARY KEY|UNIQUE) \(([^)]+)\);', schema):
    table, name, kind, cols = m.groups()
    if table == 'flyway_schema_history':
        continue
    qcols = cols_list(cols)
    if kind == 'PRIMARY KEY':
        out.append(f'ALTER TABLE [dbo].{q(table)} ADD CONSTRAINT {q(name)} PRIMARY KEY ({qcols});')
    else:
        nullable = [c for c in cols.split(',') if any(x['name'] == c.strip() and x['nullable'] for x in tables[table])]
        if nullable:
            flt = ' AND '.join(f'{q(c.strip())} IS NOT NULL' for c in nullable)
            out.append(f'CREATE UNIQUE INDEX {q(name)} ON [dbo].{q(table)} ({qcols}) WHERE {flt};')
        else:
            out.append(f'ALTER TABLE [dbo].{q(table)} ADD CONSTRAINT {q(name)} UNIQUE ({qcols});')
out.append('')

# Indexes
for m in re.finditer(r'CREATE (UNIQUE )?INDEX (\w+) ON public\.(\w+) USING btree \(([^)]+)\)(?: WHERE \((.+?)\))?;', schema):
    unique, name, table, cols, where = m.groups()
    if table == 'flyway_schema_history':
        continue
    qcols = index_cols(cols)
    flt = None
    if where:
        flt = where.replace('true', '1').replace('false', '0')
    elif unique:
        nullable = [c for c in cols.split(',') if any(x['name'] == c.strip() and x['nullable'] for x in tables[table])]
        if nullable:
            flt = ' AND '.join(f'{q(c.strip())} IS NOT NULL' for c in nullable)
    sql = f'CREATE {"UNIQUE " if unique else ""}INDEX {q(name)} ON [dbo].{q(table)} ({qcols})'
    if flt:
        sql += ' WHERE ' + flt
    out.append(sql + ';')
out.append('')

schema_sql = '\n'.join(out) + '\n'

# Data inserts
def conv_value(v):
    # protect strings
    strings = []
    def stash(m):
        strings.append(m.group(1))
        return f'__S{len(strings)-1}__'
    masked = re.sub(r"'((?:[^']|'')*)'", stash, v)
    masked = re.sub(r'\btrue\b', '1', masked, flags=re.I)
    masked = re.sub(r'\bfalse\b', '0', masked, flags=re.I)
    def unstash(m):
        s = strings[int(m.group(1))]
        return "N'" + s + "'"
    return re.sub(r'__S(\d+)__', unstash, masked)

inserts = ['-- SQL Server 种子数据：由 PostgreSQL 0.4.0 终态数据生成，插入顺序按 pg_dump。']
for m in re.finditer(r'^INSERT INTO public\.(\w+) \(([^)]+)\) VALUES (.*);$', data, re.M):
    table, colraw, values = m.groups()
    if table == 'flyway_schema_history':
        continue
    qcols = ', '.join(q(c.strip()) for c in colraw.split(','))
    inserts.append(f'INSERT INTO [dbo].{q(table)} ({qcols}) VALUES {conv_value(values)};')
inserts.append('')

out_dir.mkdir(parents=True, exist_ok=True)
(out_dir / 'V0.2.0__baseline_seed.sql').write_text(schema_sql, encoding='utf-8')
(out_dir / 'R__0.2.0_sqlserver_seed.sql').write_text('\n'.join(inserts) + '\n', encoding='utf-8')
versions = ['0.2.2', '0.2.3', '0.2.4', '0.2.5', '0.2.6', '0.2.7', '0.2.8', '0.2.9', '0.4.0']
for v in versions:
    (out_dir / f'V{v}__sqlserver_baseline_merged.sql').write_text(
        f'-- SQL Server 首次支持：0.4.0 终态基线已合并到 V0.2.0，本版本无增量。\nSELECT 1;\n', encoding='utf-8')
repeatables = ['0.1.7_catalog_menu_first','0.1.7_org_demo_seed','0.2.0_approval_opinion_draft','0.2.0_ext_datasource_grant','0.2.0_form_integration','0.2.1_workspace_stat_metric_cards','0.4.0_rename_bpm_opinion_comment','application_center_menu','bf20261013_catalog_admin_menu','bf20261020_form_data_admin_grants','i6_notify_menu_reconciliation','p62_form_txn_action_menu','p62_iot_command_verify_menu','p62_resource_ops_menu','p62_txn_batch_menu','p63_iot_reservation_menu','p64_approval_form_menu','p64_position_delegate_menu','u020_user_group_menu']
for name in repeatables:
    (out_dir / f'R__{name}.sql').write_text(
        f'-- SQL Server 首次支持：种子已合并到 R__0.2.0_sqlserver_seed.sql，本对账脚本无增量。\nSELECT 1;\n', encoding='utf-8')
print('tables', len(tables), 'schema lines', len(schema_sql.splitlines()), 'inserts', len(inserts)-2)
