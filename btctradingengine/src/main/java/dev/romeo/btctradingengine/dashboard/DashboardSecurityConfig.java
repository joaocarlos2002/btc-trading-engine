package dev.romeo.btctradingengine.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.util.Arrays;
import java.util.List;

/**
 * Authentication and CSRF protection for the dashboard (issue #98), replacing the short-term
 * X-Api-Token interceptor of issue #66.
 *
 * <p>Nothing is served without a login: the pages, every {@code /api/**} endpoint and the
 * {@code /ws/live} handshake all require an authenticated session, and the manual-order endpoints
 * additionally require the {@code TRADER} role. Every state-changing request needs the CSRF token,
 * so a POST from another site is refused even while the operator is logged in.
 *
 * <p>Fails closed: with {@code dashboard.auth.password} blank no user exists and nobody can log in.
 * Together with {@code server.address=127.0.0.1}, remote access is meant to go through a reverse
 * proxy with TLS.
 */
@Configuration
@EnableWebSecurity
public class DashboardSecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(DashboardSecurityConfig.class);

    private final String username;
    private final String password;
    private final String roles;

    public DashboardSecurityConfig(@Value("${dashboard.auth.username:trader}") String username,
                                   @Value("${dashboard.auth.password:}") String password,
                                   @Value("${dashboard.auth.roles:VIEWER,TRADER}") String roles) {
        this.username = username;
        this.password = password;
        this.roles = roles;
    }

    @Bean
    public SecurityFilterChain dashboardSecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // Placing orders needs the TRADER role; a VIEWER-only login can watch but not trade.
                        .requestMatchers("/api/trades/manual/**").hasRole("TRADER")
                        .requestMatchers(HttpMethod.GET, "/favicon.ico").permitAll()
                        // Boot's error dispatch: without this a 401 sent as an error would be turned into
                        // a redirect to the login form on the way out.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // permitAll opens the generated login page itself, which anyRequest() would otherwise block.
                .formLogin(form -> form.permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                // For scripts and curl, which send the credentials up front. An unauthenticated browser
                // call is answered by the entry points below, so it never sees a basic-auth challenge.
                .httpBasic(Customizer.withDefaults())
                // A browser asking for a page is sent to the login form, a call to /api/** gets a plain
                // 401 the dashboard's JS can react to. Registering both as mappings (instead of setting
                // one entry point) keeps Spring's generated login page in the chain.
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE))
                .csrf(csrf -> csrf
                        // Readable by JS, which echoes it back in the X-XSRF-TOKEN header: a third-party
                        // site cannot read the cookie, so it cannot forge the header.
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                // Spring Security 6 only writes the cookie when something reads the token; this makes
                // sure the page always leaves with one.
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * The single configured user. A password with no {@code {id}} prefix is taken as plain text and
     * hashed at startup; storing {@code {bcrypt}$2a$...} in the properties file is better, since the
     * plain text would otherwise sit on disk.
     */
    @Bean
    public UserDetailsService dashboardUsers(PasswordEncoder encoder) {
        if (password.isBlank()) {
            logger.warn("dashboard.auth.password is not set: nobody can log in to the dashboard. "
                    + "Set it (or the DASHBOARD_AUTH_PASSWORD environment variable) to use it.");
            return new InMemoryUserDetailsManager();
        }
        boolean alreadyEncoded = password.startsWith("{");
        if (!alreadyEncoded) {
            logger.info("dashboard.auth.password is plain text and was hashed at startup; "
                    + "consider storing the encoded value ({bcrypt}$2a$...) instead");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(alreadyEncoded ? password : encoder.encode(password))
                .roles(parsedRoles().toArray(new String[0]))
                .build());
    }

    private List<String> parsedRoles() {
        List<String> parsed = Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .toList();
        return parsed.isEmpty() ? List.of("VIEWER") : parsed;
    }
}
