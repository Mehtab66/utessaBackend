package com.crm.crm_backend.crm.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.IntegrationDtos.IntegrationRequest;
import com.crm.crm_backend.crm.api.dto.IntegrationDtos.IntegrationResponse;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.CrmIntegration;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.CrmIntegrationRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Phone-system and EHR connection settings.
 *
 * <p>The live connectors are not implemented yet: saving settings records the practice's
 * choices and flips status to PENDING ("connection requested"). Records created while the
 * EHR "auto-create patients" switch is on are marked ehr_sync_status = PENDING so they can
 * be pushed as soon as the connector goes live.
 */
@Service
public class IntegrationService {

	public static final String SYNC_NOT_LINKED = "NOT_LINKED";
	public static final String SYNC_PENDING = "PENDING";
	public static final String SYNC_SYNCED = "SYNCED";

	private static final Set<String> KINDS = Set.of(CrmIntegration.PHONE, CrmIntegration.EHR);
	private static final Set<String> STATUSES = Set.of("NOT_CONNECTED", "PENDING", "CONNECTED", "ERROR", "DISABLED");
	/** Keys that must never be persisted in settings_json. */
	private static final Set<String> SECRET_HINTS = Set.of("password", "secret", "token", "apikey", "api_key", "clientsecret");

	private final CrmIntegrationRepository repository;
	private final PatientRepository patientRepository;
	private final AppointmentRepository appointmentRepository;
	private final OrganizationRepository organizationRepository;
	private final CrmSupport support;

	public IntegrationService(
			CrmIntegrationRepository repository,
			PatientRepository patientRepository,
			AppointmentRepository appointmentRepository,
			OrganizationRepository organizationRepository,
			CrmSupport support
	) {
		this.repository = repository;
		this.patientRepository = patientRepository;
		this.appointmentRepository = appointmentRepository;
		this.organizationRepository = organizationRepository;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<IntegrationResponse> list(UserPrincipal principal) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		Map<String, CrmIntegration> existing = new HashMap<>();
		repository.findByOrganizationId(orgId).forEach(i -> existing.put(i.getKind(), i));
		List<Patient> patients = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		List<Appointment> appointments = appointmentRepository.findByOrganizationIdOrderByStartsAtAsc(orgId);
		return List.of(CrmIntegration.PHONE, CrmIntegration.EHR).stream()
				.map(kind -> toResponse(kind, existing.get(kind), patients, appointments))
				.toList();
	}

	@Transactional
	public IntegrationResponse save(UserPrincipal principal, String kindRaw, IntegrationRequest request) {
		support.requireAdmin(principal);
		String kind = kindRaw == null ? "" : kindRaw.toUpperCase();
		if (!KINDS.contains(kind)) {
			throw ApiException.badRequest("Unknown integration type.");
		}
		CrmIntegration integration = repository.findByOrganizationIdAndKind(support.orgId(), kind)
				.orElseGet(() -> {
					CrmIntegration created = new CrmIntegration();
					created.setKind(kind);
					return created;
				});
		integration.setProvider(CrmSupport.clean(request.provider()));
		String status = request.status() == null ? null : request.status().toUpperCase();
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.badRequest("Unknown integration status.");
		}
		if ("CONNECTED".equals(status)) {
			// Live connectors are not shipped yet — never claim a connection we cannot make.
			status = "PENDING";
		}
		if (status == null) {
			status = integration.getProvider() == null ? "NOT_CONNECTED" : "PENDING";
		}
		integration.setStatus(status);
		integration.setSettings(sanitize(request.settings()));
		CrmIntegration saved = repository.save(integration);
		UUID orgId = support.orgId();
		return toResponse(kind, saved,
				patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId),
				appointmentRepository.findByOrganizationIdOrderByStartsAtAsc(orgId));
	}

	/** True when the practice asked for new patients to be auto-created in the EHR. */
	@Transactional(readOnly = true)
	public boolean autoCreateEhrPatients() {
		return repository.findByOrganizationIdAndKind(support.orgId(), CrmIntegration.EHR)
				.filter(i -> !"NOT_CONNECTED".equals(i.getStatus()) && !"DISABLED".equals(i.getStatus()))
				.map(i -> Boolean.TRUE.equals(i.getSettings().get("autoCreatePatients")))
				.orElse(false);
	}

	@Transactional(readOnly = true)
	public boolean isEhrConnected() {
		return repository.findByOrganizationIdAndKind(support.orgId(), CrmIntegration.EHR)
				.map(i -> "CONNECTED".equals(i.getStatus()))
				.orElse(false);
	}

	@Transactional(readOnly = true)
	public boolean syncEhrAppointments() {
		return repository.findByOrganizationIdAndKind(support.orgId(), CrmIntegration.EHR)
				.filter(i -> !"NOT_CONNECTED".equals(i.getStatus()) && !"DISABLED".equals(i.getStatus()))
				.map(i -> Boolean.TRUE.equals(i.getSettings().get("syncAppointments")))
				.orElse(false);
	}

	private IntegrationResponse toResponse(
			String kind,
			CrmIntegration integration,
			List<Patient> patients,
			List<Appointment> appointments
	) {
		long pendingPatients = patients.stream().filter(p -> SYNC_PENDING.equals(p.getEhrSyncStatus())).count();
		long linkedPatients = patients.stream().filter(p -> p.getEhrPatientId() != null).count();
		long pendingAppointments = appointments.stream().filter(a -> SYNC_PENDING.equals(a.getEhrSyncStatus())).count();
		if (integration == null) {
			Map<String, Object> defaults = new LinkedHashMap<>();
			String provider = null;
			if (CrmIntegration.EHR.equals(kind)) {
				provider = organizationRepository.findById(support.orgId()).map(o -> o.getEhrName()).orElse(null);
				defaults.put("autoCreatePatients", true);
				defaults.put("syncAppointments", true);
				defaults.put("matchOn", "NAME_DOB_PHONE");
			} else {
				defaults.put("screenPop", true);
				defaults.put("logCallsAutomatically", true);
				defaults.put("createLeadForUnknownCallers", true);
				defaults.put("smsEnabled", false);
			}
			return new IntegrationResponse(kind, provider, "NOT_CONNECTED", defaults, null,
					pendingPatients, pendingAppointments, linkedPatients, null);
		}
		return new IntegrationResponse(kind, integration.getProvider(), integration.getStatus(),
				integration.getSettings(), integration.getLastSyncAt(), pendingPatients, pendingAppointments,
				linkedPatients, integration.getUpdatedAt());
	}

	private Map<String, Object> sanitize(Map<String, Object> settings) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (settings == null) {
			return out;
		}
		settings.forEach((key, value) -> {
			String k = key == null ? "" : key.replace("-", "").toLowerCase();
			boolean secret = SECRET_HINTS.stream().anyMatch(k::contains);
			if (!secret && value != null && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
				out.put(key, value instanceof String s ? CrmSupport.truncate(s.trim(), 255) : value);
			}
		});
		return out;
	}
}
