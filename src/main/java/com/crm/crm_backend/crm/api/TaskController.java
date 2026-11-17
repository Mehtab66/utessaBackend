package com.crm.crm_backend.crm.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.crm.crm_backend.crm.api.dto.TaskDtos.CompleteTaskRequest;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskRequest;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskResponse;
import com.crm.crm_backend.crm.application.TaskService;
import com.crm.crm_backend.shared.security.UserPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/crm/tasks")
public class TaskController {

	private final TaskService service;

	public TaskController(TaskService service) {
		this.service = service;
	}

	@GetMapping
	public List<TaskResponse> list(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) String bucket,
			@RequestParam(required = false) UUID assignedUserId,
			@RequestParam(required = false) UUID patientId,
			@RequestParam(defaultValue = "false") boolean mine,
			@RequestParam(required = false) String tz
	) {
		return service.list(principal, bucket, assignedUserId, patientId, mine, tz);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskResponse create(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody TaskRequest request) {
		return service.create(principal, request);
	}

	@PutMapping("/{id}")
	public TaskResponse update(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody TaskRequest request
	) {
		return service.update(principal, id, request);
	}

	@PostMapping("/{id}/complete")
	public TaskResponse complete(
			@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID id,
			@Valid @RequestBody(required = false) CompleteTaskRequest request
	) {
		return service.complete(principal, id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
		service.delete(principal, id);
	}
}
