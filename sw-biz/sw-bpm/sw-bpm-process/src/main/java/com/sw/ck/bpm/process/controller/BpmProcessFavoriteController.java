package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.FavoriteItemDTO;
import com.sw.ck.bpm.process.service.BpmProcessFavoriteService;
import com.sw.ck.common.response.R;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 流程收藏 / 常用 / 最近使用（V012-BUG-009）。登录即可用；数据用户级租户内隔离。
 */
@RestController
@RequestMapping("/workflow/favorites")
public class BpmProcessFavoriteController {

    private final BpmProcessFavoriteService favoriteService;

    public BpmProcessFavoriteController(BpmProcessFavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /** 当前用户收藏列表（最近收藏在前）。 */
    @GetMapping
    public R<List<FavoriteItemDTO>> myFavorites() {
        return R.ok(favoriteService.listMyFavorites());
    }

    /** 收藏流程（body.name 为展示名快照，可缺省）。 */
    @PostMapping("/{processKey}")
    public R<Void> favorite(@PathVariable String processKey,
                            @RequestBody(required = false) MapBody body) {
        favoriteService.favorite(processKey, body == null ? null : body.getName());
        return R.ok(null);
    }

    /** 取消收藏（幂等）。 */
    @DeleteMapping("/{processKey}")
    public R<Void> unfavorite(@PathVariable String processKey) {
        favoriteService.unfavorite(processKey);
        return R.ok(null);
    }

    /** 常用流程：租户内发起次数总量 TopN（默认 5）。 */
    @GetMapping("/frequently-started")
    public R<List<FavoriteItemDTO>> frequentlyStarted(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(favoriteService.frequentlyStarted(limit));
    }

    /** 最近使用：本人最近发起去重 TopN（默认 5）。 */
    @GetMapping("/recently-used")
    public R<List<FavoriteItemDTO>> recentlyUsed(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(favoriteService.recentlyUsed(limit));
    }

    /** 内嵌请求体。 */
    public static class MapBody {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
