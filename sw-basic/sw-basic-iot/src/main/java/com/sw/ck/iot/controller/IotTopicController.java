package com.sw.ck.iot.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.service.IotTopicService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * Topic 配置控制器。
 */
@RestController
@PreAuthorize("@ss.hasPermi('iot:topic:manage')")
@RequestMapping("/iot/topics")
public class IotTopicController {

    private final IotTopicService topicService;

    public IotTopicController(IotTopicService topicService) {
        this.topicService = topicService;
    }

    @GetMapping
    public R<List<IotTopic>> list() {
        return R.ok(topicService.list());
    }

    @GetMapping("/{id}")
    public R<IotTopic> detail(@PathVariable Long id) {
        IotTopic topic = topicService.getById(id);
        if (topic == null) {
            return R.fail(404, "Topic 不存在: id=" + id);
        }
        return R.ok(topic);
    }

    @PostMapping
    public R<IotTopic> create(@RequestBody IotTopic topic) {
        return R.ok(topicService.create(topic));
    }

    @PutMapping("/{id}")
    public R<IotTopic> update(@PathVariable Long id, @RequestBody IotTopic patch) {
        return R.ok(topicService.update(id, patch));
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
