package com.crm.crm_backend.timeclock.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.timeclock.domain.Timesheet;

public interface TimesheetRepository extends JpaRepository<Timesheet, UUID> {

	Optional<Timesheet> findByIdAndOrganizationId(UUID id, UUID organizationId);

	Optional<Timesheet> findByOrganizationIdAndUserIdAndPeriodStart(UUID organizationId, UUID userId, LocalDate periodStart);

	List<Timesheet> findByOrganizationIdAndPeriodStart(UUID organizationId, LocalDate periodStart);

	/** One person's timesheets whose period overlaps [from, to]. */
	List<Timesheet> findByOrganizationIdAndUserIdAndPeriodStartLessThanEqualAndPeriodEndGreaterThanEqual(
			UUID organizationId, UUID userId, LocalDate to, LocalDate from);
}
