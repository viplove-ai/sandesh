package in.sandesh.security;

import in.sandesh.directory.NirmanDirectory;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates every request from Nirman's access token.
 *
 * <p>The signature check is local and free. The session epoch is then re-read from Nirman,
 * because a password reset there must end a session here too — a signed claim that nothing looks
 * up would otherwise keep a handset working until the token expired, which is precisely the
 * fifteen minutes somebody resets a password to avoid.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;
    private final NirmanDirectory directory;

    public JwtAuthenticationFilter(JwtTokenService tokens, NirmanDirectory directory) {
        this.tokens = tokens;
        this.directory = directory;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthenticatedUser user = tokens.parse(header.substring(7));
                if (directory.isSessionCurrent(user.userId(), user.sessionEpoch())) {
                    var authorities = user.permissions().stream()
                            .map(SimpleGrantedAuthority::new).toList();
                    var auth = new UsernamePasswordAuthenticationToken(user, null,
                            List.copyOf(authorities));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException refused) {
                // Forged, malformed or expired. Leave the context empty and let the entry point
                // answer 401 — an exception here would become a 500.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
