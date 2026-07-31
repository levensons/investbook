/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import ru.investbook.entity.SecurityPositionEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface SecurityPositionRepository extends JpaRepository<SecurityPositionEntity, Integer> {

    Optional<SecurityPositionEntity> findFirstByAccountOrderByTimestampDesc(String account);

    List<SecurityPositionEntity> findByAccountAndTimestamp(String account, Instant timestamp);
}
