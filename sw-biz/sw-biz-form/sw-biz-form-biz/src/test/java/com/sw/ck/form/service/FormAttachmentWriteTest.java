package com.sw.ck.form.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.storage.api.StorageFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FormAttachmentWriteTest {
    private final FormConfigMapper configs = mock(FormConfigMapper.class);
    private final StorageFacade storage = mock(StorageFacade.class);
    private FormFieldEnrichmentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ObjectProvider<StorageFacade> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        ObjectMapper mapper = new ObjectMapper();
        service = new FormFieldEnrichmentService(configs, mapper, null,
                new FieldPermissionService(mapper), null, null, null, null, null, provider);
        FormConfigEntity config = new FormConfigEntity();
        config.setDefinition("""
                {"fields":[{"name":"files","type":"ATTACHMENT","label":"附件"},
                {"name":"lines","type":"TABLE","subFields":[
                {"name":"photo","type":"IMAGE","label":"图片"}]}]}
                """);
        when(configs.selectList(any())).thenReturn(List.of(config));
        when(storage.exists("live")).thenReturn(Optional.of(true));
        when(storage.exists("missing")).thenReturn(Optional.of(false));
    }

    @Test
    void existingStringKeysRemainValid() {
        Map<String, Object> payload = new HashMap<>(Map.of("files", List.of("live")));
        service.enrichForWrite("form", payload);
        assertThat(payload.get("files")).isEqualTo(List.of("live"));
        verify(storage).exists("live");
    }

    @Test
    void uploadedObjectIsCheckedByKeyAndKeepsItsFilename() {
        var file = Map.of("storageKey", "live", "name", "演练方案.pdf");
        Map<String, Object> payload = new HashMap<>(Map.of("files", List.of(file)));
        service.enrichForWrite("form", payload);
        assertThat(payload.get("files")).isEqualTo(List.of(file));
        verify(storage).exists("live");
    }

    @Test
    void missingObjectFileIsRejectedByStorageCheck() {
        var payload = new HashMap<String, Object>(Map.of("files",
                List.of(Map.of("storageKey", "missing", "name", "plan.pdf"))));
        assertThatThrownBy(() -> service.enrichForWrite("form", payload))
                .isInstanceOf(BaseException.class).hasMessageContaining("不存在或不可见");
        verify(storage).exists("missing");
    }

    @Test
    void malformedObjectCannotUseItsNameAsAStorageKey() {
        for (var file : List.of(Map.of("name", "live"), Map.of("storageKey", 7),
                Map.of("storageKey", " "))) {
            assertThatThrownBy(() -> service.enrichForWrite("form",
                    new HashMap<>(Map.of("files", List.of(file)))))
                    .isInstanceOf(BaseException.class);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void subtableImageHasTheSameExistenceBoundary() {
        var row = new HashMap<String, Object>(Map.of("photo",
                List.of(Map.of("storageKey", "missing", "name", "photo.png"))));
        assertThatThrownBy(() -> service.enrichForWrite("form",
                new HashMap<>(Map.of("lines", List.of(row)))))
                .isInstanceOf(BaseException.class).hasMessageContaining("不存在或不可见");
        verify(storage).exists("missing");
    }
}
