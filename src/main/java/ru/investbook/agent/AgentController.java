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

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/v1")
@RequiredArgsConstructor
public class AgentController {
    private final AgentAnalyticsService service;

    @GetMapping("/accounts")
    public AgentResponse accounts() { return service.accounts(); }
    @GetMapping("/portfolio")
    public AgentResponse portfolio(@RequestParam("account_ids") List<String> accounts) {
        return service.portfolio(accounts);
    }
    @GetMapping("/allocation")
    public AgentResponse allocation(@RequestParam("account_ids") List<String> accounts,
                                   @RequestParam(name="group_by", defaultValue="security") String group) {
        return service.allocation(accounts, group);
    }
    @GetMapping("/performance")
    public AgentResponse performance(@RequestParam("account_ids") List<String> accounts,
                                     @RequestParam("date_from") LocalDate from,
                                     @RequestParam("date_to") LocalDate to) {
        return service.performance(accounts, from, to);
    }
    @GetMapping("/income")
    public AgentResponse income(@RequestParam("account_ids") List<String> accounts,
                               @RequestParam("date_from") LocalDate from,
                               @RequestParam("date_to") LocalDate to,
                               @RequestParam(name="group_by", defaultValue="month") String group) {
        return service.income(accounts, from, to, group);
    }
    @GetMapping("/transactions")
    public AgentResponse transactions(@RequestParam("account_ids") List<String> accounts,
                                      @RequestParam("date_from") LocalDate from,
                                      @RequestParam("date_to") LocalDate to,
                                      @RequestParam(name="security_id", defaultValue="0") int security,
                                      @RequestParam(defaultValue="100") int limit,
                                      @RequestParam(defaultValue="0") int offset) {
        return service.transactions(accounts, from, to, security, limit, offset);
    }

    @ExceptionHandler(AgentException.class)
    ResponseEntity<?> error(AgentException e) {
        return ResponseEntity.status(e.status()).body(Map.of("schema_version", "1", "error",
                Map.of("code", e.code(), "message", e.getMessage())));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid(Exception e) { return error(AgentException.invalid()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unavailable(Exception e) {
        // Do not expose database paths, SQL parameters, account numbers or credentials.
        return error(new AgentException("UPSTREAM_UNAVAILABLE", 503, "Analytics could not be completed"));
    }
}
