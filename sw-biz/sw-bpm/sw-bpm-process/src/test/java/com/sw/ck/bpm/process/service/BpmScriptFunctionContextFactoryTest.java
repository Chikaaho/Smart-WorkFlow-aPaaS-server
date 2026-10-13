package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.ScriptFunctionContext;
import com.sw.ck.system.api.dept.DeptDirectoryRow;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BF20261013-021：脚本函数上下文工厂用例（引用扫描、目录映射、实例变量可序列化过滤）。
 */
@DisplayName("BF021 脚本函数上下文工厂测试")
class BpmScriptFunctionContextFactoryTest {

    private final DeptQueryFacade deptQueryFacade = mock(DeptQueryFacade.class);
    private final BpmTaskFacade bpmTaskFacade = mock(BpmTaskFacade.class);
    private final BpmScriptFunctionContextFactory factory =
            new BpmScriptFunctionContextFactory(deptQueryFacade, bpmTaskFacade, new ObjectMapper());

    @Test
    @DisplayName("未引用组织/变量函数：空上下文且零查询（既有脚本零额外开销）")
    void returnsEmptyWithoutFunctionTokens() {
        ScriptFunctionContext ctx = factory.build("return 1;", "pi-1", 9L);
        assertThat(ctx.isEmpty()).isTrue();
        verify(deptQueryFacade, never()).listDeptDirectory(anyLong());
        verify(bpmTaskFacade, never()).getVariables("pi-1");
    }

    @Test
    @DisplayName("引用组织函数：构建部门目录（父级与有效负责人映射）")
    void buildsDeptDirectoryWhenOrgFunctionReferenced() {
        when(deptQueryFacade.listDeptDirectory(9L)).thenReturn(Optional.of(List.of(
                new DeptDirectoryRow(1L, 0L, null),
                new DeptDirectoryRow(2L, 1L, 100L))));
        ScriptFunctionContext ctx = factory.build("#获取部门负责人(2); return 1;", "pi-1", 9L);
        assertThat(ctx.deptParentMap()).containsEntry(2L, 1L);
        assertThat(ctx.deptLeaderMap()).containsEntry(2L, 100L);
        assertThat(ctx.deptLeaderMap()).doesNotContainKey(1L);
    }

    @Test
    @DisplayName("引用流程变量函数：实例变量快照过滤不可序列化值")
    void buildsInstanceVariablesJsonSafe() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("count", 5L);
        raw.put("obj", new Object() {
            @SuppressWarnings("unused")
            public Object self() {
                return this;
            }
        });
        when(bpmTaskFacade.getVariables("pi-1")).thenReturn(Optional.of(raw));
        ScriptFunctionContext ctx = factory.build("return #获取流程变量(count);", "pi-1", 9L);
        assertThat(ctx.instanceVariables()).containsEntry("count", 5L);
        assertThat(ctx.instanceVariables()).doesNotContainKey("obj");
    }
}
