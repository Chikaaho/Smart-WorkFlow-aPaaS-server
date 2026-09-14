-- V92 (I6)：见 postgresql/V92 注释（H2 侧宽松兼容 smallint 存 Boolean，不做改含）。
-- 保留同版本身份；H2 无 DDL 幂等 DO 块语法，按宽松语义无操作化确认。
select 1;
