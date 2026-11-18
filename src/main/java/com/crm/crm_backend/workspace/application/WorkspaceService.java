package com.crm.crm_backend.workspace.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.AnnouncementRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.AnnouncementResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.EventRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.EventResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.NoteRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.NoteResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TaskRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TaskResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TeamMember;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.WorkspaceSummary;
import com.crm.crm_backend.workspace.domain.Announcement;
import com.crm.crm_backend.workspace.domain.WorkspaceEvent;
import com.crm.crm_backend.workspace.domain.WorkspaceNote;
import com.crm.crm_backend.workspace.domain.WorkspaceTask;
import com.crm.crm_backend.workspace.infrastructure.AnnouncementRepository;
import com.crm.crm_backend.workspace.infrastructure.WorkspaceEventRepository;
import com.crm.crm_backend.workspace.infrastructure.WorkspaceNoteRepository;
import com.crm.crm_backend.workspace.infrastructure.WorkspaceTaskRepository;

/**
 * Always-on workspace features (My work, Calendar, Notes, Team board, Team).
 * Available to every practice member, independent of the enabled modules.
 */
@Service
public class WorkspaceService {

	private static final String PERM = "dash";
	private static final Set<String> TASK_STATUSES = Set.of("TODO", "IN_PROGRESS", "DONE");
	private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");
	private static final Set<String> EVENT_CATEGORIES = Set.of("MEETING", "HUDDLE", "TRAINING", "TIME_OFF", "REMINDER", "OTHER");
	private static final Set<String> NOTE_COLORS = Set.of("yellow", "mint", "sky", "lavender", "rose", "white");
	private static final Set<String> POST_CATEGORIES = Set.of("GENERAL", "POLICY", "SCHEDULE", "CELEBRATION", "URGENT");
	private static final Set<String> MANAGER_ROLES = Set.of("OWNER", "ADMIN", "MANAGER", "HR");

	private final WorkspaceTaskRepository tasks;
	private final WorkspaceEventRepository events;
	private final WorkspaceNoteRepository notes;
	private final AnnouncementRepository announcements;
	private final MembershipRepository memberships;
	private final UserRepository users;

	public WorkspaceService(
			WorkspaceTaskRepository tasks,
			WorkspaceEventRepository events,
			WorkspaceNoteRepository notes,
			AnnouncementRepository announcements,
			MembershipRepository memberships,
			UserRepository users
	) {
		this.tasks = tasks;
		this.events = events;
		this.notes = notes;
		this.announcements = announcements;
		this.memberships = memberships;
		this.users = users;
	}

	/* ══ Tasks ═════════════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public List<TaskResponse> listTasks(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, PERM);
		return tasks.findByOrganizationIdOrderByCreatedAtDesc(org()).stream().map(this::toTask).toList();
	}

	@Transactional
	public TaskResponse createTask(UserPrincipal p, TaskRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceTask t = new WorkspaceTask();
		t.setCreatedByUserId(p.userId());
		t.setCreatedByName(nameOf(p.userId()));
		applyTask(t, r, p);
		return toTask(tasks.save(t));
	}

	@Transactional
	public TaskResponse updateTask(UserPrincipal p, UUID id, TaskRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceTask t = requireTask(id);
		applyTask(t, r, p);
		return toTask(tasks.save(t));
	}

	@Transactional
	public TaskResponse setTaskStatus(UserPrincipal p, UUID id, String status) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceTask t = requireTask(id);
		setStatus(t, status);
		return toTask(tasks.save(t));
	}

	@Transactional
	public void deleteTask(UserPrincipal p, UUID id) {
		RoleGuard.requireOrgAccess(p, PERM);
		tasks.delete(requireTask(id));
	}

	private void applyTask(WorkspaceTask t, TaskRequest r, UserPrincipal p) {
		t.setTitle(r.title().trim());
		t.setNotes(blank(r.notes()));
		t.setPriority(pick(r.priority(), PRIORITIES, "MEDIUM"));
		t.setDueDate(r.dueDate());
		UUID assignee = r.assigneeUserId() != null ? r.assigneeUserId() : p.userId();
		requireMember(assignee);
		t.setAssigneeUserId(assignee);
		t.setAssigneeName(nameOf(assignee));
		setStatus(t, r.status() == null ? t.getStatus() : r.status());
	}

	private void setStatus(WorkspaceTask t, String status) {
		String s = pick(status, TASK_STATUSES, "TODO");
		if ("DONE".equals(s) && !"DONE".equals(t.getStatus())) {
			t.setCompletedAt(Instant.now());
		} else if (!"DONE".equals(s)) {
			t.setCompletedAt(null);
		}
		t.setStatus(s);
	}

	private WorkspaceTask requireTask(UUID id) {
		return tasks.findByIdAndOrganizationId(id, org())
				.orElseThrow(() -> ApiException.notFound("Task not found."));
	}

	/* ══ Calendar ══════════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public List<EventResponse> listEvents(UserPrincipal p, Instant from, Instant to) {
		RoleGuard.requireOrgAccess(p, PERM);
		Instant f = from != null ? from : Instant.now().minus(31, ChronoUnit.DAYS);
		Instant t = to != null ? to : f.plus(62, ChronoUnit.DAYS);
		return events.findByOrganizationIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(org(), t, f)
				.stream().map(this::toEvent).toList();
	}

	@Transactional
	public EventResponse createEvent(UserPrincipal p, EventRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceEvent e = new WorkspaceEvent();
		e.setCreatedByUserId(p.userId());
		e.setCreatedByName(nameOf(p.userId()));
		applyEvent(e, r);
		return toEvent(events.save(e));
	}

	@Transactional
	public EventResponse updateEvent(UserPrincipal p, UUID id, EventRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceEvent e = requireEvent(id);
		applyEvent(e, r);
		return toEvent(events.save(e));
	}

	@Transactional
	public void deleteEvent(UserPrincipal p, UUID id) {
		RoleGuard.requireOrgAccess(p, PERM);
		events.delete(requireEvent(id));
	}

	private void applyEvent(WorkspaceEvent e, EventRequest r) {
		e.setTitle(r.title().trim());
		e.setDescription(blank(r.description()));
		e.setLocation(blank(r.location()));
		e.setCategory(pick(r.category(), EVENT_CATEGORIES, "MEETING"));
		e.setAllDay(Boolean.TRUE.equals(r.allDay()));
		e.setStartsAt(r.startsAt());
		Instant end = r.endsAt() != null ? r.endsAt() : r.startsAt().plus(1, ChronoUnit.HOURS);
		if (end.isBefore(r.startsAt())) {
			throw ApiException.badRequest("The end time must be after the start time.");
		}
		if (end.equals(r.startsAt())) {
			end = end.plus(30, ChronoUnit.MINUTES);
		}
		e.setEndsAt(end);
	}

	private WorkspaceEvent requireEvent(UUID id) {
		return events.findByIdAndOrganizationId(id, org())
				.orElseThrow(() -> ApiException.notFound("Event not found."));
	}

	/* ══ Notes ═════════════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public List<NoteResponse> listNotes(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, PERM);
		return notes.findVisible(org(), p.userId()).stream().map(n -> toNote(n, p)).toList();
	}

	@Transactional
	public NoteResponse createNote(UserPrincipal p, NoteRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceNote n = new WorkspaceNote();
		n.setOwnerUserId(p.userId());
		n.setOwnerName(nameOf(p.userId()));
		applyNote(n, r);
		return toNote(notes.save(n), p);
	}

	@Transactional
	public NoteResponse updateNote(UserPrincipal p, UUID id, NoteRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		WorkspaceNote n = requireOwnNote(id, p);
		applyNote(n, r);
		return toNote(notes.save(n), p);
	}

	@Transactional
	public void deleteNote(UserPrincipal p, UUID id) {
		RoleGuard.requireOrgAccess(p, PERM);
		notes.delete(requireOwnNote(id, p));
	}

	private void applyNote(WorkspaceNote n, NoteRequest r) {
		n.setTitle(r.title().trim());
		n.setBody(r.body());
		n.setColor(pick(r.color(), NOTE_COLORS, "yellow"));
		n.setPinned(Boolean.TRUE.equals(r.pinned()));
		n.setShared(Boolean.TRUE.equals(r.shared()));
	}

	private WorkspaceNote requireOwnNote(UUID id, UserPrincipal p) {
		WorkspaceNote n = notes.findByIdAndOrganizationId(id, org())
				.orElseThrow(() -> ApiException.notFound("Note not found."));
		if (!n.getOwnerUserId().equals(p.userId())) {
			throw ApiException.forbidden("Only the author can change this note.");
		}
		return n;
	}

	/* ══ Team board ════════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public List<AnnouncementResponse> listAnnouncements(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, PERM);
		return announcements.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(org())
				.stream().map(a -> toAnnouncement(a, p)).toList();
	}

	@Transactional
	public AnnouncementResponse createAnnouncement(UserPrincipal p, AnnouncementRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		Announcement a = new Announcement();
		a.setAuthorUserId(p.userId());
		a.setAuthorName(nameOf(p.userId()));
		applyAnnouncement(a, r, p);
		return toAnnouncement(announcements.save(a), p);
	}

	@Transactional
	public AnnouncementResponse updateAnnouncement(UserPrincipal p, UUID id, AnnouncementRequest r) {
		RoleGuard.requireOrgAccess(p, PERM);
		Announcement a = requireManageable(id, p);
		applyAnnouncement(a, r, p);
		return toAnnouncement(announcements.save(a), p);
	}

	@Transactional
	public void deleteAnnouncement(UserPrincipal p, UUID id) {
		RoleGuard.requireOrgAccess(p, PERM);
		announcements.delete(requireManageable(id, p));
	}

	private void applyAnnouncement(Announcement a, AnnouncementRequest r, UserPrincipal p) {
		a.setTitle(r.title().trim());
		a.setBody(r.body().trim());
		a.setCategory(pick(r.category(), POST_CATEGORIES, "GENERAL"));
		// Only managers can pin posts to the top of the board.
		a.setPinned(isManager(p) && Boolean.TRUE.equals(r.pinned()));
	}

	private Announcement requireManageable(UUID id, UserPrincipal p) {
		Announcement a = announcements.findByIdAndOrganizationId(id, org())
				.orElseThrow(() -> ApiException.notFound("Post not found."));
		if (!canManage(a, p)) {
			throw ApiException.forbidden("Only the author or a manager can change this post.");
		}
		return a;
	}

	private boolean canManage(Announcement a, UserPrincipal p) {
		return isManager(p) || Objects.equals(a.getAuthorUserId(), p.userId());
	}

	private boolean isManager(UserPrincipal p) {
		return p.role() != null && MANAGER_ROLES.contains(p.role().toUpperCase());
	}

	/* ══ Team directory ════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public List<TeamMember> team(UserPrincipal p) {
		RoleGuard.requireOrgAccess(p, PERM);
		List<Membership> ms = memberships.findByOrganizationId(org());
		Map<UUID, User> byId = users.findAllById(ms.stream().map(Membership::getUserId).toList())
				.stream().collect(Collectors.toMap(User::getId, Function.identity()));
		Map<UUID, Long> open = new HashMap<>();
		for (WorkspaceTask t : tasks.findByOrganizationIdOrderByCreatedAtDesc(org())) {
			if (!"DONE".equals(t.getStatus()) && t.getAssigneeUserId() != null) {
				open.merge(t.getAssigneeUserId(), 1L, Long::sum);
			}
		}
		return ms.stream()
				.filter(m -> byId.containsKey(m.getUserId()))
				.map(m -> {
					User u = byId.get(m.getUserId());
					return new TeamMember(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(),
							m.getRole().name(), u.getId().equals(p.userId()), m.getCreatedAt(),
							open.getOrDefault(u.getId(), 0L));
				})
				.sorted(Comparator.comparing(TeamMember::you).reversed()
						.thenComparing(m -> m.fullName() == null ? "" : m.fullName().toLowerCase()))
				.toList();
	}

	/* ══ Home summary ══════════════════════════════════════════════════ */

	@Transactional(readOnly = true)
	public WorkspaceSummary summary(UserPrincipal p, String tz) {
		RoleGuard.requireOrgAccess(p, PERM);
		LocalDate today = LocalDate.now(zone(tz));
		List<WorkspaceTask> mine = tasks.findByOrganizationIdOrderByCreatedAtDesc(org()).stream()
				.filter(t -> p.userId().equals(t.getAssigneeUserId()) && !"DONE".equals(t.getStatus()))
				.toList();
		long dueToday = mine.stream().filter(t -> today.equals(t.getDueDate())).count();
		long overdue = mine.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(today)).count();
		List<TaskResponse> top = mine.stream()
				.sorted(Comparator.comparing(WorkspaceTask::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
				.limit(5).map(this::toTask).toList();
		Instant now = Instant.now();
		List<EventResponse> upcoming = events
				.findByOrganizationIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc(
						org(), now.plus(14, ChronoUnit.DAYS), now)
				.stream().limit(5).map(this::toEvent).toList();
		List<AnnouncementResponse> posts = announcements.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(org())
				.stream().limit(3).map(a -> toAnnouncement(a, p)).toList();
		long teamSize = memberships.findByOrganizationId(org()).size();
		return new WorkspaceSummary(mine.size(), dueToday, overdue, teamSize, top, upcoming, posts);
	}

	/* ══ Helpers ═══════════════════════════════════════════════════════ */

	private UUID org() {
		return TenantContext.requireOrganizationId();
	}

	private void requireMember(UUID userId) {
		if (memberships.findByUserIdAndOrganizationId(userId, org()).isEmpty()) {
			throw ApiException.badRequest("That person is not on this practice team.");
		}
	}

	private String nameOf(UUID userId) {
		return users.findById(userId).map(User::getFullName).orElse(null);
	}

	private static ZoneId zone(String tz) {
		try {
			return tz == null || tz.isBlank() ? ZoneId.of("UTC") : ZoneId.of(tz);
		} catch (Exception e) {
			return ZoneId.of("UTC");
		}
	}

	/** Normalizes enum-like strings: matches upper-case codes or lower-case codes (note colors). */
	private static String pick(String value, Set<String> allowed, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String v = value.trim();
		if (allowed.contains(v.toUpperCase())) {
			return v.toUpperCase();
		}
		if (allowed.contains(v.toLowerCase())) {
			return v.toLowerCase();
		}
		return fallback;
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private TaskResponse toTask(WorkspaceTask t) {
		return new TaskResponse(t.getId(), t.getTitle(), t.getNotes(), t.getStatus(), t.getPriority(), t.getDueDate(),
				t.getAssigneeUserId(), t.getAssigneeName(), t.getCreatedByUserId(), t.getCreatedByName(),
				t.getCompletedAt(), t.getCreatedAt(), t.getUpdatedAt());
	}

	private EventResponse toEvent(WorkspaceEvent e) {
		return new EventResponse(e.getId(), e.getTitle(), e.getDescription(), e.getLocation(), e.getCategory(),
				e.getStartsAt(), e.getEndsAt(), e.isAllDay(), e.getCreatedByName(), "workspace");
	}

	private NoteResponse toNote(WorkspaceNote n, UserPrincipal p) {
		return new NoteResponse(n.getId(), n.getTitle(), n.getBody(), n.getColor(), n.isPinned(), n.isShared(),
				n.getOwnerUserId().equals(p.userId()), n.getOwnerName(), n.getUpdatedAt());
	}

	private AnnouncementResponse toAnnouncement(Announcement a, UserPrincipal p) {
		return new AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(), a.getCategory(), a.isPinned(),
				a.getAuthorName(), canManage(a, p), a.getCreatedAt());
	}
}
