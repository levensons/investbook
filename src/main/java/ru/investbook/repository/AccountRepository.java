/*
 * InvestBook
 * Copyright (C) 2022  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.investbook.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.ListQuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.investbook.entity.AccountEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Transactional(readOnly = true)
public interface AccountRepository extends JpaRepository<AccountEntity, String>, ListQuerydslPredicateExecutor<AccountEntity> {

    Set<AccountEntity> findByEnabledIsTrue();

    Set<AccountEntity> findByEnabledIsFalse();

    @Query(nativeQuery = true, value = """
            SELECT MIN(event_timestamp)
            FROM (
                SELECT timestamp AS event_timestamp FROM transaction WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM event_cash_flow WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM security_event_cash_flow WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM account_cash WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM account_property WHERE account IN (:accounts)
            ) AS report_events
            """)
    Optional<LocalDateTime> findFirstReportTimestamp(@Param("accounts") Collection<String> accounts);

    @Query(nativeQuery = true, value = """
            SELECT MAX(event_timestamp)
            FROM (
                SELECT timestamp AS event_timestamp FROM transaction WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM event_cash_flow WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM security_event_cash_flow WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM account_cash WHERE account IN (:accounts)
                UNION ALL
                SELECT timestamp FROM account_property WHERE account IN (:accounts)
            ) AS report_events
            """)
    Optional<LocalDateTime> findLastReportTimestamp(@Param("accounts") Collection<String> accounts);

    @Transactional
    @Modifying
    @Query("UPDATE AccountEntity SET enabled = :enabled WHERE id = :account")
    void setEnabledForAccount(String account, boolean enabled);
}
