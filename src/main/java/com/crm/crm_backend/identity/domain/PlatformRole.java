package com.crm.crm_backend.identity.domain;

/**
 * System-wide operator role. Independent of organization memberships.
 * Only seedable via scripts/create-superadmin.sh — never via signup.
 */
public enum PlatformRole {
	NONE,
	SUPERADMIN
}
