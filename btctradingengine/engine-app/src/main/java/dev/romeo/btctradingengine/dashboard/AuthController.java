package dev.romeo.btctradingengine.dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Who is logged in (issue #134), so the dashboard can tell a VIEWER that placing orders needs the TRADER role
 * before the click. Anonymous calls get the usual 401, which also leaves the XSRF-TOKEN cookie the login form
 * needs for its POST.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Session(String username, List<String> roles) { }

    @GetMapping("/me")
    public Session me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .sorted()
                .toList();
        return new Session(authentication.getName(), roles);
    }
}
