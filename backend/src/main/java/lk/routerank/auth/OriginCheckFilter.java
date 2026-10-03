package lk.routerank.auth;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requests that change data must come from one of the site's own origins. Browsers always send
 * {@code Origin} on these requests, so a missing one is refused too. Works alongside the CSRF token.
 */
class OriginCheckFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private final Set<String> allowed;

	OriginCheckFilter(List<String> allowedOrigins) {
		this.allowed = Set.copyOf(allowedOrigins);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return SAFE_METHODS.contains(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String origin = request.getHeader("Origin");
		if (origin == null || !allowed.contains(origin)) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		chain.doFilter(request, response);
	}

}
