package com.crm.crm_backend.operations.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.operations.api.dto.DashboardSummaryResponse;
import com.crm.crm_backend.operations.application.DashboardService;
import com.crm.crm_backend.shared.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

	private final DashboardService dashboardService;

	public DashboardController(DashboardService dashboardService) {
		this.dashboardService = dashboardService;
	}

	@GetMapping("/summary")
	public DashboardSummaryResponse summary(@AuthenticationPrincipal UserPrincipal principal) {
		return dashboardService.summary(principal);
	}
}
