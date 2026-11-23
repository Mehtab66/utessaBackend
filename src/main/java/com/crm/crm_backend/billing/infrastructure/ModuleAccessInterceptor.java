package com.crm.crm_backend.billing.infrastructure;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.crm.crm_backend.billing.application.EntitlementService;
import com.crm.crm_backend.billing.domain.BillingModule;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Server-side module gate: every request to a paid module's API is checked against the practice's
 * entitlements (402 when not allowed). Billing, workspace, team, organization, sign-in and
 * dashboard routes aren't module routes, so a practice can always reach them — e.g. to pay.
 */
@Component
public class ModuleAccessInterceptor implements HandlerInterceptor {

	/** API path prefix → module. Order matters only for readability; prefixes don't overlap. */
	static final List<Map.Entry<String, BillingModule>> ROUTES = List.of(
			Map.entry("/api/v1/crm/", BillingModule.CRM),
			Map.entry("/api/v1/staff", BillingModule.OPERATIONS),
			Map.entry("/api/v1/vendors", BillingModule.OPERATIONS),
			Map.entry("/api/v1/providers", BillingModule.OPERATIONS),
			Map.entry("/api/v1/operations", BillingModule.OPERATIONS),
			Map.entry("/api/v1/marketing", BillingModule.MARKETING),
			Map.entry("/api/v1/timeclock", BillingModule.TIME_CLOCK));

	private final EntitlementService entitlements;

	public ModuleAccessInterceptor(EntitlementService entitlements) {
		this.entitlements = entitlements;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		BillingModule module = moduleFor(request.getRequestURI());
		if (module == null) {
			return true;
		}
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal p)
				|| p.superAdmin() || p.organizationId() == null) {
			// Public callbacks and platform staff: RoleGuard / security config decide.
			return true;
		}
		entitlements.requireModule(p.organizationId(), module);
		return true;
	}

	static BillingModule moduleFor(String path) {
		if (path == null) {
			return null;
		}
		for (Map.Entry<String, BillingModule> r : ROUTES) {
			String prefix = r.getKey();
			if (path.startsWith(prefix) || (!prefix.endsWith("/") && path.equals(prefix))) {
				// "/api/v1/staff" must not match "/api/v1/staffing"
				if (prefix.endsWith("/") || path.length() == prefix.length() || path.charAt(prefix.length()) == '/') {
					return r.getValue();
				}
			}
		}
		return null;
	}
}
