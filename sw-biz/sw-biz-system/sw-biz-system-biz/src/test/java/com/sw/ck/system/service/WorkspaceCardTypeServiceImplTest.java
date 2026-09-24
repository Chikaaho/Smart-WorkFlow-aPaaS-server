package com.sw.ck.system.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.mapper.SysWorkspaceCardTypeMapper;
import com.sw.ck.system.service.impl.WorkspaceCardTypeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceCardTypeServiceImplTest {

    private SysWorkspaceCardTypeMapper mapper;
    private WorkspaceCardTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(SysWorkspaceCardTypeMapper.class);
        service = new WorkspaceCardTypeServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void create_shouldRejectExecutableRendererPath() {
        SysWorkspaceCardType type = validType();
        type.setRendererKey("../../arbitrary-component");

        assertThatThrownBy(() -> service.create(type))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不支持的卡片渲染器");
    }

    @Test
    void create_shouldRejectNonObjectMetadata() {
        SysWorkspaceCardType type = validType();
        type.setMetadataJson("[]");

        assertThatThrownBy(() -> service.create(type))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("JSON 对象");
    }

    private SysWorkspaceCardType validType() {
        SysWorkspaceCardType type = new SysWorkspaceCardType();
        type.setTypeCode("custom_card");
        type.setDisplayName("自定义卡片");
        type.setRendererKey("todo");
        type.setMetadataJson("{}");
        type.setDefaultSpan(1);
        type.setDefaultOrder(1);
        type.setStatus(0);
        return type;
    }
}
