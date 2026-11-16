// package com.sw.ck.form.dto;

// import com.fasterxml.jackson.core.type.TypeReference;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import org.junit.jupiter.api.Test;

// import java.util.Map;

// import static org.assertj.core.api.Assertions.assertThat;

// /** 动态表单记录 DTO 的 JSON 形状：字段直接展开，不出现 values 包装。 */
// class FormDataRecordDTOTest {

//     @Test
//     void shouldSerializeDynamicFieldsFlat() {
//         FormDataRecordVO dto = new FormDataRecordVO(Map.of(
//                 "title", "示例",
//                 "score", 10));

//         Map<String, Object> json = new ObjectMapper()
//                 .convertValue(dto, new TypeReference<Map<String, Object>>() { });

//         assertThat(json).containsEntry("title", "示例")
//                 .containsEntry("score", 10)
//                 .doesNotContainKey("values");
//     }
// }
