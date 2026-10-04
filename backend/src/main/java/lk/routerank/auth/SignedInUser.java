package lk.routerank.auth;

import java.io.Serializable;

/**
 * The principal kept in the session (Spring Session stores it serialized in PostgreSQL). Only the account
 * ID; everything else is read fresh from the database. Other modules get it with {@code @AuthenticationPrincipal}.
 */
public record SignedInUser(long id) implements Serializable {
}
