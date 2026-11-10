package com.crm.crm_backend.organization.domain;

/**
 * Practice (organization) access roles on memberships.role.
 * Platform operators use users.platform_role = SUPERADMIN — not an org role.
 */
public enum OrgRole {
	OWNER,
	ADMIN,
	MANAGER,
	FRONT,
	CLINICAL,
	PROVIDER,
	BILLING,
	OUTREACH,
	HR,
	MEMBER
}
