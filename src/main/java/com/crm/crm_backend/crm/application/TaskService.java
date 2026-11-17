package com.crm.crm_backend.crm.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.TaskDtos.CompleteTaskRequest;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskRequest;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskResponse;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.TaskStatus;
import com.crm.crm_backend.crm.infrastructure.CrmTaskRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** Follow-up queue. Keeps each patient's next_follow_up in sync with their earliest open task. */
@Service
public class TaskService {

	public static final String FIRST_CONTACT_PREFIX = "First contact: ";

	private static final List<TaskStatus> OPEN = List.of(TaskStatus.OPEN, TaskStatus.IN_PROGRESS);

	private final CrmTaskRepository repository;
	private final PatientRepository patientRepository;
	private final CrmSupport support;

	public TaskService(CrmTaskRepository repository, PatientRepository patientRepository, CrmSupport support) {
		this.repository = repository;
		this.patientRepository = patientRepository;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<TaskResponse> list(
			UserPrincipal principal,
			String bucket,
			UUID assignedUserId,
			UUID patientId,
			boolean mine,
			String tz
	) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		ZoneId zone = zone(tz);
		LocalDate today = LocalDate.now(zone);
		Instant startOfToday = today.atStartOfDay(zone).toInstant();
		Instant startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
		Instant now = Instant.now();

		List<CrmTask> tasks = patientId != null
				? repository.findByOrganizationIdAndPatientIdOrderByDueAtAsc(orgId, patientId)
				: repository.findByOrganizationIdOrderByDueAtAsc(orgId);
		UUID assignee = mine ? principal.userId() : assignedUserId;

		List<CrmTask> filtered = tasks.stream()
				.filter(t -> assignee == null || assignee.equals(t.getAssignedUserId()))
				.filter(t -> switch (bucket == null ? "all" : bucket.toLowerCase()) {
					case "open" -> t.isOpen();
					case "overdue" -> t.isOpen() && t.getDueAt().isBefore(now);
					case "today" -> t.isOpen() && !t.getDueAt().isBefore(startOfToday) && t.getDueAt().isBefore(startOfTomorrow);
					case "upcoming" -> t.isOpen() && !t.getDueAt().isBefore(startOfTomorrow);
					case "done" -> !t.isOpen();
					default -> true;
				})
				.toList();
		Map<UUID, Patient> patients = support.patients(filtered.stream().map(CrmTask::getPatientId).toList());
		return filtered.stream().map(t -> CrmMapper.task(t, patients.get(t.getPatientId()))).toList();
	}

	@Transactional
	public TaskResponse create(UserPrincipal principal, TaskRequest request) {
		support.requireAccess(principal);
		Patient patient = request.patientId() == null ? null : support.requirePatient(request.patientId());
		CrmTask task = new CrmTask();
		task.setCreatedByName(support.actorName(principal));
		apply(task, request);
		if (task.getAssignedUserId() == null && patient != null && patient.getOwnerUserId() != null) {
			task.setAssignedUserId(patient.getOwnerUserId());
			task.setAssignedName(patient.getOwnerName());
		}
		CrmTask saved = repository.save(task);
		syncFollowUp(saved.getPatientId());
		return CrmMapper.task(saved, patient);
	}

	@Transactional
	public TaskResponse update(UserPrincipal principal, UUID id, TaskRequest request) {
		support.requireAccess(principal);
		CrmTask task = require(id);
		UUID previousPatient = task.getPatientId();
		Patient patient = request.patientId() == null ? null : support.requirePatient(request.patientId());
		boolean wasOpen = task.isOpen();
		apply(task, request);
		if (wasOpen && !task.isOpen()) {
			task.setCompletedAt(Instant.now());
			task.setCompletedByName(support.actorName(principal));
		} else if (task.isOpen()) {
			task.setCompletedAt(null);
			task.setCompletedByName(null);
		}
		CrmTask saved = repository.save(task);
		syncFollowUp(previousPatient);
		syncFollowUp(saved.getPatientId());
		return CrmMapper.task(saved, patient);
	}

	@Transactional
	public TaskResponse complete(UserPrincipal principal, UUID id, CompleteTaskRequest request) {
		support.requireAccess(principal);
		CrmTask task = require(id);
		if (!task.isOpen()) {
			throw ApiException.badRequest("This task is already closed.");
		}
		String actor = support.actorName(principal);
		task.setStatus(TaskStatus.DONE);
		task.setCompletedAt(Instant.now());
		task.setCompletedByName(actor);
		String outcome = request == null ? null : CrmSupport.clean(request.outcome());
		if (outcome != null) {
			task.setNotes(task.getNotes() == null ? outcome : task.getNotes() + "\n\nOutcome: " + outcome);
		}
		repository.save(task);
		support.log(principal, ActivityType.SYSTEM, task.getPatientId(), task.getOpportunityId(), task.getReferralId(),
				"Task completed: " + task.getTitle(), outcome);

		if (request != null && request.nextFollowUpAt() != null) {
			String title = CrmSupport.clean(request.nextFollowUpTitle());
			createSystemTask(principal, task.getPatientId(), task.getOpportunityId(), task.getReferralId(),
					title == null ? task.getTitle() : title, task.getTaskType(), task.getPriority(),
					request.nextFollowUpAt(), task.getAssignedUserId());
		}
		syncFollowUp(task.getPatientId());
		Patient patient = task.getPatientId() == null ? null : patientRepository.findById(task.getPatientId()).orElse(null);
		return CrmMapper.task(task, patient);
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		CrmTask task = require(id);
		repository.delete(task);
		repository.flush();
		syncFollowUp(task.getPatientId());
	}

	/** Creates a task from automation (speed-to-lead, no-show recovery, call follow-up, ...). */
	@Transactional
	public CrmTask createSystemTask(
			UserPrincipal principal,
			UUID patientId,
			UUID opportunityId,
			UUID referralId,
			String title,
			String taskType,
			Priority priority,
			Instant dueAt,
			UUID assignedUserId
	) {
		CrmTask task = new CrmTask();
		task.setPatientId(patientId);
		task.setOpportunityId(opportunityId);
		task.setReferralId(referralId);
		task.setTitle(CrmSupport.truncate(title, 255));
		task.setTaskType(taskType == null ? "CALL" : taskType);
		task.setPriority(priority == null ? Priority.MEDIUM : priority);
		task.setDueAt(dueAt);
		task.setCreatedByName(support.actorName(principal));
		UUID assignee = assignedUserId;
		if (assignee == null && patientId != null) {
			assignee = patientRepository.findById(patientId).map(Patient::getOwnerUserId).orElse(null);
		}
		if (assignee != null) {
			String name = support.memberNameOrNull(assignee);
			if (name != null) {
				task.setAssignedUserId(assignee);
				task.setAssignedName(name);
			}
		}
		CrmTask saved = repository.save(task);
		syncFollowUp(patientId);
		return saved;
	}

	/** Closes the automatic speed-to-lead task once the lead has actually been contacted. */
	@Transactional
	public void completeFirstContact(UserPrincipal principal, UUID patientId) {
		List<CrmTask> open = repository.findByOrganizationIdAndPatientIdAndStatusInOrderByDueAtAsc(
				support.orgId(), patientId, OPEN);
		List<CrmTask> firstContact = open.stream().filter(t -> t.getTitle().startsWith(FIRST_CONTACT_PREFIX)).toList();
		if (firstContact.isEmpty()) {
			return;
		}
		String actor = support.actorName(principal);
		for (CrmTask task : firstContact) {
			task.setStatus(TaskStatus.DONE);
			task.setCompletedAt(Instant.now());
			task.setCompletedByName(actor);
		}
		repository.saveAll(firstContact);
		syncFollowUp(patientId);
	}

	/** Cancels every open task for a patient (used when a lead is lost / not qualified). */
	@Transactional
	public int cancelOpenTasks(UUID patientId, String reason) {
		List<CrmTask> open = repository.findByOrganizationIdAndPatientIdAndStatusInOrderByDueAtAsc(
				support.orgId(), patientId, OPEN);
		for (CrmTask task : open) {
			task.setStatus(TaskStatus.CANCELLED);
			task.setNotes(task.getNotes() == null ? reason : task.getNotes() + "\n\n" + reason);
		}
		repository.saveAll(open);
		syncFollowUp(patientId);
		return open.size();
	}

	public void syncFollowUp(UUID patientId) {
		if (patientId == null) {
			return;
		}
		patientRepository.findByIdAndOrganizationId(patientId, support.orgId()).ifPresent(patient -> {
			LocalDate next = repository.findByOrganizationIdAndPatientIdAndStatusInOrderByDueAtAsc(
							support.orgId(), patientId, OPEN)
					.stream()
					.findFirst()
					.map(t -> LocalDate.ofInstant(t.getDueAt(), ZoneId.systemDefault()))
					.orElse(null);
			if (next == null ? patient.getNextFollowUp() != null : !next.equals(patient.getNextFollowUp())) {
				patient.setNextFollowUp(next);
				patientRepository.save(patient);
			}
		});
	}

	private CrmTask require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Task not found."));
	}

	private void apply(CrmTask task, TaskRequest request) {
		task.setPatientId(request.patientId());
		task.setOpportunityId(request.opportunityId());
		task.setReferralId(request.referralId());
		task.setTitle(request.title().trim());
		task.setTaskType(request.taskType() == null || request.taskType().isBlank() ? "CALL" : request.taskType().trim());
		task.setPriority(request.priority() == null ? Priority.MEDIUM : request.priority());
		task.setStatus(request.status() == null ? TaskStatus.OPEN : request.status());
		task.setDueAt(request.dueAt());
		task.setAssignedUserId(request.assignedUserId());
		task.setAssignedName(support.memberName(request.assignedUserId()));
		task.setNotes(CrmSupport.clean(request.notes()));
	}

	static ZoneId zone(String tz) {
		if (tz == null || tz.isBlank()) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(tz);
		} catch (Exception ex) {
			return ZoneId.systemDefault();
		}
	}
}
