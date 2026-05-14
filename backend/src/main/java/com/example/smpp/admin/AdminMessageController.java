package com.example.smpp.admin;

import com.example.smpp.application.dto.FailedMessageResponse;
import com.example.smpp.application.dto.MessageStatsResponse;
import com.example.smpp.application.service.AdminMessageQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/messages")
public class AdminMessageController {

    private final AdminMessageQueryService adminMessageQueryService;

    public AdminMessageController(AdminMessageQueryService adminMessageQueryService) {
        this.adminMessageQueryService = adminMessageQueryService;
    }

    @GetMapping("/stats")
    public MessageStatsResponse stats() {
        return adminMessageQueryService.getMessageStats();
    }

    @GetMapping("/failed")
    public List<FailedMessageResponse> failed(
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        return adminMessageQueryService.getFailedMessages(limit);
    }
}
