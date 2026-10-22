package com.sw.ck.bpm.engine.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.bpm.api.dto.ExternalDatasourceRequest;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import com.sw.ck.bpm.engine.service.ExternalDatasourceService;
import com.sw.ck.common.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalDatasourceControllerTest {

    private final ExternalDatasourceService service = mock(ExternalDatasourceService.class);
    private final ExternalDatasourceController controller =
            new ExternalDatasourceController(service, mock(SqlExecutor.class));

    @Test
    void listReturnsCurrentTenantRecordsWithoutPasswordCipher() {
        ExternalDatasource row = validEntity();
        Page<ExternalDatasource> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(row));
        when(service.page(any(), any())).thenReturn(page);

        var result = controller.page(1, 20, null, null).getData();
        assertThat(result.getRecords()).containsExactly(row);
        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(row.getPasswordCipher()).isNull();
    }

    @Test
    void createRejectsSecretBearingOrMismatchedJdbcUrl() {
        ExternalDatasourceRequest request = request();
        request.setJdbcUrl("jdbc:postgresql://db/app?user=admin&password=secret");
        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(BaseException.class);

        request.setType("mysql");
        request.setDriverClass("org.postgresql.Driver");
        request.setJdbcUrl("jdbc:postgresql://db/app");
        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(BaseException.class);
    }

    @Test
    void validDatasourceUsesEncryptedServicePath() {
        ExternalDatasourceRequest request = request();
        _whenCreateReturnsId();

        assertThat(controller.create(request).getCode()).isZero();
        verify(service).saveWithEncryption(any(ExternalDatasource.class), org.mockito.ArgumentMatchers.eq("secret"));
    }

    private void _whenCreateReturnsId() {
        org.mockito.Mockito.doAnswer(invocation -> {
            ExternalDatasource entity = invocation.getArgument(0);
            entity.setId(91L);
            return null;
        }).when(service).saveWithEncryption(any(ExternalDatasource.class), any());
    }

    private static ExternalDatasourceRequest request() {
        ExternalDatasourceRequest request = new ExternalDatasourceRequest();
        request.setName("报表库");
        request.setType("postgresql");
        request.setDriverClass("org.postgresql.Driver");
        request.setJdbcUrl("jdbc:postgresql://db/report");
        request.setUsername("report_user");
        request.setPassword("secret");
        return request;
    }

    private static ExternalDatasource validEntity() {
        ExternalDatasource row = new ExternalDatasource();
        row.setId(91L);
        row.setName("报表库");
        row.setType("postgresql");
        row.setJdbcUrl("jdbc:postgresql://db/report");
        row.setUsername("report_user");
        row.setEnabled(1);
        row.setReadOnly(1);
        return row;
    }
}
