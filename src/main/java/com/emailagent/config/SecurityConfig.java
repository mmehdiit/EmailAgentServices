package com.emailagent.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.StringTokenizer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.emailagent.security.JwtAuthenticationFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String FILE_CONFIG_LOCATION_WINDOWS = "C:/appsfiles/security/security.properties";

    private static final String FILE_CONFIG_LOCATION_UNIX = "/appsfiles/security/security.properties";

    private static String allowed = null;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {
                    try {
                        cors.configurationSource(corsConfigurationSource());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/api/auth/**").permitAll()
                        .requestMatchers("/v1/api/reply/mark").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() throws Exception {
        try {
            allowed = getPropStringValues("allowedOrigin");
        } catch (Exception e) {
            throw new Exception(e);
        }
        CorsConfiguration configuration = new CorsConfiguration();
        StringTokenizer stringTokenizer = new StringTokenizer(allowed, ";");
        while (stringTokenizer.hasMoreTokens()) {
            String val = stringTokenizer.nextToken();
            configuration.addAllowedOrigin(val);
        }
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static String getPropStringValues(String propertyValue) throws Exception {
        Properties properties = new Properties();

        String FILE_CONFIG_LOCATION = System.getProperty("os.name").startsWith("Windows") ? FILE_CONFIG_LOCATION_WINDOWS
                : FILE_CONFIG_LOCATION_UNIX;

        try (InputStream inputStream = Files.newInputStream(Paths.get(FILE_CONFIG_LOCATION))) {
            properties.load(inputStream);
            String value = properties.getProperty(propertyValue);
            if (!StringUtils.hasLength(value)) {
                throw new Exception("File Configuration not exist");
            }
            return value;
        } catch (Exception e) {
            throw new Exception("File Configuration not exist");
        }
    }
}
