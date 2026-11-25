package com.crm.crm_backend.timeclock.application;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.domain.EntryAudit;
import com.crm.crm_backend.timeclock.domain.TimeEntry;
import com.crm.crm_backend.timeclock.infrastructure.EntryAuditRepository;

/** Writes the audit trail for time entries (who changed what, when and why). */
@Component
public class EntryAuditor {

	private final EntryAuditRepository repo;
	private final TcSupport support;

	public EntryAuditor(EntryAuditRepository repo, TcSupport support) {
		this.repo = repo;
		this.support = support;
	}

	public void record(UserPrincipal actor, TimeEntry entry, String action, String summary, String reason) {
		EntryAudit a = new EntryAudit();
		a.setOrganizationId(entry.getOrganizationId());
		a.setEntryId(entry.getId());
		a.setSubjectUserId(entry.getUserId());
		a.setActorUserId(actor.userId());
		a.setActorName(support.name(actor.userId()));
		a.setAction(action);
		a.setSummary(summary);
		a.setReason(TcSupport.clean(reason, 1000));
		repo.save(a);
	}
}
