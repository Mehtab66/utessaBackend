package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentRequest;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentStatusRequest;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.ReferralStatus;
import com.crm.crm_backend.crm.domain.Recall;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.RecallRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Appointments and meetings booked from the CRM. Booking / completing a visit advances the
 * lead, the linked opportunity and the linked referral; no-shows and cancellations create
 * recovery follow-ups automatically.
 */
@Service
public class AppointmentService {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a");

	private final AppointmentRepository repository;
	private final RecallRepository recallRepository;
	private final PatientService patientService;
	private final OpportunityService opportunityService;
	private final ReferralService referralService;
	private final TaskService taskService;
	private final IntegrationService integrationService;
	private final CrmSupport support;

	public AppointmentService(
			AppointmentRepository repository,
			RecallRepository recallRepository,
			PatientService patientService,
			OpportunityService opportunityService,
			ReferralService referralService,
			TaskService taskService,
			IntegrationService integrationService,
			CrmSupport support
	) {
		this.repository = repository;
		this.recallRepository = recallRepository;
		this.patientService = patientService;
		this.opportunityService = opportunityService;
		this.referralService = referralService;
		this.taskService = taskService;
		this.integrationService = integrationService;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<AppointmentResponse> list(
			UserPrincipal principal,
			Instant from,
			Instant to,
			AppointmentStatus status,
			UUID patientId,
			String provider
	) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		List<Appointment> rows;
		if (patientId != null) {
			rows = repository.findByOrganizationIdAndPatientIdOrderByStartsAtDesc(orgId, patientId);
		} else if (from != null && to != null) {
			rows = repository.findByOrganizationIdAndStartsAtBetweenOrderByStartsAtAsc(orgId, from, to);
		} else {
			rows = repository.findByOrganizationIdOrderByStartsAtAsc(orgId);
		}
		String providerNeedle = provider == null || provider.isBlank() ? null : provider.trim().toLowerCase();
		List<Appointment> filtered = rows.stream()
				.filter(a -> status == null || a.getStatus() == status)
				.filter(a -> from == null || patientId != null || !a.getStartsAt().isBefore(from))
				.filter(a -> to == null || patientId != null || a.getStartsAt().isBefore(to))
				.filter(a -> providerNeedle == null
						|| (a.getProviderName() != null && a.getProviderName().toLowerCase().contains(providerNeedle)))
				.toList();
		Map<UUID, Patient> patients = support.patients(filtered.stream().map(Appointment::getPatientId).toList());
		return filtered.stream().map(a -> CrmMapper.appointment(a, patients.get(a.getPatientId()))).toList();
	}

	@Transactional
	public AppointmentResponse create(UserPrincipal principal, AppointmentRequest request) {
		support.requireAccess(principal);
		Patient patient = support.requirePatient(request.patientId());
		Appointment a = new Appointment();
		apply(a, request, patient);
		if (a.getAssignedUserId() == null) {
			a.setAssignedUserId(principal.userId());
			a.setAssignedName(support.actorName(principal));
		}
		checkConflict(a);
		if (integrationService.syncEhrAppointments()) {
			a.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		}
		Appointment saved = repository.save(a);
		support.log(principal, ActivityType.MEETING, patient.getId(), saved.getOpportunityId(), saved.getReferralId(),
				"Appointment booked: " + saved.getTitle(),
				describe(saved));
		afterBooked(principal, saved, patient);
		return CrmMapper.appointment(saved, patient);
	}

	@Transactional
	public AppointmentResponse update(UserPrincipal principal, UUID id, AppointmentRequest request) {
		support.requireAccess(principal);
		Appointment a = require(id);
		Patient patient = support.requirePatient(request.patientId());
		Instant previousStart = a.getStartsAt();
		AppointmentStatus previousStatus = a.getStatus();
		apply(a, request, patient);
		checkConflict(a);
		Appointment saved = repository.save(a);
		if (!previousStart.equals(saved.getStartsAt())) {
			support.log(principal, ActivityType.MEETING, patient.getId(), saved.getOpportunityId(), saved.getReferralId(),
					"Appointment rescheduled: " + saved.getTitle(),
					"From " + format(previousStart) + " to " + format(saved.getStartsAt()));
		}
		if (previousStatus != saved.getStatus()) {
			afterStatusChange(principal, saved, patient, previousStatus);
		}
		return CrmMapper.appointment(saved, patient);
	}

	@Transactional
	public AppointmentResponse changeStatus(UserPrincipal principal, UUID id, AppointmentStatusRequest request) {
		support.requireAccess(principal);
		Appointment a = require(id);
		Patient patient = support.requirePatient(a.getPatientId());
		AppointmentStatus previous = a.getStatus();
		if (previous == request.status()) {
			return CrmMapper.appointment(a, patient);
		}
		a.setStatus(request.status());
		if (request.status() == AppointmentStatus.CANCELLED || request.status() == AppointmentStatus.NO_SHOW) {
			a.setCancelReason(CrmSupport.clean(request.reason()));
		}
		Appointment saved = repository.save(a);
		afterStatusChange(principal, saved, patient, previous);
		return CrmMapper.appointment(saved, patient);
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		repository.delete(require(id));
	}

	// ── automation ──────────────────────────────────────────────────────

	private void afterBooked(UserPrincipal principal, Appointment a, Patient patient) {
		patientService.advanceForAppointment(principal, patient, LeadStatus.APPOINTMENT_SCHEDULED);
		opportunityService.advance(principal, a.getOpportunityId(), OpportunityStage.CONSULT_SCHEDULED);
		referralService.advance(principal, a.getReferralId(), ReferralStatus.SCHEDULED);
	}

	private void afterStatusChange(UserPrincipal principal, Appointment a, Patient patient, AppointmentStatus previous) {
		String reason = a.getCancelReason() == null ? null : "Reason: " + a.getCancelReason();
		support.log(principal, ActivityType.STATUS_CHANGE, patient.getId(), a.getOpportunityId(), a.getReferralId(),
				a.getTitle() + ": " + CrmSupport.humanize(previous) + " → " + CrmSupport.humanize(a.getStatus()), reason);
		switch (a.getStatus()) {
			case COMPLETED, CHECKED_IN -> {
				patientService.advanceForAppointment(principal, patient, LeadStatus.SHOWED);
				if (a.getStatus() == AppointmentStatus.COMPLETED) {
					opportunityService.advance(principal, a.getOpportunityId(), OpportunityStage.CONSULT_COMPLETED);
					referralService.advance(principal, a.getReferralId(), ReferralStatus.COMPLETED);
				}
			}
			case NO_SHOW, CANCELLED -> addToRecallList(principal, a, patient);
			default -> {
			}
		}
	}

	/** Missed and cancelled visits go onto the recall call list (once per visit). */
	private void addToRecallList(UserPrincipal principal, Appointment a, Patient patient) {
		if (recallRepository.findByOrganizationIdAndAppointmentId(support.orgId(), a.getId()).isPresent()) {
			return;
		}
		Recall recall = new Recall();
		recall.setPatientId(patient.getId());
		recall.setAppointmentId(a.getId());
		recall.setMissedAt(a.getStartsAt());
		recall.setMissType(a.getStatus() == AppointmentStatus.NO_SHOW ? "NO_SHOW" : "CANCELLED");
		recall.setVisitType(typeLabel(a.getAppointmentType()));
		recall.setProviderName(a.getProviderName());
		recall.setSource("UTESSA");
		recall.setNextAttemptOn(LocalDate.now(ZoneId.systemDefault()));
		recall.setNotes(a.getCancelReason());
		recallRepository.save(recall);
		support.log(principal, ActivityType.SYSTEM, patient.getId(), a.getOpportunityId(), a.getReferralId(),
				"Added to the no-shows & cancellations call list", null);
	}

	private void checkConflict(Appointment a) {
		if (a.getProviderName() == null || !a.isActive()) {
			return;
		}
		Instant start = a.getStartsAt();
		Instant end = start.plusSeconds(a.getDurationMinutes() * 60L);
		List<Appointment> sameWindow = repository.findByOrganizationIdAndStartsAtBetweenOrderByStartsAtAsc(
				support.orgId(), start.minusSeconds(12 * 3600L), end);
		for (Appointment other : sameWindow) {
			if (other.getId().equals(a.getId()) || !other.isActive() || other.getProviderName() == null
					|| !other.getProviderName().equalsIgnoreCase(a.getProviderName())) {
				continue;
			}
			Instant otherEnd = other.getStartsAt().plusSeconds(other.getDurationMinutes() * 60L);
			if (other.getStartsAt().isBefore(end) && otherEnd.isAfter(start)) {
				throw ApiException.conflict(other.getProviderName() + " is already booked at " + format(other.getStartsAt())
						+ " (" + other.getTitle() + "). Pick another time or provider.");
			}
		}
	}

	private void apply(Appointment a, AppointmentRequest r, Patient patient) {
		a.setPatientId(patient.getId());
		a.setOpportunityId(r.opportunityId());
		a.setReferralId(r.referralId());
		String type = r.appointmentType() == null || r.appointmentType().isBlank() ? "NEW_PATIENT" : r.appointmentType().trim();
		a.setAppointmentType(type);
		String title = CrmSupport.clean(r.title());
		a.setTitle(title != null ? title : typeLabel(type) + " · " + patient.getFullName());
		a.setMode(r.mode() == null || r.mode().isBlank() ? "IN_PERSON" : r.mode().trim());
		a.setStatus(r.status() == null ? AppointmentStatus.SCHEDULED : r.status());
		a.setStartsAt(r.startsAt());
		a.setDurationMinutes(r.durationMinutes() == null ? 30 : r.durationMinutes());
		a.setProviderName(CrmSupport.clean(r.providerName()));
		a.setLocation(CrmSupport.clean(r.location()));
		if (r.assignedUserId() != null || a.getId() != null) {
			a.setAssignedUserId(r.assignedUserId());
			a.setAssignedName(support.memberName(r.assignedUserId()));
		}
		a.setCancelReason(CrmSupport.clean(r.cancelReason()));
		a.setNotes(CrmSupport.clean(r.notes()));
	}

	private Appointment require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Appointment not found."));
	}

	private static String describe(Appointment a) {
		StringBuilder sb = new StringBuilder(format(a.getStartsAt())).append(" · ").append(a.getDurationMinutes()).append(" min");
		if (a.getProviderName() != null) {
			sb.append(" · with ").append(a.getProviderName());
		}
		if (a.getLocation() != null) {
			sb.append(" · ").append(a.getLocation());
		}
		return sb.toString();
	}

	private static String format(Instant instant) {
		return WHEN.format(instant.atZone(ZoneId.systemDefault()));
	}

	static String typeLabel(String type) {
		return switch (type) {
			case "NEW_PATIENT" -> "New patient visit";
			case "CONSULTATION" -> "Consultation";
			case "FOLLOW_UP" -> "Follow-up visit";
			case "TELEHEALTH" -> "Telehealth visit";
			case "CALLBACK" -> "Scheduled callback";
			case "MEETING" -> "Meeting";
			default -> "Appointment";
		};
	}
}
