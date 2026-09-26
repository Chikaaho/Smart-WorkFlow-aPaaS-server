package com.sw.ck.bpm.engine.facade;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link BpmDeployFacadeImpl} 单元测试。
 * <p>
 * 纯 Mockito + {@link MockitoExtension}，不装载 Spring 上下文。
 * 验证 {@link BpmDeployFacadeImpl#getBpmnXml(String)} 正常/异常路径。
 * </p>
 */
@DisplayName("BpmDeployFacadeImpl 单元测试")
@ExtendWith(MockitoExtension.class)
class BpmDeployFacadeImplTest {

    @Mock
    private RepositoryService repositoryService;

    @InjectMocks
    private BpmDeployFacadeImpl facade;

    // ==================== getBpmnXml ====================

    @Nested
    @DisplayName("getBpmnXml 方法")
    class GetBpmnXmlTests {

        @Test
        @DisplayName("正常：查询到 ProcessDefinition → 返回解码后的 XML 字符串")
        void getBpmnXml_shouldReturnXmlString() {
            // 模拟 ProcessDefinitionQuery 链
            ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
            when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
            when(query.processDefinitionId("proc-def-1")).thenReturn(query);

            ProcessDefinition processDef = mock(ProcessDefinition.class);
            when(processDef.getDeploymentId()).thenReturn("deploy-1");
            when(processDef.getResourceName()).thenReturn("process.bpmn20.xml");
            when(query.singleResult()).thenReturn(processDef);

            // 模拟 getResourceAsStream 返回
            String expectedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><definitions id=\"def1\"/>";
            InputStream inputStream = new ByteArrayInputStream(expectedXml.getBytes(StandardCharsets.UTF_8));
            when(repositoryService.getResourceAsStream("deploy-1", "process.bpmn20.xml"))
                    .thenReturn(inputStream);

            String result = facade.getBpmnXml("proc-def-1").orElseThrow();

            assertThat(result).isEqualTo(expectedXml);
            verify(repositoryService).createProcessDefinitionQuery();
            verify(query).processDefinitionId("proc-def-1");
            verify(repositoryService).getResourceAsStream("deploy-1", "process.bpmn20.xml");
        }

        @Test
        @DisplayName("目标缺失：查询不到 ProcessDefinition → 返回 empty（原 IllegalStateException 路径）")
        void getBpmnXml_processDefNotFound_shouldReturnEmpty() {
            ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
            when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
            when(query.processDefinitionId("proc-def-missing")).thenReturn(query);
            when(query.singleResult()).thenReturn(null);

            assertThat(facade.getBpmnXml("proc-def-missing")).isEmpty();

            verify(repositoryService, never()).getResourceAsStream(anyString(), anyString());
        }
    }
}
