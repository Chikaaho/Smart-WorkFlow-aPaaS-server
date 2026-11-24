package com.sw.ck.bpm.process.service;

import com.sw.ck.common.value.DynamicData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** E4-1：审批意见字段展示值（DynamicData 解包、人员/多选/日期/数字可读化）。 */
class ApprovalHistoryEnricherTest {

    @Test
    void unwrapsRadioText() {
        assertEquals("属实", ApprovalHistoryEnricher.opinionDisplayValue(
                "RADIO", DynamicData.of("属实"), Map.of()));
    }

    @Test
    void unwrapsCheckboxList() {
        assertEquals("疏导、增派安保", ApprovalHistoryEnricher.opinionDisplayValue(
                "CHECKBOX", DynamicData.of(List.of("疏导", "增派安保")), Map.of()));
    }

    @Test
    void resolvesUserNames() {
        assertEquals("赵磊", ApprovalHistoryEnricher.opinionDisplayValue(
                "USER", DynamicData.of(List.of("9")), Map.of(9L, "赵磊")));
    }

    @Test
    void formatsDateToMinute() {
        assertEquals("2026-11-07 21:58", ApprovalHistoryEnricher.opinionDisplayValue(
                "DATE", DynamicData.of("2026-11-07 21:58:00"), Map.of()));
    }

    @Test
    void formatsNumber() {
        assertEquals("9", ApprovalHistoryEnricher.opinionDisplayValue(
                "NUMBER", DynamicData.of(9), Map.of()));
    }

    @Test
    void emptyForNull() {
        assertEquals("", ApprovalHistoryEnricher.opinionDisplayValue(
                "RADIO", null, Map.of()));
    }
}
