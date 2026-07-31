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

import java.math.BigDecimal;
import java.time.Instant;

/** A security balance reported by a broker at the end of a reporting period. */
public record SecurityPosition(String account, Integer security, Instant timestamp, BigDecimal count) {
}
