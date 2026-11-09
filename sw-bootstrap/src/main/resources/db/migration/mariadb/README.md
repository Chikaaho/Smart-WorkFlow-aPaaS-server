# MariaDB 10.6 Migration Scripts

本目录脚本与 `mysql/` 同源（MariaDB 10.6 兼容 MySQL 5.7/8.0 DDL 与种子语法），
仅用于 MariaDB 10.6 容器验证；如后续出现方言差异，在本目录单独修订，不改 MySQL 脚本。

验证口径：
- 数据库字符集 `utf8mb4`、排序规则 `utf8mb4_bin`（启动检查会拒绝不满足的库）。
- `JSON` 列在 MariaDB 中是 LONGTEXT 别名，MyBatis/JSON 函数测试已覆盖。
- `explicit_defaults_for_timestamp=ON` 通过 JDBC `sessionVariables` 设置。
