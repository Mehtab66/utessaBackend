package com.crm.crm_backend.shared.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.crm.crm_backend.shared.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		try {
			String header = request.getHeader(HttpHeaders.AUTHORIZATION);
			if (header != null && header.startsWith("Bearer ")) {
				String token = header.substring(7);
				try {
					UserPrincipal principal = jwtService.parse(token);
					UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
							principal, null, principal.getAuthorities());
					SecurityContextHolder.getContext().setAuthentication(auth);
					TenantContext.set(principal.organizationId(), principal.userId(), principal.role());
				} catch (Exception ex) {
					SecurityContextHolder.clearContext();
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					response.setContentType("application/json");
					response.getWriter().write("{\"message\":\"Invalid or expired token\"}");
					return;
				}
			}
			filterChain.doFilter(request, response);
		} finally {
			TenantContext.clear();
		}
	}
}
