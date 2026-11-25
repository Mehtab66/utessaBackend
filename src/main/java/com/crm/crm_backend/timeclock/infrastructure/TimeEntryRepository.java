package com.crm.crm_backend.timeclock.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.timeclock.domain.TimeEntry;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

	Optional<TimeEntry> findByIdAndOrganizationId(UUID id, UUID organizationId);

	Optional<TimeEntry> findFirstByOrganizationIdAndUserIdAndClockOutIsNull(UUID organizationId, UUID userId);

	List<TimeEntry> findByOrganizationIdAndClockOutIsNull(UUID organizationId);

	/** One person's entries that overlap [from, to): started before {@code to} and not finished by {@code from}. */
	@Query("""
			select e from TimeEntry e
			where e.organizationId = :org and e.userId = :user
			  and e.clockIn < :to and (e.clockOut is null or e.clockOut > :from)
			order by e.clockIn
			""")
	List<TimeEntry> overlapping(@Param("org") UUID org, @Param("user") UUID user, @Param("from") Instant from, @Param("to") Instant to);

	/** One person's entries that started in [from, to). */
	List<TimeEntry> findByOrganizationIdAndUserIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(
			UUID organizationId, UUID userId, Instant from, Instant to);

	/** Everyone's entries that started in [from, to). */
	List<TimeEntry> findByOrganizationIdAndClockInGreaterThanEqualAndClockInLessThanOrderByClockInAsc(
			UUID organizationId, Instant from, Instant to);
}
