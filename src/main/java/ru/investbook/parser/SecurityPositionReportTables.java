/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.parser;

import org.spacious_team.broker.report_parser.api.ReportTable;

/** Implemented by broker parsers whose reports contain end-of-period security balances. */
public interface SecurityPositionReportTables {

    ReportTable<SecurityPosition> getSecurityPositionTable();
}
