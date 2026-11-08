package com.lqx.ragmcp.api;

import com.lqx.ragmcp.api.error.GlobalExceptionHandler;
import com.lqx.ragmcp.api.error.TraceIdFilter;
import com.lqx.ragmcp.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retrieval database failures must surface as 503, never as an empty 200 result. */
class SearchControllerErrorTest {

    @Test
    void databaseFailureMapsTo503DbUnavailable() throws Exception {
        SearchService searchService = mock(SearchService.class);
        when(searchService.search(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(searchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();

        mockMvc.perform(post("/api/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ERR-DB_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.results").doesNotExist());
    }
}
