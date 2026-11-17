package com.crm.crm_backend.crm.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Patient;

public interface PatientRepository extends JpaRepository<Patient, UUID>, JpaSpecificationExecutor<Patient> {

	List<Patient> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

	Optional<Patient> findByIdAndOrganizationId(UUID id, UUID organizationId);

	List<Patient> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

	long countByOrganizationId(UUID organizationId);

	long countByOrganizationIdAndLifecycleStageAndLeadStatusIn(
			UUID organizationId, Lifecycle lifecycleStage, Collection<LeadStatus> statuses);

	@Query("""
			select p from Patient p
			where p.organizationId = :orgId
			  and (p.phoneNormalized = :phone or p.altPhoneNormalized = :phone)
			order by p.lifecycleStage desc, p.updatedAt desc
			""")
	List<Patient> findByPhone(@Param("orgId") UUID orgId, @Param("phone") String phone);

	@Query("""
			select p from Patient p
			where p.organizationId = :orgId and lower(p.email) = lower(:email)
			order by p.updatedAt desc
			""")
	List<Patient> findByEmail(@Param("orgId") UUID orgId, @Param("email") String email);
}
