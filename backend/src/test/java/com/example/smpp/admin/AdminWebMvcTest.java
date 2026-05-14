package com.example.smpp.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.smpp.application.dto.MessageStatsResponse;
import com.example.smpp.application.dto.ThroughputResponse;
import com.example.smpp.application.service.AdminMessageQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AdminMessageController.class, AdminThroughputController.class})
class AdminWebMvcTest {

    @Autowired MockMvc mockMvc;

    @MockBean AdminMessageQueryService adminMessageQueryService;

    @Test
    void stats_returnsJson() throws Exception {
        Instant now = Instant.parse("2026-05-11T10:00:00Z");
        when(adminMessageQueryService.getMessageStats())
                .thenReturn(new MessageStatsResponse(1, 0, 2, 0, 3, now));

        mockMvc.perform(get("/admin/messages/stats").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.queued").value(0))
                .andExpect(jsonPath("$.processed").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void failed_passesLimitQueryParam() throws Exception {
        when(adminMessageQueryService.getFailedMessages(10)).thenReturn(List.of());

        mockMvc.perform(
                        get("/admin/messages/failed")
                                .param("limit", "10")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void throughput_returnsJson() throws Exception {
        Instant now = Instant.parse("2026-05-11T10:01:00Z");
        Instant since = Instant.parse("2026-05-11T10:00:00Z");
        when(adminMessageQueryService.getThroughputLastWindow())
                .thenReturn(new ThroughputResponse(60, since, now, 5L, 5.0 / 60.0));

        mockMvc.perform(get("/admin/throughput").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.processedCount").value(5))
                .andExpect(jsonPath("$.messagesPerSecond").exists());
    }
}
