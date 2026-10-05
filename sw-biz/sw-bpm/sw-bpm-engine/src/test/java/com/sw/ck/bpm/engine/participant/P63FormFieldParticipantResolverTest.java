package com.sw.ck.bpm.engine.participant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.facade.FormRecordReadFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * P63 FORM_FIELD 参与人策略单元测试：
 * 主字段/表格列 × 人员/部门 解析、多选收集、失效对象拒绝、部门唯一负责人权威解析。
 */
class P63FormFieldParticipantResolverTest {

    private static final Long TENANT = 100L;

    private FormFieldParticipantResolver resolver(FormRecordReadFacade facade,
                                                  UserQueryFacade users) {
        return new FormFieldParticipantResolver(facade, users, new ObjectMapper());
    }

    private FormRecordReadFacade facade(Map<String, Object> fields,
                                        Map<String, List<Map<String, Object>>> tables) {
        return (tenantId, formKey, recordId) -> Optional.of(
                new FormRecordReadFacade.FormRecordData(formKey, recordId, fields, tables));
    }

    @SuppressWarnings("unchecked")
    private UserQueryFacade users(Set<Long> activeUsers, Map<Long, Long> leaderByDept) {
        return new UserQueryFacade() {
            @Override
            public Optional<List<Long>> findActiveUserIds(java.util.Collection<Long> ids, Long tenantId) {
                if (!TENANT.equals(tenantId)) {
                    return Optional.empty();
                }
                return Optional.of(ids.stream().filter(activeUsers::contains).distinct().toList());
            }

            @Override
            public Optional<Map<Long, Long>> findDeptLeaderMap(java.util.Collection<Long> deptIds, Long tenantId) {
                if (!TENANT.equals(tenantId)) {
                    return Optional.empty();
                }
                Map<Long, Long> result = new LinkedHashMap<>();
                deptIds.forEach(dept -> {
                    if (leaderByDept.containsKey(dept)) {
                        result.put(dept, leaderByDept.get(dept));
                    }
                });
                return Optional.of(result);
            }

            @Override public Optional<List<com.sw.ck.system.api.user.UserOptionDTO>> searchActiveUsers(String k, int l) { return Optional.empty(); }
            @Override public Optional<Map<Long, String>> getUserDisplayNames(java.util.Collection<Long> ids) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIds(java.util.Collection<Long> ids) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIdsByRoleCodes(java.util.Collection<String> c) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIdsByRoleCodes(java.util.Collection<String> c, Long t) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIdsByDeptLeaders(java.util.Collection<Long> d, Long t) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIdsByPostCodes(java.util.Collection<String> p, Long t) { return Optional.empty(); }
            @Override public Optional<List<Long>> findActiveUserIdsByDeptAndPost(Long d, String p, Long t) { return Optional.empty(); }
        };
    }

    private NodeParticipantContext context(Object strategyValue) {
        return NodeParticipantContext.builder()
                .tenantId(TENANT)
                .processInstanceId("pi-1")
                .nodeKey("approver_1")
                .businessKey("record-1")
                .formKey("p63_form")
                .strategy(ParticipantStrategy.FORM_FIELD)
                .strategyValue(strategyValue)
                .build();
    }

    @Test
    @DisplayName("策略白名单与 value 形状单一权威：合法形状通过，缺字段/非法 objectType 拒绝")
    void formFieldConfigErrorContract() {
        assertThat(ParticipantStrategy.ALL).contains("FORM_FIELD");
        assertThat(ParticipantStrategy.formFieldConfigError(
                Map.of("objectType", "USER", "scope", "MAIN", "field", "owner"))).isEmpty();
        assertThat(ParticipantStrategy.formFieldConfigError(
                Map.of("objectType", "DEPT", "scope", "TABLE",
                        "field", "dept_list", "tableField", "items", "column", "dept_refs"))).isEmpty();
        assertThat(ParticipantStrategy.formFieldConfigError("fixed")).isPresent();
        assertThat(ParticipantStrategy.formFieldConfigError(
                Map.of("objectType", "ROLE", "scope", "MAIN", "field", "x"))).isPresent();
        assertThat(ParticipantStrategy.formFieldConfigError(
                Map.of("objectType", "USER", "scope", "TABLE", "field", "x"))).isPresent();
    }

    @Test
    @DisplayName("USER·MAIN：单选与多选解析为有效人员；含失效人员整组拒绝")
    void userMainField() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("owner", "5");
        fields.put("watchers", List.of("5", "6"));
        FormFieldParticipantResolver resolver = resolver(facade(fields, Map.of()),
                users(Set.of(5L, 6L), Map.of()));

        assertThat(resolver.resolve(context(Map.of("objectType", "USER", "scope", "MAIN",
                "field", "owner"))).orElseThrow()).containsExactly("5");
        assertThat(resolver.resolve(context(Map.of("objectType", "USER", "scope", "MAIN",
                "field", "watchers"))).orElseThrow()).containsExactly("5", "6");

        Map<String, Object> invalidFields = new HashMap<>();
        invalidFields.put("watchers", List.of("5", "999"));
        FormFieldParticipantResolver invalid = resolver(facade(invalidFields, Map.of()),
                users(Set.of(5L, 6L), Map.of()));
        assertThatThrownBy(() -> invalid.resolve(context(Map.of("objectType", "USER",
                "scope", "MAIN", "field", "watchers"))))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getCode())
                        .isEqualTo(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode()));
    }

    @Test
    @DisplayName("DEPT·MAIN：逐部门解析唯一负责人；同负责人多部门按参与人去重；缺负责人拒绝")
    void deptMainFieldResolvesUniqueLeaders() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("dept_list", List.of("7", "8", "9"));
        // 部门 7/8 同负责人（审批/会签参与人去重为一人）；部门 9 缺负责人 → 整组拒绝
        FormFieldParticipantResolver resolver = resolver(facade(fields, Map.of()),
                users(Set.of(5L), Map.of(7L, 5L, 8L, 5L)));

        assertThatThrownBy(() -> resolver.resolve(context(Map.of("objectType", "DEPT",
                "scope", "MAIN", "field", "dept_list"))))
                .isInstanceOf(BaseException.class);

        Map<String, Object> validFields = new HashMap<>();
        validFields.put("dept_list", List.of("7", "8"));
        FormFieldParticipantResolver valid = resolver(facade(validFields, Map.of()),
                users(Set.of(5L), Map.of(7L, 5L, 8L, 5L)));
        assertThat(valid.resolve(context(Map.of("objectType", "DEPT", "scope", "MAIN",
                "field", "dept_list"))).orElseThrow()).containsExactly("5");
    }

    @Test
    @DisplayName("TABLE scope：逐行收集单值与多值列（来源顺序去重）；记录不存在抛明确错误")
    void tableScopeCollection() {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        tables.put("items", List.of(
                Map.of("id", "row-1", "dept_refs", "7"),
                Map.of("id", "row-2", "dept_refs", List.of("8", "7"))));
        FormFieldParticipantResolver resolver = resolver(
                facade(new HashMap<>(Map.of("owner", "5")), tables),
                users(Set.of(5L, 6L), Map.of(7L, 5L, 8L, 6L)));

        assertThat(resolver.resolve(context(Map.of("objectType", "DEPT", "scope", "TABLE",
                "field", "dept_list", "tableField", "items", "column", "dept_refs")))
                .orElseThrow()).containsExactly("5", "6");

        FormRecordReadFacade missing = (tenantId, formKey, recordId) -> Optional.empty();
        FormFieldParticipantResolver missingResolver = resolver(missing, users(Set.of(), Map.of()));
        assertThatThrownBy(() -> missingResolver.resolve(context(Map.of("objectType", "DEPT",
                "scope", "MAIN", "field", "dept_list"))))
                .isInstanceOf(BaseException.class);
    }
}
