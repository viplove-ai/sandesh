package in.sandesh.security;

import in.sandesh.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The principal for the current request, or a 401 if there is not one. */
@Component
public class CurrentUser {

    public AuthenticatedUser required() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException("unauthenticated", "Sign in again.", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    public UUID id() {
        return required().userId();
    }

    public UUID orgId() {
        return required().orgId();
    }
}
