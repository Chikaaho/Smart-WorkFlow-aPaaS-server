package com.sw.ck.iot.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.service.IotTopicService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import com.sw.ck.iot.vo.IotTopicVO;

/**
 * Topic 配置控制器。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:topic:manage')")
@RequestMapping("/iot/topics")
public class IotTopicController {

    @Autowired
    private IotTopicService topicService;

    @GetMapping
    public R<List<IotTopicVO>> list() {
        return R.ok(topicService.list() == null ? List.of() : topicService.list().stream().map(IotTopicVO::from).toList());
    }

    @GetMapping("/{id}")
    public R<IotTopicVO> detail(@PathVariable Long id) {
        IotTopic topic = topicService.getById(id);
        if (topic == null) {
            return R.fail(404, "Topic 不存在: id=" + id);
        }
        return R.ok(IotTopicVO.from(topic));
    }

    @PostMapping
    public R<IotTopicVO> create(@RequestBody IotTopic topic) {
        return R.ok(IotTopicVO.from(topicService.create(topic)));
    }

    @PutMapping("/{id}")
    public R<IotTopicVO> update(@PathVariable Long id, @RequestBody IotTopic patch) {
        return R.ok(IotTopicVO.from(topicService.update(id, patch)));
    }

    @PostMapping("/{id}/enabled/{flag}")
    public R<Void> changeEnabled(@PathVariable Long id, @PathVariable boolean flag) {
        topicService.changeEnabled(id, flag);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        topicService.delete(id);
        return R.ok();
    }
}
