package com.sw.ck.form.service;

import java.util.Map;
import java.util.Set;

/**
 * 目标租户既有事实（预检与确认共用；预检后目标可能变化，确认时重新收集）。
 */
record TargetFacts(Set<String> existingFormKeys,
                   boolean appNameTaken,
                   Map<String, String> dictActions,
                   Set<String> existingQueryKeyVersions,
                   Map<String, Boolean> roleCodes,
                   Map<String, Boolean> postCodes) {

    boolean opinionKeyTaken(String key) {
        return existingFormKeys.contains(key);
    }
}
