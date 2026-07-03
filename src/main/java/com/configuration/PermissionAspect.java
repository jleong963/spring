package com.configuration;

import com.enums.ResponseCode;
import com.validation.Permission;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class PermissionAspect {

	@Before("@annotation(permissions)")
	public void checkPermission(JoinPoint joinPoint, Permission permissions) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !authentication.isAuthenticated()) {
			log.warn("Unauthenticated access attempt to resource: {}", permissions.resource());
			throw new AccessDeniedException(ResponseCode.UNAUTHORIZED_ACCESS.getResponse_desc());
		}

		Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
		Set<String> userRoles = authorities.stream()
				.map(authority -> authority.getAuthority())
				.collect(Collectors.toSet());

		String resource = permissions.resource().toUpperCase();
		boolean hasPermission = false;

		for (Permission.PermissionType permission : permissions.permissions()) {
			String requiredAuthority = "ROLE_" + resource + "_" + permission.name();

			// Check if user has the specific permission
			if (userRoles.contains(requiredAuthority)) {
				hasPermission = true;
				break;
			}
		}

		if (!hasPermission) {
			log.warn("Access denied for user {} to resource {} with permissions {}. User roles: {}",
					authentication.getName(), resource, permissions.permissions(), userRoles);
			throw new AccessDeniedException(ResponseCode.FORBIDDEN_ACCESS.getResponse_desc());
		}

		log.info("Permission granted for user {} to access resource {} with permissions {}",
				authentication.getName(), resource, permissions.permissions());
	}
}
