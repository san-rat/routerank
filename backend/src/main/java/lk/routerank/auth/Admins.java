package lk.routerank.auth;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Who may use the admin API: accounts whose Google email is in {@code routerank.admin.emails} (an environment
 * variable, comma-separated; empty by default, so nobody). Phase 6 replaces this with the admin role and its
 * pages.
 */
@Service
public class Admins {

	private final Accounts accounts;

	private final Set<String> emails;

	Admins(Accounts accounts, @Value("${routerank.admin.emails:}") List<String> emails) {
		this.accounts = accounts;
		this.emails = emails.stream()
			.map(e -> e.strip().toLowerCase(Locale.ROOT))
			.filter(e -> !e.isEmpty())
			.collect(Collectors.toUnmodifiableSet());
	}

	public boolean isAdmin(long userId) {
		return accounts.find(userId)
			.filter(a -> !a.banned())
			.map(a -> emails.contains(a.email().toLowerCase(Locale.ROOT)))
			.orElse(false);
	}

}
