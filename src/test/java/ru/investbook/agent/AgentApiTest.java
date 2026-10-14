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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.spacious_team.broker.pojo.SecurityType;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import ru.investbook.entity.*;
import ru.investbook.report.ForeignExchangeRateService;
import java.time.Instant;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-api;mode=mysql;non_keywords=value",
        "investbook.agent.enabled=true", "investbook.agent.token=" + AgentFixture.TOKEN,
        "investbook.agent.accounts.main=PRIVATE-BROKER-A", "investbook.agent.accounts.second=PRIVATE-BROKER-B",
        "investbook.agent.accounts.empty=PRIVATE-EMPTY", "investbook.agent.accounts.fx=PRIVATE-FX"})
@Transactional
class AgentApiTest {
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy security;
    @Autowired EntityManager em;
    @Autowired ForeignExchangeRateService fx;
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        AgentFixture.populate(em); fx.invalidateCache();
    }
    MockHttpServletRequestBuilder read(String route) {
        return get("/api/agent/v1/" + route).header("Authorization", "Bearer " + AgentFixture.TOKEN);
    }
    MockHttpServletRequestBuilder dated(String route, String... accounts) {
        return read(route).param("account_ids", accounts).param("date_from", "2025-01-02").param("date_to", "2025-01-02");
    }

    @Test void authenticationAndLocalConnectionAreRequired() throws Exception {
        mvc.perform(get("/api/agent/v1/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/agent/v1/accounts").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(read("accounts").with(r -> { r.setRemoteAddr("198.51.100.1"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(read("accounts").header("X-Forwarded-For", "127.0.0.1")).andExpect(status().isForbidden());
        mvc.perform(read("accounts").header("Forwarded", "for=127.0.0.1")).andExpect(status().isForbidden());
    }
    @Test void writesAreRejectedAndExistingUiStillWorks() throws Exception {
        mvc.perform(post("/api/agent/v1/portfolio").header("Authorization", "Bearer " + AgentFixture.TOKEN))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void listOnlyAliasesWithoutPrivateAccountOrTradeIdentifiers() throws Exception {
        mvc.perform(read("accounts")).andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(content().string(not(containsString("PRIVATE-"))))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(dated("transactions", "main")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("private-trade"))))
                .andExpect(content().string(not(containsString("PRIVATE-"))));
    }
    @Test void everyAnalysisEndpointRejectsUnknownAndEmptyScope() throws Exception {
        for (String route : new String[]{"portfolio", "allocation", "income", "performance", "transactions"}) {
            mvc.perform(dated(route, "main", "PRIVATE-HIDDEN")).andExpect(status().isForbidden());
            mvc.perform(dated(route, "")).andExpect(status().isBadRequest());
            mvc.perform(read(route).param("date_from", "2025-01-02").param("date_to", "2025-01-02"))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test void portfolioIncludesPartialSaleCashAndStoredValuation() throws Exception {
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdings[0].quantity").value("8"))
                .andExpect(jsonPath("$.data.holdings[0].value_rub").value("880"))
                .andExpect(jsonPath("$.data.cash[0].amount").value("312"))
                .andExpect(jsonPath("$.data.total_rub").value("1192"));
    }
    @Test void missingQuoteNeverDisappearsFromCoverage() throws Exception {
        mvc.perform(read("portfolio").param("account_ids", "main", "second")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.valued_subtotal_rub").value("1792"))
                .andExpect(jsonPath("$.data.holdings[1].unvalued_reason").value("MISSING_QUOTE"));
    }
    @Test void emptyAccountWithZeroCashDiffersFromMissingCashHistory() throws Exception {
        mvc.perform(read("portfolio").param("account_ids", "empty")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdings", hasSize(0))).andExpect(jsonPath("$.data.total_rub").value("0"));
        em.createQuery("delete from AccountCashEntity c where c.account = 'PRIVATE-EMPTY'").executeUpdate();
        mvc.perform(read("portfolio").param("account_ids", "empty")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.warnings", hasItem("MISSING_CASH_SNAPSHOT:empty")));
    }
    @Test void allocationIncludesCashAndUsesDecimalFractions() throws Exception {
        mvc.perform(read("allocation").param("account_ids", "main").param("group_by", "sector"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.denominator_rub").value("1192"))
                .andExpect(jsonPath("$.data.groups[0].group").value("CASH"))
                .andExpect(jsonPath("$.data.groups[0].weight_fraction").value("0.261744966443"))
                .andExpect(jsonPath("$.data.groups[1].weight_fraction").value("0.738255033557"));
    }
    @Test void principalAndTaxesAreSeparateFromIncomeAndFiltersDoNotLeak() throws Exception {
        mvc.perform(dated("income", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].totals.dividend_rub").value("20"))
                .andExpect(jsonPath("$.data.groups[0].totals.coupon_rub").value("5"))
                .andExpect(jsonPath("$.data.groups[0].totals.amortization_rub").value("50"))
                .andExpect(jsonPath("$.data.groups[0].totals.tax_rub").value("-2"));
        mvc.perform(dated("income", "second")).andExpect(status().isOk()).andExpect(jsonPath("$.data.payments", hasSize(0)));
        mvc.perform(dated("transactions", "main")).andExpect(status().isOk()).andExpect(jsonPath("$.data.transactions", hasSize(1)));
    }
    @Test void performanceDoesNotCallValuationChangeProfitAndNetsInternalCashTransfer() throws Exception {
        mvc.perform(dated("performance", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opening.total_rub").value("1100"))
                .andExpect(jsonPath("$.data.closing.total_rub").value("1192"))
                .andExpect(jsonPath("$.data.value_change_rub").value("92"))
                .andExpect(jsonPath("$.data.recorded_net_cash_flow_rub").value("-100"))
                .andExpect(jsonPath("$.data.profit_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.twr").value(nullValue()));
        mvc.perform(dated("performance", "main", "second")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recorded_net_cash_flow_rub").value("0"));
    }
    @Test void historicalCashValuationUsesDifferentRatesAtTheBoundaries() throws Exception {
        mvc.perform(dated("performance", "fx")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opening.total_rub").value("8000"))
                .andExpect(jsonPath("$.data.closing.total_rub").value("10000"))
                .andExpect(jsonPath("$.data.value_change_rub").value("2000"));
    }
    @Test void paginationKeepsStableOrderAndFees() throws Exception {
        mvc.perform(read("transactions").param("account_ids", "main").param("date_from", "2025-01-01")
                        .param("date_to", "2025-01-02").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactions[0].source_id").value("transaction:910001"))
                .andExpect(jsonPath("$.data.has_more").value(true)).andExpect(jsonPath("$.data.next_offset").value(1));
        mvc.perform(read("transactions").param("account_ids", "main").param("date_from", "2025-01-01")
                        .param("date_to", "2025-01-02").param("limit", "1").param("offset", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transactions[0].source_id").value("transaction:910002"))
                .andExpect(jsonPath("$.data.transactions[0].cash_flows[1].amount").value("-1"))
                .andExpect(jsonPath("$.data.has_more").value(false));
    }
    @Test void inclusiveLocalDatesIncludeFinalFractionalSecondButExcludeFollowingMidnight() throws Exception {
        var t = em.find(TransactionEntity.class, 910001); t.setTimestamp(Instant.parse("2025-01-02T20:59:59.999Z"));
        var t2 = em.find(TransactionEntity.class, 910002); t2.setTimestamp(Instant.parse("2025-01-02T21:00:00Z")); em.flush();
        mvc.perform(dated("transactions", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactions", hasSize(1)))
                .andExpect(jsonPath("$.data.transactions[0].source_id").value("transaction:910001"));
    }
    @Test void invalidArgumentsFailBeforeCalculation() throws Exception {
        mvc.perform(dated("transactions", "main").param("limit", "201")).andExpect(status().isBadRequest());
        mvc.perform(read("income").param("account_ids", "main").param("date_from", "2025-01-03").param("date_to", "2025-01-02"))
                .andExpect(status().isBadRequest());
        mvc.perform(read("income").param("account_ids", "main").param("date_from", "bad").param("date_to", "2025-01-02"))
                .andExpect(status().isBadRequest());
        mvc.perform(read("allocation").param("account_ids", "main").param("group_by", "anything"))
                .andExpect(status().isBadRequest());
    }
    @Test void laterOperationsMakeCashIncompleteInsteadOfTreatingStaleCashAsCurrent() throws Exception {
        var trade = em.find(TransactionEntity.class, 910002);
        trade.setTimestamp(Instant.parse("2025-01-03T10:00:00Z")); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.warnings", hasItem("CASH_PRECEDES_OPERATIONS:main")));
    }
    @Test void brokerSnapshotIncludesOpeningPositionsAndAddsOnlySubsequentTrades() throws Exception {
        var p = new SecurityPositionEntity(); p.setId(970001); p.setAccount("PRIVATE-BROKER-A");
        p.setSecurity(em.getReference(SecurityEntity.class, 900001)); p.setCount(new java.math.BigDecimal("15.5"));
        p.setTimestamp(Instant.parse("2025-01-01T20:59:59Z")); em.persist(p); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdings[0].quantity").value("13.5"))
                .andExpect(jsonPath("$.data.total_rub").value("1797"));
    }
    @Test void missingFxDoesNotProduceZeroIncomeOrUseAFutureRate() throws Exception {
        var payment = em.find(SecurityEventCashFlowEntity.class, 940001);
        payment.setCurrency("YYY"); em.flush();
        mvc.perform(dated("income", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].totals.dividend_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.payments[0].amount").value("20"))
                .andExpect(jsonPath("$.warnings", hasItem("MISSING_FX:security_event_cash_flow:940001")));
    }
    @Test void incomeConvertsAtPaymentDateAndKeepsUnallocatedTaxSeparate() throws Exception {
        var payment = em.find(SecurityEventCashFlowEntity.class, 940001); payment.setCurrency("ZZZ");
        AgentFixture.event(em, 930004, em.getReference(AccountEntity.class, "PRIVATE-BROKER-A"),
                "2025-01-02T15:00:00Z", 10, "-3");
        AgentFixture.rate(em, "2025-01-03", "200"); em.flush(); fx.invalidateCache();
        mvc.perform(dated("income", "main").param("group_by", "security")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].totals.dividend_rub").value("2000"))
                .andExpect(jsonPath("$.data.groups[1].group").value("UNALLOCATED"))
                .andExpect(jsonPath("$.data.groups[1].totals.unallocated_tax_rub").value("-3"));
    }
    @Test void derivativesAndBondsWithoutAnAbsolutePriceStayUnvalued() throws Exception {
        var security = em.find(SecurityEntity.class, 900001);
        security.setType(org.spacious_team.broker.pojo.SecurityType.DERIVATIVE); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.holdings[0].unvalued_reason").value("UNSUPPORTED_ASSET_TYPE"));
        security.setType(org.spacious_team.broker.pojo.SecurityType.BOND);
        em.find(SecurityQuoteEntity.class, 950002).setPrice(null); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdings[0].unvalued_reason").value("MISSING_ABSOLUTE_PRICE"));
    }


    @ParameterizedTest
    @EnumSource(value = SecurityType.class, names = {"STOCK", "STOCK_OR_BOND", "ASSET"})
    void monetaryQuotesWithoutSeparatePriceAreValued(SecurityType type) throws Exception {
        em.find(SecurityEntity.class, 900001).setType(type);
        em.find(SecurityQuoteEntity.class, 950001).setPrice(null);
        em.find(SecurityQuoteEntity.class, 950002).setPrice(null); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuation_complete").value(true))
                .andExpect(jsonPath("$.data.total_rub").value("1192"))
                .andExpect(jsonPath("$.data.holdings[0].value_rub").value("880"))
                .andExpect(jsonPath("$.data.holdings[0].unvalued_reason").value(nullValue()));
        mvc.perform(dated("performance", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opening.total_rub").value("1100"))
                .andExpect(jsonPath("$.data.closing.total_rub").value("1192"));
        mvc.perform(read("allocation").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuation_complete").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3"})
    void genericBondQuoteRequiresAbsolutePriceEvenWithZeroAccruedInterest(String accrued) throws Exception {
        em.find(SecurityEntity.class, 900001).setType(SecurityType.STOCK_OR_BOND);
        var quote = em.find(SecurityQuoteEntity.class, 950002);
        quote.setPrice(null); quote.setAccruedInterest(new BigDecimal(accrued)); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuation_complete").value(false))
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.valued_subtotal_rub").value("312"))
                .andExpect(jsonPath("$.data.holdings[0].unvalued_reason").value("MISSING_ABSOLUTE_PRICE"));
    }

    @ParameterizedTest
    @EnumSource(value = SecurityType.class, names = {"BOND", "STOCK_OR_BOND"})
    void bondUsesAbsolutePriceAndAddsAccruedInterestOnce(SecurityType type) throws Exception {
        em.find(SecurityEntity.class, 900001).setType(type);
        var quote = em.find(SecurityQuoteEntity.class, 950002);
        quote.setQuote(new BigDecimal("101")); quote.setPrice(new BigDecimal("1010"));
        quote.setAccruedInterest(new BigDecimal("3")); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuation_complete").value(true))
                .andExpect(jsonPath("$.data.total_rub").value("8416"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingQuoteCurrencyIsReportedWithoutAssumingRubles(String currency) throws Exception {
        em.find(SecurityQuoteEntity.class, 950002).setCurrency(currency); em.flush();
        mvc.perform(read("portfolio").param("account_ids", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_rub").value(nullValue()))
                .andExpect(jsonPath("$.data.valued_subtotal_rub").value("312"))
                .andExpect(jsonPath("$.data.holdings[0].unvalued_reason").value("MISSING_QUOTE_CURRENCY"))
                .andExpect(jsonPath("$.warnings", hasItem("MISSING_QUOTE_CURRENCY:main:900001")));
    }

    @Test void genericMonetaryQuoteUsesHistoricalFxAndExposesStoredFields() throws Exception {
        em.find(SecurityEntity.class, 900001).setType(SecurityType.STOCK_OR_BOND);
        var quote = em.find(SecurityQuoteEntity.class, 950002);
        quote.setPrice(null); quote.setCurrency("ZZZ");
        AgentFixture.rate(em, "2025-01-03", "200"); em.flush(); fx.invalidateCache();
        mvc.perform(dated("performance", "main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closing.total_rub").value("88312"))
                .andExpect(jsonPath("$.data.closing.holdings[0].quote").value("110"))
                .andExpect(jsonPath("$.data.closing.holdings[0].price").value(nullValue()))
                .andExpect(jsonPath("$.data.closing.holdings[0].accrued_interest").value(nullValue()))
                .andExpect(jsonPath("$.data.closing.holdings[0].quote_currency").value("ZZZ"));
    }

}
