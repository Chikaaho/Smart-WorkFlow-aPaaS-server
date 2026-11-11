package com.sw.ck.system.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** R5 回归：关键字 LIKE 模式在应用侧生成（避免 PG 参数类型推断失败）。 */
@DisplayName("用户分页查询关键字模式")
class UserPageQueryTest {

    @Test
    @DisplayName("关键字生成 %keyword%，空值与空白为 null")
    void keywordLikePattern() {
        UserPageQuery query = new UserPageQuery();
        assertThat(query.getKeywordLike()).isNull();

        query.setKeyword("  ");
        assertThat(query.getKeywordLike()).isNull();

        query.setKeyword(" zhang ");
        assertThat(query.getKeywordLike()).isEqualTo("%zhang%");
    }
}
