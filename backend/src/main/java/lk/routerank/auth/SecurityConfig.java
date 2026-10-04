package lk.routerank.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AuthProperties props,
			SecurityContextRepository securityContextRepository) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/auth/**", "/actuator/health", "/v3/api-docs", "/v3/api-docs/**", "/error").permitAll()
				.requestMatchers("/api/**").authenticated()
				.anyRequest().denyAll())
			.csrf(csrf -> csrf.spa())
			.addFilterBefore(new OriginCheckFilter(props.allowedOrigins()), CsrfFilter.class)
			.securityContext(context -> context.securityContextRepository(securityContextRepository))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.requestCache(cache -> cache.disable())
			.build();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/** Runs before everything else, including Spring Session, so requests without the secret never touch the database. */
	@Bean
	FilterRegistrationBean<ProxySecretFilter> proxySecretFilter(AuthProperties props) {
		var registration = new FilterRegistrationBean<>(new ProxySecretFilter(props.proxySecret()));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	@Bean
	JwtDecoder googleIdTokenDecoder(AuthProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GoogleIdTokens.JWK_SET_URI).build();
		decoder.setJwtValidator(GoogleIdTokens.validator(props.googleClientId()));
		return decoder;
	}

	@Bean
	GoogleIdTokens googleIdTokens(JwtDecoder googleIdTokenDecoder) {
		return new GoogleIdTokens(googleIdTokenDecoder);
	}

}
