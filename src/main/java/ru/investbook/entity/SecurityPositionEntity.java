/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "security_position")
@Data
public class SecurityPositionEntity {

    @Id
    @AssignedOrGeneratedValue
    @Column(name = "id")
    private Integer id;

    @Basic
    @Column(name = "account", nullable = false)
    private String account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "security", referencedColumnName = "id", nullable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer"})
    private SecurityEntity security;

    @Basic
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Basic
    @Column(name = "count", nullable = false)
    private BigDecimal count;
}
