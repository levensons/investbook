/* InvestBook. Copyright (C) 2026 Spacious Team. AGPL-3.0-or-later. */
package ru.investbook.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import ru.investbook.entity.AccountEntity;
import ru.investbook.repository.AccountRepository;
import ru.investbook.report.excel.AccountAnalysisChartService;
import ru.investbook.service.AssetsAndCashService;
import ru.investbook.service.InvestmentProportionService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsSelectionTest {
    private final AccountRepository repository = mock(AccountRepository.class);
    private final AssetsAndCashService assets = mock(AssetsAndCashService.class);
    private final InvestmentProportionService proportions = mock(InvestmentProportionService.class);
    private final AccountAnalysisChartService history = mock(AccountAnalysisChartService.class);

    private void accounts() {
        AccountEntity first = new AccountEntity(); first.setId("first"); first.setEnabled(true);
        AccountEntity second = new AccountEntity(); second.setId("second"); second.setEnabled(false);
        when(repository.findAll()).thenReturn(List.of(first, second));
        when(repository.findByEnabledIsTrue()).thenReturn(Set.of(first));
        when(assets.getActiveAccounts()).thenReturn(Set.of("first"));
    }
    @Test void bothCompositionTabsUseExplicitSelectionIncludingAnArchivedAccount() {
        accounts();
        when(proportions.getSecuritiesProportion(Set.of("second"))).thenReturn(Map.of("TEST", 100f));
        when(proportions.getSectorsProportion(Set.of("second"))).thenReturn(Map.of("SECTOR", 100f));
        var controller = new AccountCompositionController(proportions, assets, repository);
        var model = new ExtendedModelMap();
        assertEquals("charts/securities-pie-chart", controller.getSecuritiesProportionPage(model, new MockHttpServletRequest(), Set.of("second")));
        assertEquals(Set.of("second"), model.get("selectedAccounts"));
        assertEquals("charts/sectors-pie-chart", controller.getSectorsProportionPage(model, new MockHttpServletRequest(), Set.of("second")));
        verify(proportions).getSecuritiesProportion(Set.of("second"));
        verify(proportions).getSectorsProportion(Set.of("second"));
        verify(assets, times(2)).getTotalCashInRub(Set.of("second"));
        verify(assets, never()).getTotalCashInRub(Set.of("first"));
    }
    @Test void omittedSelectionUsesActiveAccountsAndRejectsUnknownAccounts() {
        accounts();
        var controller = new AccountCompositionController(proportions, assets, repository);
        controller.getSecuritiesProportionPage(new ExtendedModelMap(), new MockHttpServletRequest(), null);
        verify(proportions).getSecuritiesProportion(Set.of("first"));
        assertThrows(ResponseStatusException.class, () -> controller.getSecuritiesProportionPage(new ExtendedModelMap(), new MockHttpServletRequest(), Set.of("unknown")));
        assertThrows(ResponseStatusException.class, () -> controller.getSectorsProportionPage(new ExtendedModelMap(), new MockHttpServletRequest(), Set.of()));
    }
    @Test void noActiveAccountsNeverMeansAllLegacyAccounts() {
        when(repository.findAll()).thenReturn(List.of());
        when(assets.getActiveAccounts()).thenReturn(Set.of());
        var controller = new AccountCompositionController(proportions, assets, repository);
        controller.getSecuritiesProportionPage(new ExtendedModelMap(), new MockHttpServletRequest(), null);
        verifyNoInteractions(proportions);
        verify(assets, never()).getTotalCashInRub(anySet());
        var analysis = new AccountAnalysisChartController(history, repository);
        assertEquals(List.of(), analysis.getData(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1), null));
        verifyNoInteractions(history);
    }
    @Test void historyUsesSelectedScopeAndRejectsUnknownOrEmptySelection() {
        accounts();
        var controller = new AccountAnalysisChartController(history, repository);
        var from = LocalDate.of(2025, 1, 1); var to = LocalDate.of(2025, 2, 1);
        controller.getData(from, to, Set.of("second"));
        verify(history).getChartPoints(argThat(filter -> filter.getAccounts().equals(Set.of("second"))));
        assertThrows(ResponseStatusException.class, () -> controller.getData(from, to, Set.of("unknown")));
        assertThrows(ResponseStatusException.class, () -> controller.getData(from, to, Set.of()));
    }
}
