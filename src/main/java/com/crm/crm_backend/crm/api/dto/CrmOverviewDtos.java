package com.crm.crm_backend.crm.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.crm.crm_backend.crm.api.dto.ActivityDtos.ActivityResponse;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.api.dto.TaskDtos.TaskResponse;

/** Dashboard, global search and team directory payloads. */
public final class CrmOverviewDtos {

	private CrmOverviewDtos() {
	}

	public record DashboardResponse(
			Kpis kpis,
			List<CountItem> leadFunnel,
			List<CountItem> sources,
			List<CountItem> priorities,
			List<PipelineItem> pipeline,
			List<CountItem> referralStatus,
			List<TrendPoint> trend,
			List<AppointmentResponse> upcomingAppointments,
			List<TaskResponse> dueTasks,
			List<PatientResponse> hotLeads,
			List<ActivityResponse> recentActivity
	) {
	}

	public record Kpis(
			long newLeadsThisMonth,
			long openLeads,
			long activePatients,
			double conversionRate,
			long appointmentsToday,
			long appointmentsUpcoming,
			long tasksDueToday,
			long tasksOverdue,
			long openReferrals,
			long callsToday,
			double noShowRate,
			BigDecimal openPipelineValue,
			BigDecimal wonValueThisMonth
	) {
	}

	public record CountItem(String key, String label, long count) {
	}

	public record PipelineItem(String stage, long count, BigDecimal value) {
	}

	public record TrendPoint(String month, long leads, long conversions, long appointments) {
	}

	public record SearchResult(String type, UUID id, String title, String subtitle, String link) {
	}

	public record TeamMember(UUID userId, String fullName, String email, String role) {
	}
}
