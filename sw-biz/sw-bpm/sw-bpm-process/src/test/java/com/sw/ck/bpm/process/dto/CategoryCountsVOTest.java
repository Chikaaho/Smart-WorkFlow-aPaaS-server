// package com.sw.ck.bpm.process.dto;

// import com.fasterxml.jackson.core.type.TypeReference;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import org.junit.jupiter.api.Test;

// import java.util.LinkedHashMap;
// import java.util.Map;

// import static org.assertj.core.api.Assertions.assertThat;

// /** 分类计数 DTO 的 JSON 形状：直接展开为字典，不出现 counts 包装。 */
// class CategoryCountsDTOTest {

//     @Test
//     void shouldSerializeAsFlatDictionary() throws Exception {
//         Map<Long, Long> counts = new LinkedHashMap<>();
//         counts.put(0L, 1L);
//         counts.put(5L, 2L);

//         String json = new ObjectMapper().writeValueAsString(new CategoryCountsVO(counts));
//         Map<String, Object> parsed = new ObjectMapper()
//                 .readValue(json, new TypeReference<Map<String, Object>>() { });

//         assertThat(parsed).containsEntry("0", 1)
//                 .containsEntry("5", 2)
//                 .doesNotContainKey("counts");
//     }
// }
