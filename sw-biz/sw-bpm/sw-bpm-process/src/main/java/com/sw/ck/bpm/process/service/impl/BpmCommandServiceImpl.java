package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.service.BpmCommandService;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 流程业务命令受理 Service 实现。
 */
@Service
public class BpmCommandServiceImpl extends BaseServiceImpl<BpmCommandMapper, BpmCommand>
        implements BpmCommandService {
}
