package com.configuration;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Custom authentication token that extends Spring Security's
 * AbstractAuthenticationToken.
 * This implementation provides signature-based authentication for the
 * application.
 * 
 * <p>
 * This token stores a signature as the credential and supports both
 * authenticated
 * and unauthenticated states. When authenticated, it grants a single authority.
 * </p>
 * 
 * @see AbstractAuthenticationToken
 * @author jleong963
 */
public class CustomAbstractAuthenticationToken extends AbstractAuthenticationToken {

	private static final long serialVersionUID = 1L;

	/**
	 * The signature used as the authentication credential.
	 */
	private final String signature;

	/**
	 * The principal object representing the authenticated user or entity.
	 */
	private final Object principal;

	/**
	 * Constructs a new CustomAbstractAuthenticationToken with the specified
	 * parameters.
	 * 
	 * @param signature     the signature to use as the authentication credential
	 * @param principal     the principal object representing the user or entity
	 * @param authenticated {@code true} if the token should be marked as
	 *                      authenticated,
	 *                      {@code false} otherwise. When {@code true}, grants a
	 *                      single authority.
	 */
	public CustomAbstractAuthenticationToken(String signature, Object principal, boolean authenticated,
			Collection<? extends GrantedAuthority> authorities) {
		super(authenticated
				? authorities == null ? Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_USER")) : authorities
				: null);
		this.signature = signature;
		this.principal = principal;
		setAuthenticated(authenticated);
	}

	/**
	 * Returns the credentials used to authenticate the principal.
	 * 
	 * @return the signature used as the authentication credential
	 */
	@Override
	public Object getCredentials() {
		return signature;
	}

	/**
	 * Returns the principal being authenticated.
	 * 
	 * @return the principal object representing the authenticated user or entity
	 */
	@Override
	public Object getPrincipal() {
		return principal;
	}
}