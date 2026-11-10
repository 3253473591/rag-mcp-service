package com.lqx.ragmcp.api;

import com.lqx.ragmcp.api.dto.PageSearchRequest;
import com.lqx.ragmcp.api.dto.PageSearchResponse;
import com.lqx.ragmcp.page.PageSearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Page-granularity retrieval endpoint (Phase 10). Additive: {@code POST /api/v1/search} is
 * unchanged and remains the chunk route.
 */
@RestController
@RequestMapping("/api/v1/pages")
public class PageController {

    private final PageSearchService pageSearchService;

    public PageController(PageSearchService pageSearchService) {
        this.pageSearchService = pageSearchService;
    }

    @PostMapping("/search")
    public PageSearchResponse search(@RequestBody(required = false) PageSearchRequest request) {
        return pageSearchService.search(request);
    }
}
