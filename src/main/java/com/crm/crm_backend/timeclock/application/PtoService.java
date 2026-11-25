package com.crm.crm_backend.timeclock.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PtoBody;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.PtoView;
import com.crm.crm_backend.timeclock.domain.PtoRequest;
import com.crm.crm_backend.timeclock.infrastructure.PtoRequestRepository;

/** Time off requests: staff ask, managers approve or deny, approved hours flow into timesheets and payroll. */
@Service
public class PtoService {

	private static final BigDecimal MAX_HOURS_PER_DAY = BigDecimal.valueOf(24);

	private final PtoRequestRepository repo;
	private final TcSupport support;

	public PtoService(PtoRequestRepository repo, TcSupport support) {
		this.repo = repo;
		this.support = support;
	}

	/** scope "mine" (default) or "team" (managers). */
	@Transactional(readOnly = true)
	public List<PtoView> list(UserPrincipal p, String scope) {
		support.require(p);
		UUID org = support.org(p);
		boolean team = "team".equalsIgnoreCase(scope);
		if (team) {
			support.requireAdmin(p);
		}
		Map<UUID, Member> members = support.members(org);
		List<PtoRequest> list = team
				? repo.findByOrganizationIdOrderByStartDateDesc(org)
				: repo.findByOrganizationIdAndUserIdOrderByStartDateDesc(org, p.userId());
		return list.stream().map(r -> view(p, r, members)).toList();
	}

	@Transactional
	public PtoView create(UserPrincipal p, PtoBody body) {
		support.require(p);
		UUID org = support.org(p);
		String type = body.type() == null ? null : body.type().trim().toUpperCase();
		if (type == null || !TcSupport.PTO_TYPES.contains(type)) {
			throw ApiException.badRequest("Choose the type of time off.");
		}
		if (body.startDate() == null || body.endDate() == null) {
			throw ApiException.badRequest("Choose the first and last day off.");
		}
		if (body.endDate().isBefore(body.startDate())) {
			throw ApiException.badRequest("The last day can't be before the first day.");
		}
		long days = ChronoUnit.DAYS.between(body.startDate(), body.endDate()) + 1;
		if (days > 60) {
			throw ApiException.badRequest("Time off requests can cover at most 60 days. Split longer leave into parts.");
		}
		if (body.hours() == null || body.hours().signum() <= 0) {
			throw ApiException.badRequest("Enter how many hours you'll be off.");
		}
		if (body.hours().compareTo(MAX_HOURS_PER_DAY.multiply(BigDecimal.valueOf(days))) > 0) {
			throw ApiException.badRequest("That's more hours than there are in those days.");
		}
		boolean clash = repo.findByOrganizationIdAndUserIdOrderByStartDateDesc(org, p.userId()).stream()
				.filter(r -> PtoRequest.PENDING.equals(r.getStatus()) || PtoRequest.APPROVED.equals(r.getStatus()))
				.anyMatch(r -> !r.getEndDate().isBefore(body.startDate()) && !r.getStartDate().isAfter(body.endDate()));
		if (clash) {
			throw ApiException.conflict("You already have time off requested for some of those days.");
		}
		PtoRequest r = new PtoRequest();
		r.setOrganizationId(org);
		r.setUserId(p.userId());
		r.setPtoType(type);
		r.setStartDate(body.startDate());
		r.setEndDate(body.endDate());
		r.setHours(body.hours().setScale(2, java.math.RoundingMode.HALF_UP));
		r.setReason(TcSupport.clean(body.reason(), 1000));
		r.setStatus(PtoRequest.PENDING);
		repo.save(r);
		return view(p, r, support.members(org));
	}

	@Transactional
	public PtoView decide(UserPrincipal p, UUID id, boolean approve, String note) {
		UUID org = support.org(p);
		PtoRequest r = load(org, id);
		support.requireMayDecideFor(p, r.getUserId());
		if (!PtoRequest.PENDING.equals(r.getStatus())) {
			throw ApiException.conflict("This request was already " + r.getStatus().toLowerCase() + ".");
		}
		r.setStatus(approve ? PtoRequest.APPROVED : PtoRequest.DENIED);
		r.setDecidedBy(p.userId());
		r.setDecidedByName(support.name(p.userId()));
		r.setDecidedAt(Instant.now());
		r.setDecisionNote(TcSupport.clean(note, 1000));
		repo.save(r);
		return view(p, r, support.members(org));
	}

	@Transactional
	public PtoView cancel(UserPrincipal p, UUID id) {
		support.require(p);
		UUID org = support.org(p);
		PtoRequest r = load(org, id);
		if (!canCancel(p, r)) {
			throw ApiException.conflict(r.getUserId().equals(p.userId())
					? "Approved time off that has already started can only be cancelled by a manager."
					: "You can only cancel your own requests.");
		}
		r.setStatus(PtoRequest.CANCELLED);
		repo.save(r);
		return view(p, r, support.members(org));
	}

	private boolean canCancel(UserPrincipal p, PtoRequest r) {
		if (PtoRequest.DENIED.equals(r.getStatus()) || PtoRequest.CANCELLED.equals(r.getStatus())) {
			return false;
		}
		if (support.isAdmin(p)) {
			return true;
		}
		if (!r.getUserId().equals(p.userId())) {
			return false;
		}
		return PtoRequest.PENDING.equals(r.getStatus()) || r.getStartDate().isAfter(LocalDate.now());
	}

	private PtoRequest load(UUID org, UUID id) {
		return repo.findByIdAndOrganizationId(id, org).orElseThrow(() -> ApiException.notFound("Time off request not found."));
	}

	private PtoView view(UserPrincipal p, PtoRequest r, Map<UUID, Member> members) {
		Member m = members.get(r.getUserId());
		boolean canDecide = PtoRequest.PENDING.equals(r.getStatus()) && support.isAdmin(p)
				&& (!r.getUserId().equals(p.userId()) || "OWNER".equalsIgnoreCase(p.role()));
		return new PtoView(r.getId(), r.getUserId(), m == null ? support.name(r.getUserId()) : m.name(), r.getPtoType(),
				r.getStartDate(), r.getEndDate(), r.getHours(), r.getReason(), r.getStatus(), r.getDecidedByName(),
				r.getDecidedAt(), r.getDecisionNote(), r.getCreatedAt(), canCancel(p, r), canDecide);
	}
}
