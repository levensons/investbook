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

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spacious_team.broker.pojo.CashFlowType;
import org.spacious_team.broker.pojo.SecurityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.investbook.entity.*;
import ru.investbook.report.ForeignExchangeRateService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only analytics with explicit scope. No ViewFilter, network fetches or JPA serialization. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, timeout = 15)
public class AgentAnalyticsService {
    private static final int MAX_ROWS = 2000;
    private final EntityManager em;
    private final AgentProperties config;
    private final ForeignExchangeRateService fx;

    public AgentResponse accounts() {
        List<Object> result = new ArrayList<>();
        for (var entry : config.getAccounts().entrySet()) {
            AccountEntity account = em.find(AccountEntity.class, entry.getValue());
            if (account != null) result.add(map("id", entry.getKey(), "active", account.isEnabled()));
        }
        return response(List.of(), null, null, "configured_account_aliases", result, List.of());
    }

    private List<String> scope(List<String> aliases) {
        if (aliases.isEmpty() || aliases.size() > 20 || new HashSet<>(aliases).size() != aliases.size()
                || aliases.stream().anyMatch(a -> !a.matches("[a-zA-Z0-9_-]{1,64}"))) throw AgentException.invalid();
        List<String> ids = new ArrayList<>();
        for (String alias : aliases) {
            @Nullable String id = config.getAccounts().get(alias);
            if (id == null) throw new AgentException("FORBIDDEN", 403, "Account is not permitted");
            ids.add(id);
        }
        // Authorization of the entire list precedes any access to portfolio data.
        for (String id : ids) {
            if (em.find(AccountEntity.class, id) == null)
                throw new AgentException("INSUFFICIENT_DATA", 422, "Configured account is unavailable");
        }
        return ids;
    }

    private String alias(String id) {
        return config.getAccounts().entrySet().stream().filter(e -> e.getValue().equals(id))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private void period(LocalDate from, LocalDate to) {
        if (from.isBefore(LocalDate.of(1970, 1, 2)) || to.isBefore(from)
                || to.isAfter(LocalDate.now(config.getTimezone())) || ChronoUnit.DAYS.between(from, to) >= 3660)
            throw AgentException.invalid();
    }

    private Instant start(LocalDate date) { return date.atStartOfDay(config.getTimezone()).toInstant(); }
    private LocalDate day(Instant at) { return at.atZone(config.getTimezone()).toLocalDate(); }

    private <T> TypedQuery<T> query(String hql, Class<T> type) {
        return em.createQuery(hql, type).setHint("jakarta.persistence.query.timeout", 10000);
    }
    private <T> List<T> bounded(TypedQuery<T> query) {
        List<T> rows = query.setMaxResults(MAX_ROWS + 1).getResultList();
        if (rows.size() > MAX_ROWS)
            throw new AgentException("RESULT_TOO_LARGE", 422, "Narrow the account selection or date range");
        return rows;
    }

    public AgentResponse portfolio(List<String> accounts) {
        List<String> ids = scope(accounts);
        Instant cutoff = Instant.now();
        Snapshot snapshot = snapshot(ids, cutoff);
        return response(accounts, null, null, "broker_positions_plus_later_trades_or_transaction_history; stored_quotes; latest_cash_snapshot",
                snapshot.data(), snapshot.warnings());
    }

    private Snapshot snapshot(List<String> ids, Instant cutoff) {
        List<Object> holdings = new ArrayList<>();
        List<Object> cash = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Value> values = new ArrayList<>();
        Map<String, @Nullable Object> freshness = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (String id : ids) {
            String publicId = alias(id);
            List<SecurityPositionEntity> reported = bounded(query("""
                    select p from SecurityPositionEntity p join fetch p.security
                    where p.account = :account and p.timestamp =
                    (select max(p2.timestamp) from SecurityPositionEntity p2 where p2.account = :account and p2.timestamp < :cutoff)
                    order by p.security.id, p.id
                    """, SecurityPositionEntity.class).setParameter("account", id).setParameter("cutoff", cutoff));
            Map<Integer, BigDecimal> quantities = new TreeMap<>();
            Instant positionStart = Instant.EPOCH;
            List<String> positionSources = new ArrayList<>();
            if (!reported.isEmpty()) {
                positionStart = reported.getFirst().getTimestamp();
                for (var row : reported) {
                    quantities.merge(row.getSecurity().getId(), row.getCount(), BigDecimal::add);
                    positionSources.add("security_position:" + row.getId());
                }
            } else {
                warnings.add("TRANSACTION_HISTORY_ASSUMED_COMPLETE:" + publicId);
            }
            // A broker snapshot is end-of-instant: later trades only; absent snapshot includes all recorded trades.
            for (Object[] row : bounded(query("""
                    select t.security.id, sum(t.count) from TransactionEntity t
                    where t.account = :account and t.timestamp > :after and t.timestamp < :cutoff
                    group by t.security.id
                    """, Object[].class).setParameter("account", id).setParameter("after", positionStart).setParameter("cutoff", cutoff))) {
                quantities.merge((Integer) row[0], new BigDecimal(row[1].toString()), BigDecimal::add);
            }
            for (Object[] row : bounded(query("""
                    select e.security.id, sum(e.count) from SecurityEventCashFlowEntity e
                    where e.account.id = :account and e.timestamp > :after and e.timestamp < :cutoff and e.cashFlowType.id = :type
                    group by e.security.id
                    """, Object[].class).setParameter("account", id).setParameter("after", positionStart)
                    .setParameter("cutoff", cutoff).setParameter("type", CashFlowType.REDEMPTION.getId()))) {
                quantities.merge((Integer) row[0], new BigDecimal(row[1].toString()).negate(), BigDecimal::add);
            }
            List<Instant> activity = new ArrayList<>();
            for (String entity : List.of("TransactionEntity", "EventCashFlowEntity", "SecurityEventCashFlowEntity")) {
                String accountPath = entity.equals("TransactionEntity") ? "account" : "account.id";
                List<Instant> dates = query("select max(e.timestamp) from " + entity + " e where e." + accountPath
                        + " = :account and e.timestamp < :cutoff", Instant.class)
                        .setParameter("account", id).setParameter("cutoff", cutoff).getResultList();
                for (Instant date : dates) if (date != null) activity.add(date);
            }
            @Nullable Instant lastActivity = activity.stream().max(Comparator.naturalOrder()).orElse(null);
            freshness.put(publicId, map("operations_through", lastActivity, "position_snapshot_at",
                    reported.isEmpty() ? null : positionStart, "position_source_ids", positionSources));
            for (var quantity : quantities.entrySet()) {
                if (quantity.getValue().signum() == 0) continue;
                SecurityEntity security = Objects.requireNonNull(em.find(SecurityEntity.class, quantity.getKey()));
                List<SecurityQuoteEntity> quotes = query("""
                        select q from SecurityQuoteEntity q where q.security.id = :security and q.timestamp < :cutoff
                        order by q.timestamp desc, q.id desc
                        """, SecurityQuoteEntity.class).setParameter("security", security.getId())
                        .setParameter("cutoff", cutoff).setMaxResults(1).getResultList();
                @Nullable SecurityQuoteEntity quote = quotes.isEmpty() ? null : quotes.getFirst();
                @Nullable BigDecimal value = null;
                @Nullable String reason = null;
                if (!Set.of(SecurityType.STOCK, SecurityType.BOND, SecurityType.STOCK_OR_BOND, SecurityType.ASSET).contains(security.getType())) {
                    reason = "UNSUPPORTED_ASSET_TYPE";
                } else if (quote == null) {
                    reason = "MISSING_QUOTE";
                } else if (quote.getCurrency() == null || Objects.requireNonNull(quote.getCurrency()).isBlank()) {
                    reason = "MISSING_QUOTE_CURRENCY";
                } else if (quote.getPrice() == null
                        && (security.getType() == SecurityType.BOND || quote.getAccruedInterest() != null)) {
                    // SecurityQuote uses quote as money when price and accruedInterest are absent.
                    // A present accruedInterest (even zero) marks a bond quote, including STOCK_OR_BOND.
                    // Known bonds also require an absolute price when accrued interest is unavailable.
                    reason = "MISSING_ABSOLUTE_PRICE";
                } else {
                    BigDecimal price = quote.getPrice() == null ? quote.getQuote() : Objects.requireNonNull(quote.getPrice());
                    if (quote.getAccruedInterest() != null) price = price.add(Objects.requireNonNull(quote.getAccruedInterest()));
                    value = rub(price.multiply(quantity.getValue()), Objects.requireNonNull(quote.getCurrency()), day(cutoff.minusNanos(1)));
                    if (value == null) reason = "MISSING_FX";
                    if (Duration.between(quote.getTimestamp(), cutoff).toDays() > 7)
                        warnings.add("STALE_QUOTE:" + security.getId());
                }
                if (reason != null) { complete = false; warnings.add(reason + ":" + publicId + ":" + security.getId()); }
                SecurityDescriptionEntity desc = em.find(SecurityDescriptionEntity.class, security.getId());
                String sector = desc == null || desc.getSector() == null ? "UNKNOWN" : Objects.requireNonNull(desc.getSector());
                holdings.add(map("account_id", publicId, "security_id", security.getId(), "ticker", security.getTicker(),
                        "name", security.getName(), "type", security.getType().name(), "sector", sector,
                        "quantity", decimal(quantity.getValue()), "value_rub", decimal(value), "unvalued_reason", reason,
                        "quote", quote == null ? null : decimal(quote.getQuote()),
                        "price", quote == null ? null : decimal(quote.getPrice()),
                        "accrued_interest", quote == null ? null : decimal(quote.getAccruedInterest()),
                        "quote_currency", quote == null ? null : quote.getCurrency(),
                        "quote_at", quote == null ? null : quote.getTimestamp(), "quote_source_id", quote == null ? null : "security_quote:" + quote.getId()));
                if (value != null) { total = total.add(value); values.add(new Value("security:" + security.getId(), sector, value)); }
            }
            List<AccountCashEntity> cashRows = bounded(query("""
                    select c from AccountCashEntity c where c.account = :account and c.timestamp =
                    (select max(c2.timestamp) from AccountCashEntity c2 where c2.account = :account and c2.timestamp < :cutoff)
                    order by c.currency, c.market, c.id
                    """, AccountCashEntity.class).setParameter("account", id).setParameter("cutoff", cutoff));
            if (cashRows.isEmpty()) { complete = false; warnings.add("MISSING_CASH_SNAPSHOT:" + publicId); }
            for (AccountCashEntity row : cashRows) {
                @Nullable BigDecimal value = rub(row.getValue(), row.getCurrency(), day(cutoff.minusNanos(1)));
                boolean stale = lastActivity != null && lastActivity.isAfter(row.getTimestamp());
                if (stale) { complete = false; warnings.add("CASH_PRECEDES_OPERATIONS:" + publicId); }
                if (Duration.between(row.getTimestamp(), cutoff).toDays() > 7) warnings.add("STALE_CASH_SNAPSHOT:" + publicId);
                if (value == null) { complete = false; warnings.add("MISSING_FX:account_cash:" + row.getId()); }
                cash.add(map("account_id", publicId, "amount", decimal(row.getValue()), "currency", currency(row.getCurrency()),
                        "value_rub", decimal(value), "as_of", row.getTimestamp(), "source_id", "account_cash:" + row.getId()));
                if (value != null) { total = total.add(value); values.add(new Value("CASH", "CASH", value)); }
            }
            if (holdings.size() + cash.size() > MAX_ROWS)
                throw new AgentException("RESULT_TOO_LARGE", 422, "Select fewer accounts");
        }
        warnings.add("VALUATION_USES_STORED_DATA_ONLY; operation timestamps do not prove import completeness");
        Map<String, @Nullable Object> data = map("valuation_at", cutoff, "holdings", holdings, "cash", cash,
                "total_rub", complete ? decimal(total) : null, "valued_subtotal_rub", decimal(total),
                "valuation_complete", complete, "history_completeness", "unverified", "freshness", freshness);
        return new Snapshot(data, values, complete ? total : null, warnings.stream().distinct().toList());
    }

    public AgentResponse allocation(List<String> accounts, String group) {
        if (!Set.of("security", "sector").contains(group)) throw AgentException.invalid();
        Snapshot snapshot = snapshot(scope(accounts), Instant.now());
        Map<String, BigDecimal> grouped = new TreeMap<>();
        BigDecimal denominator = BigDecimal.ZERO;
        boolean negative = false;
        for (Value v : snapshot.values()) {
            grouped.merge(group.equals("security") ? v.security() : v.sector(), v.value(), BigDecimal::add);
            denominator = denominator.add(v.value());
            negative |= v.value().signum() < 0;
        }
        List<Object> buckets = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            buckets.add(map("group", entry.getKey(), "value_rub", decimal(entry.getValue()),
                    "weight_fraction", denominator.signum() > 0 && !negative
                            ? decimal(entry.getValue().divide(denominator, 12, RoundingMode.HALF_UP)) : null));
        }
        List<String> warnings = new ArrayList<>(snapshot.warnings());
        if (negative) warnings.add("WEIGHTS_UNAVAILABLE_FOR_NEGATIVE_POSITIONS_OR_CASH");
        return response(accounts, null, null, "net_value_of_valued_assets_including_cash; weights_are_fractions",
                map("group_by", group, "denominator_rub", decimal(denominator), "denominator_scope", "valued_assets_only",
                        "valuation_complete", snapshot.total() != null, "groups", buckets,
                        "portfolio", snapshot.data()), warnings);
    }

    public AgentResponse transactions(List<String> accounts, LocalDate from, LocalDate to, int security, int limit, int offset) {
        period(from, to);
        if (security < 0 || limit < 1 || limit > 200 || offset < 0 || offset > 10000) throw AgentException.invalid();
        List<String> ids = scope(accounts);
        TypedQuery<TransactionEntity> q = query("""
                select t from TransactionEntity t join fetch t.security where t.account in :accounts
                and t.timestamp >= :from and t.timestamp < :to
                """ + (security == 0 ? "" : " and t.security.id = :security") + " order by t.timestamp, t.id", TransactionEntity.class)
                .setParameter("accounts", ids).setParameter("from", start(from)).setParameter("to", start(to.plusDays(1)));
        if (security != 0) q.setParameter("security", security);
        List<TransactionEntity> rows = q.setFirstResult(offset).setMaxResults(limit + 1).getResultList();
        boolean more = rows.size() > limit;
        rows = rows.subList(0, Math.min(limit, rows.size()));
        Map<Integer, List<Object>> flows = new HashMap<>();
        if (!rows.isEmpty()) {
            for (TransactionCashFlowEntity flow : bounded(query("""
                    select f from TransactionCashFlowEntity f join fetch f.cashFlowType
                    where f.transactionId in :ids order by f.id
                    """, TransactionCashFlowEntity.class).setParameter("ids", rows.stream().map(TransactionEntity::getId).toList()))) {
                flows.computeIfAbsent(flow.getTransactionId(), _ -> new ArrayList<>()).add(map(
                        "source_id", "transaction_cash_flow:" + flow.getId(), "type", flow.getCashFlowType().getName(),
                        "amount", decimal(flow.getValue()), "currency", currency(flow.getCurrency())));
            }
        }
        List<Object> result = new ArrayList<>();
        for (var t : rows) result.add(map("source_id", "transaction:" + t.getId(), "account_id", alias(t.getAccount()),
                "timestamp", t.getTimestamp(), "security_id", t.getSecurity().getId(), "quantity", Integer.toString(t.getCount()),
                "cash_flows", flows.getOrDefault(t.getId(), List.of())));
        return response(accounts, from, to, "recorded_trades_with_signed_cash_flows_and_fees; ascending_timestamp_then_id",
                map("transactions", result, "has_more", more, "next_offset", more && offset + limit <= 10000 ? offset + limit : null),
                more && offset + limit > 10000 ? List.of("PAGINATION_LIMIT_REACHED: narrow the date range") : List.of());
    }

    public AgentResponse income(List<String> accounts, LocalDate from, LocalDate to, String group) {
        period(from, to);
        if (!Set.of("month", "security").contains(group)) throw AgentException.invalid();
        List<String> ids = scope(accounts);
        List<Object> payments = new ArrayList<>();
        Map<String, Map<String, BigDecimal>> sums = new TreeMap<>();
        Set<String> incomplete = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        List<Integer> types = List.of(CashFlowType.DIVIDEND.getId(), CashFlowType.COUPON.getId(), CashFlowType.TAX.getId(),
                CashFlowType.AMORTIZATION.getId(), CashFlowType.REDEMPTION.getId());
        for (var e : bounded(query("""
                select e from SecurityEventCashFlowEntity e join fetch e.account join fetch e.security join fetch e.cashFlowType
                where e.account.id in :accounts and e.timestamp >= :from and e.timestamp < :to
                and e.cashFlowType.id in :types order by e.timestamp, e.id
                """, SecurityEventCashFlowEntity.class).setParameter("accounts", ids).setParameter("from", start(from))
                .setParameter("to", start(to.plusDays(1))).setParameter("types", types))) {
            String category = category(e.getCashFlowType().getId());
            String bucket = group.equals("month") ? YearMonth.from(day(e.getTimestamp())).toString() : Integer.toString(e.getSecurity().getId());
            payment(payments, sums, incomplete, warnings, bucket, category, "security_event_cash_flow:" + e.getId(),
                    alias(e.getAccount().getId()), e.getTimestamp(), e.getSecurity().getId(), e.getValue(), e.getCurrency());
        }
        for (var e : bounded(query("""
                select e from EventCashFlowEntity e join fetch e.account where e.account.id in :accounts
                and e.timestamp >= :from and e.timestamp < :to and e.cashFlowType.id = :type order by e.timestamp, e.id
                """, EventCashFlowEntity.class).setParameter("accounts", ids).setParameter("from", start(from))
                .setParameter("to", start(to.plusDays(1))).setParameter("type", CashFlowType.TAX.getId()))) {
            String bucket = group.equals("month") ? YearMonth.from(day(e.getTimestamp())).toString() : "UNALLOCATED";
            payment(payments, sums, incomplete, warnings, bucket, "unallocated_tax", "event_cash_flow:" + e.getId(),
                    alias(e.getAccount().getId()), e.getTimestamp(), null, e.getValue(), e.getCurrency());
        }
        List<Object> groups = new ArrayList<>();
        sums.forEach((bucket, categories) -> {
            Map<String, @Nullable Object> totals = new LinkedHashMap<>();
            categories.forEach((category, sum) -> totals.put(category + "_rub",
                    incomplete.contains(bucket + ":" + category) ? null : decimal(sum)));
            groups.add(map("group", bucket, "totals", totals));
        });
        return response(accounts, from, to, "dividends_and_coupons_separate_from_tax_and_principal; FX_on_payment_date",
                map("group_by", group, "groups", groups, "payments", payments), warnings);
    }

    private void payment(List<Object> payments, Map<String, Map<String, BigDecimal>> sums, Set<String> incomplete,
                         List<String> warnings, String bucket, String category, String source, String account, Instant at,
                         @Nullable Integer security, BigDecimal amount, String currency) {
        @Nullable BigDecimal converted = rub(amount, currency, day(at));
        sums.computeIfAbsent(bucket, _ -> new TreeMap<>()).merge(category, converted == null ? BigDecimal.ZERO : converted, BigDecimal::add);
        if (converted == null) { incomplete.add(bucket + ":" + category); warnings.add("MISSING_FX:" + source); }
        payments.add(map("source_id", source, "account_id", account, "timestamp", at, "security_id", security,
                "category", category, "amount", decimal(amount), "currency", currency(currency), "amount_rub", decimal(converted)));
        if (payments.size() > MAX_ROWS) throw new AgentException("RESULT_TOO_LARGE", 422, "Narrow the date range");
    }

    private static String category(int type) {
        if (type == CashFlowType.DIVIDEND.getId()) return "dividend";
        if (type == CashFlowType.COUPON.getId()) return "coupon";
        if (type == CashFlowType.TAX.getId()) return "tax";
        if (type == CashFlowType.AMORTIZATION.getId()) return "amortization";
        return "redemption";
    }

    public AgentResponse performance(List<String> accounts, LocalDate from, LocalDate to) {
        period(from, to);
        List<String> ids = scope(accounts);
        Snapshot opening = snapshot(ids, start(from));
        Snapshot closing = snapshot(ids, start(to.plusDays(1)));
        List<String> warnings = new ArrayList<>(opening.warnings());
        warnings.addAll(closing.warnings());
        List<Object> flows = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        boolean complete = true;
        for (var e : bounded(query("""
                select e from EventCashFlowEntity e join fetch e.account where e.account.id in :accounts
                and e.timestamp >= :from and e.timestamp < :to and e.cashFlowType.id = :type order by e.timestamp, e.id
                """, EventCashFlowEntity.class).setParameter("accounts", ids).setParameter("from", start(from))
                .setParameter("to", start(to.plusDays(1))).setParameter("type", CashFlowType.CASH.getId()))) {
            @Nullable BigDecimal amount = rub(e.getValue(), e.getCurrency(), day(e.getTimestamp()));
            if (amount == null) { complete = false; warnings.add("MISSING_FX:event_cash_flow:" + e.getId()); }
            else net = net.add(amount);
            flows.add(map("source_id", "event_cash_flow:" + e.getId(), "account_id", alias(e.getAccount().getId()),
                    "timestamp", e.getTimestamp(), "amount", decimal(e.getValue()), "currency", currency(e.getCurrency()),
                    "amount_rub", decimal(amount)));
        }
        @Nullable BigDecimal change = opening.total() == null || closing.total() == null ? null : Objects.requireNonNull(closing.total()).subtract(Objects.requireNonNull(opening.total()));
        warnings.add("VALUE_CHANGE_IS_NOT_PROFIT_OR_RETURN; security_transfers_and_reconciliations_not_attributed");
        warnings.add("CASH_TRANSFERS_NETTED_BY_SIGN; cross_date_or_cross_currency_transfers_may_not_cancel");
        return response(accounts, from, to, "opening_before_first_day; closing_at_end_of_last_day; valuation_change_only",
                map("opening", opening.data(), "closing", closing.data(), "value_change_rub", decimal(change),
                        "recorded_net_cash_flow_rub", complete ? decimal(net) : null, "cash_flows", flows,
                        "profit_rub", null, "twr", null, "xirr", null,
                        "unsupported_metrics", List.of("profit", "twr", "portfolio_xirr", "attribution")), warnings.stream().distinct().toList());
    }

    private @Nullable BigDecimal rub(BigDecimal value, String currency, LocalDate date) {
        try { return fx.convertValueToCurrency(value, currency(currency), "RUB", date); }
        catch (NoSuchElementException e) { return null; }
    }
    private static String currency(String currency) {
        String normalized = currency.toUpperCase(Locale.ROOT);
        return Set.of("RUR", "SUR").contains(normalized) ? "RUB" : normalized;
    }
    private static @Nullable String decimal(@Nullable BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    private AgentResponse response(List<String> accounts, @Nullable LocalDate from, @Nullable LocalDate to,
                                   String method, Object data, List<String> warnings) {
        return new AgentResponse("1", map("generated_at", Instant.now(), "timezone", config.getTimezone().getId(),
                "account_ids", accounts, "date_from", from, "date_to", to, "reporting_currency", "RUB",
                "fx_method", "latest stored effective rate on or before valuation/payment date; no future or estimated rates",
                "calculation_method", method, "coverage", warnings.isEmpty() ? "recorded_data" : "see_warnings"), data, warnings);
    }
    private static Map<String, @Nullable Object> map(@Nullable Object... pairs) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) Objects.requireNonNull(pairs[i]), pairs[i + 1]);
        return map;
    }
    private record Value(String security, String sector, BigDecimal value) { }
    private record Snapshot(Map<String, @Nullable Object> data, List<Value> values, @Nullable BigDecimal total, List<String> warnings) { }
}
