package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.UrgeReqDTO;
import com.sw.ck.bpm.process.dto.UrgeRespDTO;
import com.sw.ck.bpm.process.service.BpmUrgeService;
import com.sw.ck.common.response.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 催办控制器（v0.0.2 OA 个人办理）。 */
@RestController
@RequestMapping("/workflow/my/instances")
public class BpmUrgeController {

    private final BpmUrgeService urgeService;

    public BpmUrgeController(BpmUrgeService urgeService) {
        this.urgeService = urgeService;
    }

    /**
     * 发起人对本人运行中实例催办当前实际待办人（10 分钟冷却，重复请求返回冷却信息）。
     * <p>body 可空：携带可选催办留言（最长 200 字），旧无 body 调用保持原语义。</p>
     */
    @PostMapping("/{id}/urge")
    public R<UrgeRespDTO> urge(@PathVariable Long id, @RequestBody(required = false) UrgeReqDTO req) {
        return R.ok(urgeService.urge(id, req == null ? null : req.getMessage()));
    }
}
