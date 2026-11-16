package com.sw.ck.common.config.mybatis.datascope;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.sw.ck.common.datascope.DataScope;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code DataPermissionInterceptor} 要求的 handler 必须实现 {@link MultiDataPermissionHandler}
 * （而不是只实现父接口 {@code DataPermissionHandler}）——其
 * {@code buildTableExpression(Table, Expression, String)} 内部固定把 handler 强转为
 * {@link MultiDataPermissionHandler} 后调用三参 {@code getSqlSegment}，只实现父接口会在运行期
 * 抛 {@code ClassCastException}（已通过反编译 3.5.9 的字节码确认，而非 API 文档假设）。
 * <p>
 * MP 对一条 SQL 涉及的每个表都会回调一次本方法（多表 join 时回调多次，每次传入不同的
 * {@link Table}），因此用 {@link DataScope#deptAlias()}/{@link DataScope#userAlias()} 与当前
 * 表的别名比对，只对匹配到的那一个表拼接条件，避免同一条件被错误地重复拼接到 join 中的其它表上。
 */
@Log4j2
@RequiredArgsConstructor
public class DataScopeHandler implements MultiDataPermissionHandler {

    /**
     * 别名/列名白名单：只允许字母数字下划线、且不以数字开头，杜绝把外部可控字符串拼进 SQL
     * 文本造成注入——这里的别名虽然来自开发者写在注解里的字面量，不是用户输入，但仍按白名单校验，
     * 不依赖“开发者不会写错”这个假设。
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Map<String, Optional<DataScope>> ANNOTATION_CACHE = new ConcurrentHashMap<>();

    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        DataScope dataScope = resolveAnnotation(mappedStatementId);
        if (dataScope == null) {
            return null;
        }
        if (loginContextProvider.isSuperAdmin()) {
            return null;
        }
        DataScopeType scopeType = loginContextProvider.getDataScopeType();
        if (scopeType == null || scopeType == DataScopeType.ALL) {
            return null;
        }
        if (scopeType == DataScopeType.SELF) {
            return matchesAlias(table, dataScope.userAlias()) ? buildSelfCondition(dataScope.userAlias()) : null;
        }
        if (!matchesAlias(table, dataScope.deptAlias())) {
            return null;
        }
        return switch (scopeType) {
            case DEPT -> buildDeptCondition(dataScope.deptAlias(), singleDeptId());
            case DEPT_AND_CHILD -> buildDeptCondition(dataScope.deptAlias(), deptAndChildIds());
            case CUSTOM -> buildDeptCondition(dataScope.deptAlias(), customDeptIds());
            default -> null;
        };
    }

    /**
     * deptAlias/userAlias 留空（默认值）匹配“不带别名的表”，即单表查询场景；
     * 非空时只匹配别名相同的那一个表，多表 join 下精确定位。
     */
    private boolean matchesAlias(Table table, String alias) {
        if (alias == null || alias.isBlank()) {
            return table.getAlias() == null;
        }
        validateIdentifier(alias);
        return table.getAlias() != null && alias.equals(table.getAlias().getName());
    }

    private List<Long> singleDeptId() {
        Long deptId = loginContextProvider.getDeptId();
        return deptId == null ? List.of() : List.of(deptId);
    }

    private List<Long> deptAndChildIds() {
        Long deptId = loginContextProvider.getDeptId();
        if (deptId == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        ids.add(deptId);
        ids.addAll(deptScopeProvider.listChildDeptIds(deptId));
        return ids;
    }

    private List<Long> customDeptIds() {
        Set<Long> ids = loginContextProvider.getCustomDeptIds();
        return ids == null || ids.isEmpty() ? List.of() : new ArrayList<>(ids);
    }

    private Expression buildDeptCondition(String alias, List<Long> deptIds) {
        if (deptIds.isEmpty()) {
            return noRows();
        }
        Column column = column(alias, "dept_id");
        if (deptIds.size() == 1) {
            return new EqualsTo(column, new LongValue(deptIds.get(0)));
        }
        List<Expression> values = new ArrayList<>(deptIds.size());
        for (Long id : deptIds) {
            values.add(new LongValue(id));
        }
        // 普通 ExpressionList 默认 usingBrackets=false，toString 不会带括号，拼出的 SQL
        // 形如 "dept_id IN 1, 2, 3" 在多数数据库下是语法错误；必须用 ParenthesedExpressionList
        // 才能渲染成 "dept_id IN (1, 2, 3)"。
        return new InExpression(column, new ParenthesedExpressionList<>(new ExpressionList<>(values)));
    }

    private Expression buildSelfCondition(String alias) {
        Long userId = loginContextProvider.getUserId();
        if (userId == null) {
            return noRows();
        }
        return new EqualsTo(column(alias, "create_by"), new LongValue(userId));
    }

    /**
     * 数据范围解析为空集合（如 CUSTOM 未配置任何部门）时，不能直接放行（等同 ALL），
     * 而是拼一个恒假条件让查询返回零行，语义上等价于“当前用户看不到任何数据”。
     */
    private Expression noRows() {
        return new EqualsTo(new LongValue(1), new LongValue(0));
    }

    private Column column(String alias, String columnName) {
        validateIdentifier(columnName);
        if (alias == null || alias.isBlank()) {
            return new Column(columnName);
        }
        validateIdentifier(alias);
        return new Column(new Table(alias), columnName);
    }

    private void validateIdentifier(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalStateException("非法的列名/别名: " + identifier + "，只允许字母、数字、下划线");
        }
    }

    /**
     * mappedStatementId 形如 {@code ${MapperFQCN}.${methodName}}。只对 Mapper 接口里直接声明
     * （含默认方法）的方法生效，继承自 BaseMapper 的通用方法反射不到、视为未标注。
     * 解析结果按 mappedStatementId 缓存，避免每次查询都重新反射。
     */
    private DataScope resolveAnnotation(String mappedStatementId) {
        return ANNOTATION_CACHE.computeIfAbsent(mappedStatementId, this::lookupAnnotation).orElse(null);
    }

    private Optional<DataScope> lookupAnnotation(String mappedStatementId) {
        int idx = mappedStatementId.lastIndexOf('.');
        if (idx < 0) {
            return Optional.empty();
        }
        String className = mappedStatementId.substring(0, idx);
        String methodName = mappedStatementId.substring(idx + 1);
        try {
            Class<?> mapperClass = Class.forName(className);
            for (Method method : mapperClass.getMethods()) {
                if (method.getName().equals(methodName)) {
                    DataScope dataScope = method.getAnnotation(DataScope.class);
                    if (dataScope != null) {
                        return Optional.of(dataScope);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            log.warn("解析 @DataScope 失败，mappedStatementId={}", mappedStatementId, e);
        }
        return Optional.empty();
    }
}
