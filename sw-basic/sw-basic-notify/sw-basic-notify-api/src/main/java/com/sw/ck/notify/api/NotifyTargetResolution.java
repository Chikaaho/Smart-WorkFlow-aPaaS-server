package com.sw.ck.notify.api;

import lombok.Value;

/** PHONE 权威解析结果；只允许返回状态与摘要，不返回联系方式明文。 */
@Value
public class NotifyTargetResolution {
    String status;
    String source;
    String valueDigest;

    public static NotifyTargetResolution of(String status, String source, String valueDigest) {
        return new NotifyTargetResolution(status, source, valueDigest);
    }
}
