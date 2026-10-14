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
import ru.investbook.entity.*;
import org.spacious_team.broker.pojo.SecurityType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Synthetic accounts only. All expected arithmetic is documented in docs/mcp/contract.md. */
final class AgentFixture {
    static final String TOKEN = "synthetic-test-token-with-more-than-32-characters";
    static void populate(EntityManager em) {
        AccountEntity a = account(em, "PRIVATE-BROKER-A");
        AccountEntity b = account(em, "PRIVATE-BROKER-B");
        account(em, "PRIVATE-HIDDEN");
        account(em, "PRIVATE-EMPTY");
        account(em, "PRIVATE-FX");
        SecurityEntity stock = security(em, 900001, "SYNTHETIC", SecurityType.STOCK);
        SecurityEntity missing = security(em, 900002, "NO-QUOTE", SecurityType.STOCK);
        SecurityDescriptionEntity desc = new SecurityDescriptionEntity();
        desc.setSecurity(stock.getId()); desc.setSector("Technology"); em.persist(desc);
        transaction(em, 910001, a, stock, "2025-01-01T10:00:00Z", 10);
        transaction(em, 910002, a, stock, "2025-01-02T10:00:00Z", -2);
        transaction(em, 910003, b, missing, "2025-01-01T10:00:00Z", 1);
        tradeFlow(em, 920001, 910001, 1, "-1000");
        tradeFlow(em, 920002, 910002, 1, "240");
        tradeFlow(em, 920003, 910002, 3, "-1");
        event(em, 930001, a, "2025-01-01T09:00:00Z", 0, "1100");
        event(em, 930002, a, "2025-01-02T12:00:00Z", 0, "-100");
        event(em, 930003, b, "2025-01-02T12:00:00Z", 0, "100");
        payment(em, 940001, a, stock, 7, "20");
        payment(em, 940002, a, stock, 6, "5");
        payment(em, 940003, a, stock, 4, "50");
        payment(em, 940004, a, stock, 10, "-2");
        quote(em, 950001, stock, "2025-01-01T18:00:00Z", "100");
        quote(em, 950002, stock, "2025-01-02T18:00:00Z", "110");
        cash(em, 960001, a.getId(), "2025-01-01T20:59:59Z", "100", "RUB");
        cash(em, 960002, a.getId(), "2025-01-02T20:59:59Z", "312", "RUB");
        cash(em, 960003, b.getId(), "2025-01-01T20:59:59Z", "500", "RUB");
        cash(em, 960004, b.getId(), "2025-01-02T20:59:59Z", "600", "RUB");
        cash(em, 960005, "PRIVATE-FX", "2025-01-01T20:59:59Z", "100", "ZZZ");
        cash(em, 960006, "PRIVATE-FX", "2025-01-02T20:59:59Z", "100", "ZZZ");
        cash(em, 960007, "PRIVATE-EMPTY", "2025-01-01T20:59:59Z", "0", "RUB");
        rate(em, "2025-01-01", "80"); rate(em, "2025-01-02", "100");
        em.flush();
    }
    static AccountEntity account(EntityManager em, String id) {
        var a = new AccountEntity(); a.setId(id); a.setEnabled(true); em.persist(a); return a;
    }
    static SecurityEntity security(EntityManager em, int id, String name, SecurityType type) {
        var s = new SecurityEntity(); s.setId(id); s.setName(name); s.setTicker(name); s.setType(type); em.persist(s); return s;
    }
    static void transaction(EntityManager em, int id, AccountEntity a, SecurityEntity s, String at, int count) {
        var t = new TransactionEntity(); t.setId(id); t.setTradeId("private-trade-"+id); t.setAccount(a.getId());
        t.setSecurity(s); t.setTimestamp(Instant.parse(at)); t.setCount(count); em.persist(t);
    }
    static void tradeFlow(EntityManager em, int id, int transaction, int type, String amount) {
        var f = new TransactionCashFlowEntity(); f.setId(id); f.setTransactionId(transaction);
        f.setCashFlowType(em.getReference(CashFlowTypeEntity.class, type)); f.setValue(new BigDecimal(amount)); f.setCurrency("RUB"); em.persist(f);
    }
    static void event(EntityManager em, int id, AccountEntity a, String at, int type, String amount) {
        var e = new EventCashFlowEntity(); e.setId(id); e.setAccount(a); e.setTimestamp(Instant.parse(at));
        e.setCashFlowType(em.getReference(CashFlowTypeEntity.class, type)); e.setValue(new BigDecimal(amount)); e.setCurrency("RUB"); em.persist(e);
    }
    static void payment(EntityManager em, int id, AccountEntity a, SecurityEntity s, int type, String amount) {
        var e = new SecurityEventCashFlowEntity(); e.setId(id); e.setAccount(a); e.setSecurity(s); e.setCount(1);
        e.setTimestamp(Instant.parse("2025-01-02T11:00:00Z")); e.setCashFlowType(em.getReference(CashFlowTypeEntity.class, type));
        e.setValue(new BigDecimal(amount)); e.setCurrency("RUB"); em.persist(e);
    }
    static void quote(EntityManager em, int id, SecurityEntity s, String at, String price) {
        var q = new SecurityQuoteEntity(); q.setId(id); q.setSecurity(s); q.setTimestamp(Instant.parse(at));
        q.setQuote(new BigDecimal(price)); q.setPrice(new BigDecimal(price)); q.setCurrency("RUB"); em.persist(q);
    }
    static void cash(EntityManager em, int id, String account, String at, String amount, String currency) {
        var c = new AccountCashEntity(); c.setId(id); c.setAccount(account); c.setTimestamp(Instant.parse(at));
        c.setMarket("stock"); c.setValue(new BigDecimal(amount)); c.setCurrency(currency); em.persist(c);
    }
    static void rate(EntityManager em, String date, String value) {
        var pk = new ForeignExchangeRateEntityPk(); pk.setCurrencyPair("ZZZRUB"); pk.setDate(LocalDate.parse(date));
        var r = new ForeignExchangeRateEntity(); r.setPk(pk); r.setRate(new BigDecimal(value)); em.persist(r);
    }
}
