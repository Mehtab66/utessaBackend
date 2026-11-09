package com.crm.crm_backend.organization.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;

@Service
public class SlugService {

	private final OrganizationRepository organizationRepository;

	public SlugService(OrganizationRepository organizationRepository) {
		this.organizationRepository = organizationRepository;
	}

	public String uniqueSlug(String name) {
		String base = Normalizer.normalize(name, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-|-$)", "");
		if (base.isBlank()) {
			base = "org";
		}
		String candidate = base;
		int i = 1;
		while (organizationRepository.existsBySlug(candidate)) {
			candidate = base + "-" + i++;
			if (i > 50) {
				candidate = base + "-" + UUID.randomUUID().toString().substring(0, 8);
				break;
			}
		}
		return candidate;
	}
}
