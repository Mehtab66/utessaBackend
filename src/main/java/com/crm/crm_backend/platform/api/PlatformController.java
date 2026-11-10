package com.crm.crm_backend.platform.api;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.platform.api.dto.PlatformDtos.OrganizationRow;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.OverviewResponse;
import com.crm.crm_backend.platform.api.dto.PlatformDtos.PlatformUserRow;
import com.crm.crm_backend.platform.application.PlatformService;
import com.crm.crm_backend.shared.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

	private final PlatformService platformService;

	public PlatformController(PlatformService platformService) {
		this.platformService = platformService;
	}

	@GetMapping("/overview")
	public OverviewResponse overview(@AuthenticationPrincipal UserPrincipal principal) {
		return platformService.overview(principal);
	}

	@GetMapping("/organizations")
	public List<OrganizationRow> organizations(@AuthenticationPrincipal UserPrincipal principal) {
		return platformService.organizations(principal);
	}

	@GetMapping("/staff")
	public List<PlatformUserRow> staff(@AuthenticationPrincipal UserPrincipal principal) {
		return platformService.platformUsers(principal);
	}
}
