package com.crm.crm_backend.operations.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.StaffMember;

public interface StaffMemberRepository extends JpaRepository<StaffMember, UUID> {

	List<StaffMember> findByOrganizationIdOrderByLastNameAscFirstNameAsc(UUID organizationId);

	List<StaffMember> findByOrganizationIdAndProviderOrderByLastNameAscFirstNameAsc(UUID organizationId, boolean provider);

	Optional<StaffMember> findByIdAndOrganizationId(UUID id, UUID organizationId);

	long countByOrganizationIdAndProvider(UUID organizationId, boolean provider);

	long countByOrganizationIdAndStatus(UUID organizationId, EmploymentStatus status);

	@Query("""
			select count(s) from StaffMember s
			where s.organizationId = :orgId
			  and s.provider = true
			  and s.licenseExpiresOn is not null
			  and s.licenseExpiresOn <= :until
			""")
	long countExpiringLicenses(@Param("orgId") UUID orgId, @Param("until") LocalDate until);
}
