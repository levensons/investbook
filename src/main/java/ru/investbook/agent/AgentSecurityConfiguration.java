/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain agentFilterChain(HttpSecurity http, AgentProperties properties) throws Exception {
        properties.validate();
        return http.securityMatcher("/api/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new AgentAccessFilter(properties), AuthorizationFilter.class)
                .build();
    }

    static final class AgentAccessFilter extends OncePerRequestFilter {
        private final AgentProperties properties;
        AgentAccessFilter(AgentProperties properties) { this.properties = properties; }
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            if (!properties.isEnabled()) { reject(response, 404, "DISABLED"); return; }
            boolean forwarded = Collections.list(request.getHeaderNames()).stream()
                    .anyMatch(h -> h.equalsIgnoreCase("Forwarded") || h.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-"));
            if (forwarded || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
                reject(response, 403, "FORBIDDEN"); return;
            }
            String auth = request.getHeader("Authorization");
            byte[] expected = ("Bearer " + properties.getToken()).getBytes(StandardCharsets.UTF_8);
            if (auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) {
                reject(response, 401, "UNAUTHORIZED"); return;
            }
            if (!request.getMethod().equals("GET")) { reject(response, 405, "METHOD_NOT_ALLOWED"); return; }
            chain.doFilter(request, response);
        }
        private static void reject(HttpServletResponse response, int status, String code) throws IOException {
            response.setStatus(status);
            response.setContentType("application/json");
            response.getWriter().write("{\"schema_version\":\"1\",\"error\":{\"code\":\"" + code + "\"}}");
        }
    }
}
