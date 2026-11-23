package com.crm.crm_backend.billing.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.domain.OrgRole;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Who may see and change billing, and the organization facts billing needs. The organization is
 * always the one on the caller's session — never an id from the request.
 */
@Component
public class BillingAccess {

	/** Permission key (RoleGuard): OWNER and ADMIN only. */
	public static final String PERM_BILLING = "billing";

	private final OrganizationRepository organizations;
	private final MembershipRepository memberships;
	private final UserRepository users;

	public BillingAccess(OrganizationRepository organizations, MembershipRepository memberships, UserRepository users) {
		this.organizations = organizations;
		this.memberships = memberships;
		this.users = users;
	}

	/** Owner/admin of a practice: may view and change billing. Returns the organization id. */
	public UUID requireBillingAdmin(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, PERM_BILLING);
		return principal.organizationId();
	}

	/** Any member of a practice (e.g. to see prices). Returns the organization id. */
	public UUID requireMember(UserPrincipal principal) {
		RoleGuard.requireOrgAccess(principal, "dash");
		return principal.organizationId();
	}

	public Organization organization(UUID orgId) {
		return organizations.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization not found."));
	}

	/** The practice owner (billing contact by default). */
	public Optional<User> owner(UUID orgId) {
		return memberships.findByOrganizationId(orgId).stream()
				.filter(m -> m.getRole() == OrgRole.OWNER)
				.map(Membership::getUserId)
				.findFirst()
				.flatMap(users::findById);
	}

	/**
	 * Team members that need a paid seat: everyone except the owner (the owner is free).
	 */
	public int billableMembers(UUID orgId) {
		return (int) memberships.findByOrganizationId(orgId).stream()
				.filter(m -> m.getRole() != OrgRole.OWNER)
				.count();
	}
}
