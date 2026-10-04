package lk.routerank.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Locks the API to its proxy: every request must carry the shared secret that the Pages Function (or Vite's
 * dev proxy) adds, except the health check used by deploys. Only on those requests is
 * {@code X-Forwarded-For} trusted; its first entry, set by the Function from {@code CF-Connecting-IP},
 * becomes the client address for rate limits.
 */
class ProxySecretFilter extends OncePerRequestFilter {

	static final String HEADER = "X-RouteRank-Proxy-Secret";

	private final byte[] secret;

	ProxySecretFilter(String secret) {
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().equals("/actuator/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String given = request.getHeader(HEADER);
		if (given == null || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor == null || forwardedFor.isBlank()) {
			chain.doFilter(request, response);
			return;
		}
		String client = forwardedFor.split(",", 2)[0].trim();
		chain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public String getRemoteAddr() {
				return client;
			}
		}, response);
	}

}
