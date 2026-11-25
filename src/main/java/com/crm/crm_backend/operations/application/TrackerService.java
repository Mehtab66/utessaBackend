package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerRowRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerRowResponse;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerTabRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.TrackerTabResponse;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.domain.TrackerRow;
import com.crm.crm_backend.operations.domain.TrackerTab;
import com.crm.crm_backend.operations.infrastructure.TrackerRowRepository;
import com.crm.crm_backend.operations.infrastructure.TrackerTabRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Configurable trackers: provider ones need the credentialing permission, staff ones the staff permission. */
@Service
public class TrackerService {

	private final OpsSupport ops;
	private final OpsDefaultsService defaults;
	private final TrackerTabRepository tabRepository;
	private final TrackerRowRepository rowRepository;

	public TrackerService(OpsSupport ops, OpsDefaultsService defaults, TrackerTabRepository tabRepository, TrackerRowRepository rowRepository) {
		this.ops = ops;
		this.defaults = defaults;
		this.tabRepository = tabRepository;
		this.rowRepository = rowRepository;
	}

	@Transactional
	public List<TrackerTabResponse> tabs(UserPrincipal principal, String scope) {
		String s = scope(scope);
		requireScope(principal, s);
		defaults.ensureTrackers(ops.orgId());
		return tabRepository.findByOrganizationIdAndScopeOrderBySortOrderAscNameAsc(ops.orgId(), s).stream().map(this::toTab).toList();
	}

	@Transactional
	public TrackerTabResponse saveTab(UserPrincipal principal, UUID id, TrackerTabRequest r) {
		String s = scope(r.scope());
		requireScope(principal, s);
		TrackerTab t = id == null ? new TrackerTab() : requireTab(id);
		if (id != null && !t.getScope().equals(s)) {
			throw ApiException.badRequest("A tracker cannot move between providers and staff.");
		}
		t.setScope(s);
		t.setName(r.name().trim());
		t.setGroupName(OpsSupport.clean(r.groupName()));
		t.setDescription(OpsSupport.clean(r.description()));
		List<Map<String, Object>> fields = r.fields() == null ? new ArrayList<>() : new ArrayList<>(r.fields());
		if (fields.isEmpty()) {
			throw ApiException.badRequest("Add at least one field.");
		}
		for (Map<String, Object> f : fields) {
			if (f.get("id") == null || String.valueOf(f.get("label")).isBlank()) {
				throw ApiException.badRequest("Every field needs an id and a label.");
			}
		}
		t.setFields(fields);
		t.setGoal(r.goal());
		t.setHidden(Boolean.TRUE.equals(r.hidden()));
		if (r.sortOrder() != null) {
			t.setSortOrder(r.sortOrder());
		} else if (id == null) {
			t.setSortOrder(100 + tabRepository.findByOrganizationIdOrderBySortOrderAscNameAsc(ops.orgId()).size());
		}
		return toTab(tabRepository.save(t));
	}

	@Transactional
	public void deleteTab(UserPrincipal principal, UUID id) {
		TrackerTab t = requireTab(id);
		requireScope(principal, t.getScope());
		tabRepository.delete(t);
	}

	@Transactional(readOnly = true)
	public List<TrackerRowResponse> rows(UserPrincipal principal, UUID tabId, UUID staffId) {
		if (tabId != null) {
			TrackerTab t = requireTab(tabId);
			requireScope(principal, t.getScope());
			return rowRepository.findByOrganizationIdAndTabIdOrderByCreatedAtAsc(ops.orgId(), tabId).stream().map(this::toRow).toList();
		}
		if (staffId != null) {
			StaffMember m = ops.staff(staffId);
			requireScope(principal, m.isProvider() ? "PROVIDER" : "STAFF");
			return rowRepository.findByOrganizationIdAndStaffIdOrderByCreatedAtAsc(ops.orgId(), staffId).stream().map(this::toRow).toList();
		}
		ops.require(principal, OpsSupport.CREDS);
		List<UUID> providerTabs = tabRepository.findByOrganizationIdAndScopeOrderBySortOrderAscNameAsc(ops.orgId(), "PROVIDER").stream().map(TrackerTab::getId).toList();
		return rowRepository.findByOrganizationId(ops.orgId()).stream().filter(r -> providerTabs.contains(r.getTabId())).map(this::toRow).toList();
	}

	@Transactional
	public TrackerRowResponse saveRow(UserPrincipal principal, UUID id, TrackerRowRequest r) {
		TrackerTab t = requireTab(r.tabId());
		requireScope(principal, t.getScope());
		ops.staff(r.staffId());
		TrackerRow row = id == null ? new TrackerRow() : rowRepository.findByIdAndOrganizationId(id, ops.orgId())
				.orElseThrow(() -> ApiException.notFound("Row not found."));
		Map<String, Object> vals = r.vals() == null ? new HashMap<>() : new HashMap<>(r.vals());
		for (Map<String, Object> f : t.getFields()) {
			if (Boolean.TRUE.equals(f.get("required")) && !"file".equals(f.get("type"))) {
				Object v = vals.get(String.valueOf(f.get("id")));
				if (v == null || String.valueOf(v).isBlank()) {
					throw ApiException.badRequest("Please complete: " + f.get("label"));
				}
			}
		}
		row.setTabId(t.getId());
		row.setStaffId(r.staffId());
		row.setVals(vals);
		return toRow(rowRepository.save(row));
	}

	@Transactional
	public void deleteRow(UserPrincipal principal, UUID id) {
		TrackerRow row = rowRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Row not found."));
		requireScope(principal, requireTab(row.getTabId()).getScope());
		rowRepository.delete(row);
	}

	private void requireScope(UserPrincipal principal, String scope) {
		ops.require(principal, "PROVIDER".equals(scope) ? OpsSupport.CREDS : OpsSupport.STAFF);
	}

	private static String scope(String scope) {
		String s = scope == null ? "PROVIDER" : scope.trim().toUpperCase();
		if (!s.equals("PROVIDER") && !s.equals("STAFF")) {
			throw ApiException.badRequest("Scope must be PROVIDER or STAFF.");
		}
		return s;
	}

	private TrackerTab requireTab(UUID id) {
		return tabRepository.findByIdAndOrganizationId(id, ops.orgId()).orElseThrow(() -> ApiException.notFound("Tracker not found."));
	}

	private TrackerTabResponse toTab(TrackerTab t) {
		return new TrackerTabResponse(t.getId(), t.getCode(), t.getScope(), t.getName(), t.getGroupName(), t.getDescription(),
				t.getFields(), t.getGoal(), t.isStandard(), t.isHidden(), t.getSortOrder());
	}

	private TrackerRowResponse toRow(TrackerRow r) {
		return new TrackerRowResponse(r.getId(), r.getTabId(), r.getStaffId(), r.getVals(), r.getCreatedAt(), r.getUpdatedAt());
	}
}
