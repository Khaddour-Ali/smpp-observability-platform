package com.example.smpp.admin;

import com.example.smpp.application.dto.ThroughputResponse;
import com.example.smpp.application.service.AdminMessageQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminThroughputController {

    private final AdminMessageQueryService adminMessageQueryService;

    public AdminThroughputController(AdminMessageQueryService adminMessageQueryService) {
        this.adminMessageQueryService = adminMessageQueryService;
    }

    @GetMapping("/throughput")
    public ThroughputResponse throughput() {
        return adminMessageQueryService.getThroughputLastWindow();
    }
}
