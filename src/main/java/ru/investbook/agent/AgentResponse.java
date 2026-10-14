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

import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.List;
import java.util.Map;

/** Money and ratios inside data are decimal strings, never binary floating point. */
public record AgentResponse(String schema_version, Map<String, @Nullable Object> meta,
                            Object data, List<String> warnings) { }
