package com.crm.crm_backend.timeclock.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.Member;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.SettingsRequest;
import com.crm.crm_backend.timeclock.api.dto.TcDtos.SettingsView;
import com.crm.crm_backend.timeclock.domain.TcSettings;
import com.crm.crm_backend.timeclock.infrastructure.TcSettingsRepository;

/** Pay period, overtime and rounding rules. Everyone can read them; owners and admins change them. */
@Service
public class TcSettingsService {

	private static final List<String> PERIODS = List.of("WEEKLY", "BIWEEKLY", "SEMI_MONTHLY");
	private static final List<Integer> ROUNDING = List.of(0, 5, 6, 10, 15);

	private final TcSettingsRepository repo;
	private final TcSupport support;

	public TcSettingsService(TcSettingsRepository repo, TcSupport support) {
		this.repo = repo;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public SettingsView get(UserPrincipal p) {
		support.require(p);
		return view(p, support.settings(support.org(p)));
	}

	@Transactional
	public SettingsView update(UserPrincipal p, SettingsRequest req) {
		RoleGuard.requireOrgAccess(p, TcSupport.SETTINGS);
		UUID org = support.org(p);
		TcSettings s = support.settings(org);

		if (req.timezone() != null) {
			try {
				s.setTimezone(ZoneId.of(req.timezone().trim()).getId());
			} catch (Exception e) {
				throw ApiException.badRequest("That time zone isn't recognised.");
			}
		}
		if (req.payPeriod() != null) {
			String pp = req.payPeriod().trim().toUpperCase();
			if (!PERIODS.contains(pp)) {
				throw ApiException.badRequest("Pay period must be weekly, every two weeks or twice a month.");
			}
			s.setPayPeriod(pp);
		}
		if (req.weekStart() != null) {
			try {
				s.setWeekStart(DayOfWeek.valueOf(req.weekStart().trim().toUpperCase()).name());
			} catch (IllegalArgumentException e) {
				throw ApiException.badRequest("The workweek must start on a day of the week.");
			}
		}
		if (req.dailyOtHours() != null) {
			s.setDailyOtHours(hours(req.dailyOtHours(), 24, "Daily overtime"));
		}
		if (req.weeklyOtHours() != null) {
			s.setWeeklyOtHours(hours(req.weeklyOtHours(), 168, "Weekly overtime"));
		}
		if (req.roundingMinutes() != null) {
			if (!ROUNDING.contains(req.roundingMinutes())) {
				throw ApiException.badRequest("Rounding must be none, 5, 6, 10 or 15 minutes.");
			}
			s.setRoundingMinutes(req.roundingMinutes());
		}
		if (req.allowManualEntries() != null) {
			s.setAllowManualEntries(req.allowManualEntries());
		}
		// Weekly and biweekly periods start on the first day of the workweek.
		LocalDate anchor = req.periodAnchor() != null ? req.periodAnchor() : s.getPeriodAnchor();
		if (anchor == null) {
			anchor = LocalDate.now(TcSupport.zone(s));
		}
		s.setPeriodAnchor(TimeCalc.startOfWeek(anchor, DayOfWeek.valueOf(s.getWeekStart())));
		repo.save(s);
		return view(p, s);
	}

	/** Zero (or less) clears the limit; otherwise 1 … max hours. */
	private static BigDecimal hours(BigDecimal v, int max, String label) {
		if (v.signum() <= 0) {
			return null;
		}
		if (v.compareTo(BigDecimal.ONE) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
			throw ApiException.badRequest(label + " must be between 1 and " + max + " hours (or 0 for none).");
		}
		return v.setScale(2, java.math.RoundingMode.HALF_UP);
	}

	private SettingsView view(UserPrincipal p, TcSettings s) {
		TimeCalc.Period current = TcSupport.period(s, LocalDate.now(TcSupport.zone(s)));
		return new SettingsView(s.getTimezone(), s.getPayPeriod(), s.getPeriodAnchor(), s.getWeekStart(), s.getDailyOtHours(),
				s.getWeeklyOtHours(), s.getRoundingMinutes(), s.isAllowManualEntries(), support.canEditSettings(p),
				support.isAdmin(p), current.start(), current.end());
	}

	@Transactional(readOnly = true)
	public List<Member> members(UserPrincipal p) {
		support.requireAdmin(p);
		return List.copyOf(support.members(support.org(p)).values());
	}
}
