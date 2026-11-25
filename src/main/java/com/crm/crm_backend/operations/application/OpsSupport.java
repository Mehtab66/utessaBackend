package com.crm.crm_backend.operations.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

/** Shared helpers for the Practice Operations services: tenant, permissions, names and history. */
@Component
public class OpsSupport {

	/** Providers, credentialing, payers and privileges. */
	public static final String CREDS = "creds";
	/** Staff records, HR documents. */
	public static final String STAFF = "staff";
	/** Vendors, purchasing and inventory administration. */
	public static final String VENDORS = "vendors";
	/** Everyone signed in to the practice. */
	public static final String DASH = "dash";
	/** Practice settings. */
	public static final String SETTINGS = "settings";

	private final UserRepository userRepository;
	private final StaffMemberRepository staffRepository;

	public OpsSupport(UserRepository userRepository, StaffMemberRepository staffRepository) {
		this.userRepository = userRepository;
		this.staffRepository = staffRepository;
	}

	public UUID orgId() {
		return TenantContext.requireOrganizationId();
	}

	public void require(UserPrincipal principal, String permission) {
		RoleGuard.requireOrgAccess(principal, permission);
	}

	/** Passes when the role holds at least one of the permissions. */
	public void requireAny(UserPrincipal principal, String... permissions) {
		ApiException last = null;
		for (String permission : permissions) {
			try {
				RoleGuard.requireOrgAccess(principal, permission);
				return;
			} catch (ApiException ex) {
				last = ex;
			}
		}
		if (last != null) {
			throw last;
		}
	}

	public boolean has(UserPrincipal principal, String permission) {
		try {
			RoleGuard.requireOrgAccess(principal, permission);
			return true;
		} catch (ApiException ex) {
			return false;
		}
	}

	public String actorName(UserPrincipal principal) {
		if (principal == null) {
			return "System";
		}
		return userRepository.findById(principal.userId())
				.map(User::getFullName)
				.orElse(principal.email());
	}

	public StaffMember staff(UUID staffId) {
		if (staffId == null) {
			throw ApiException.badRequest("Pick a person.");
		}
		return staffRepository.findByIdAndOrganizationId(staffId, orgId())
				.orElseThrow(() -> ApiException.notFound("Person not found in this practice."));
	}

	public static String displayName(StaffMember m) {
		return ((m.getFirstName() == null ? "" : m.getFirstName()) + " " + (m.getLastName() == null ? "" : m.getLastName())).trim();
	}

	public static String clean(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	public static Map<String, Object> historyEntry(String status, String note, String by) {
		Map<String, Object> h = new LinkedHashMap<>();
		h.put("date", LocalDate.now().toString());
		h.put("status", status);
		h.put("note", note == null ? "" : note);
		h.put("by", by);
		return h;
	}

	public static List<Map<String, Object>> copy(List<Map<String, Object>> list) {
		return list == null ? new ArrayList<>() : new ArrayList<>(list);
	}
}
