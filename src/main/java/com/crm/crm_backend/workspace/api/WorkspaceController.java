package com.crm.crm_backend.workspace.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.AnnouncementRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.AnnouncementResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.EventRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.EventResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.NoteRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.NoteResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TaskRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TaskResponse;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TaskStatusRequest;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.TeamMember;
import com.crm.crm_backend.workspace.api.WorkspaceDtos.WorkspaceSummary;
import com.crm.crm_backend.workspace.application.WorkspaceService;

import jakarta.validation.Valid;

/** Always-on workspace endpoints — available whatever modules the practice enabled. */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

	private final WorkspaceService service;

	public WorkspaceController(WorkspaceService service) {
		this.service = service;
	}

	@GetMapping("/summary")
	public WorkspaceSummary summary(@AuthenticationPrincipal UserPrincipal p, @RequestParam(required = false) String tz) {
		return service.summary(p, tz);
	}

	@GetMapping("/team")
	public List<TeamMember> team(@AuthenticationPrincipal UserPrincipal p) {
		return service.team(p);
	}

	/* ── Tasks ── */
	@GetMapping("/tasks")
	public List<TaskResponse> tasks(@AuthenticationPrincipal UserPrincipal p) {
		return service.listTasks(p);
	}

	@PostMapping("/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	public TaskResponse createTask(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody TaskRequest r) {
		return service.createTask(p, r);
	}

	@PutMapping("/tasks/{id}")
	public TaskResponse updateTask(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody TaskRequest r) {
		return service.updateTask(p, id, r);
	}

	@PatchMapping("/tasks/{id}/status")
	public TaskResponse taskStatus(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody TaskStatusRequest r) {
		return service.setTaskStatus(p, id, r.status());
	}

	@DeleteMapping("/tasks/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteTask(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		service.deleteTask(p, id);
	}

	/* ── Calendar ── */
	@GetMapping("/events")
	public List<EventResponse> events(
			@AuthenticationPrincipal UserPrincipal p,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to) {
		return service.listEvents(p, from, to);
	}

	@PostMapping("/events")
	@ResponseStatus(HttpStatus.CREATED)
	public EventResponse createEvent(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody EventRequest r) {
		return service.createEvent(p, r);
	}

	@PutMapping("/events/{id}")
	public EventResponse updateEvent(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody EventRequest r) {
		return service.updateEvent(p, id, r);
	}

	@DeleteMapping("/events/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteEvent(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		service.deleteEvent(p, id);
	}

	/* ── Notes ── */
	@GetMapping("/notes")
	public List<NoteResponse> notes(@AuthenticationPrincipal UserPrincipal p) {
		return service.listNotes(p);
	}

	@PostMapping("/notes")
	@ResponseStatus(HttpStatus.CREATED)
	public NoteResponse createNote(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody NoteRequest r) {
		return service.createNote(p, r);
	}

	@PutMapping("/notes/{id}")
	public NoteResponse updateNote(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody NoteRequest r) {
		return service.updateNote(p, id, r);
	}

	@DeleteMapping("/notes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteNote(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		service.deleteNote(p, id);
	}

	/* ── Team board ── */
	@GetMapping("/announcements")
	public List<AnnouncementResponse> announcements(@AuthenticationPrincipal UserPrincipal p) {
		return service.listAnnouncements(p);
	}

	@PostMapping("/announcements")
	@ResponseStatus(HttpStatus.CREATED)
	public AnnouncementResponse createAnnouncement(@AuthenticationPrincipal UserPrincipal p, @Valid @RequestBody AnnouncementRequest r) {
		return service.createAnnouncement(p, r);
	}

	@PutMapping("/announcements/{id}")
	public AnnouncementResponse updateAnnouncement(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id, @Valid @RequestBody AnnouncementRequest r) {
		return service.updateAnnouncement(p, id, r);
	}

	@DeleteMapping("/announcements/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAnnouncement(@AuthenticationPrincipal UserPrincipal p, @PathVariable UUID id) {
		service.deleteAnnouncement(p, id);
	}
}
