package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.entity.BpmDraft;
import com.sw.ck.bpm.process.mapper.BpmDraftMapper;
import com.sw.ck.bpm.process.service.BpmDraftService;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 业务发起草稿 Service 实现。
 */
@Service
public class BpmDraftServiceImpl extends BaseServiceImpl<BpmDraftMapper, BpmDraft>
        implements BpmDraftService {
}
