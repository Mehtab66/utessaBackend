package com.crm.crm_backend.crm.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;

/**
 * One-time, opt-in loader for local testing: when {@code app.sample-data.seed-org-slug} is set,
 * loads the CRM sample dataset into that practice at startup, acting as the owner given by
 * {@code app.sample-data.seed-owner-email}. Does nothing if the practice already has sample data.
 * Leave both properties unset everywhere except a developer machine.
 */
@Component
public class SampleDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

	private final String slug;
	private final String ownerEmail;
	private final OrganizationRepository organizations;
	private final UserRepository users;
	private final MembershipRepository memberships;
	private final SampleDataService sampleData;

	public SampleDataSeeder(
			@Value("${app.sample-data.seed-org-slug:}") String slug,
			@Value("${app.sample-data.seed-owner-email:}") String ownerEmail,
			OrganizationRepository organizations,
			UserRepository users,
			MembershipRepository memberships,
			SampleDataService sampleData
	) {
		this.slug = slug;
		this.ownerEmail = ownerEmail;
		this.organizations = organizations;
		this.users = users;
		this.memberships = memberships;
		this.sampleData = sampleData;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (slug == null || slug.isBlank()) {
			return;
		}
		Organization org = organizations.findBySlug(slug.trim()).orElse(null);
		if (org == null) {
			log.warn("[sample-data] No practice with slug '{}' — nothing loaded.", slug);
			return;
		}
		User owner = users.findByEmailIgnoreCase(ownerEmail == null ? "" : ownerEmail.trim()).orElse(null);
		Membership membership = owner == null ? null
				: memberships.findByUserIdAndOrganizationId(owner.getId(), org.getId()).orElse(null);
		if (membership == null) {
			log.warn("[sample-data] '{}' is not a member of practice '{}' — nothing loaded.", ownerEmail, slug);
			return;
		}
		UserPrincipal principal = new UserPrincipal(owner.getId(), org.getId(), owner.getEmail(),
				membership.getRole().name(), owner.isEmailVerified(), false);
		TenantContext.set(org.getId(), owner.getId(), membership.getRole().name());
		try {
			if (sampleData.status(principal).loaded()) {
				log.info("[sample-data] Practice '{}' already has sample data — skipped.", slug);
				return;
			}
			SampleDataService.Status status = sampleData.load(principal);
			log.info("[sample-data] Loaded into practice '{}' ({}) as {}: {}", org.getName(), slug, owner.getEmail(), status.counts());
		} catch (Exception ex) {
			log.error("[sample-data] Could not load sample data into '{}': {}", slug, ex.getMessage(), ex);
		} finally {
			TenantContext.clear();
		}
	}
}
