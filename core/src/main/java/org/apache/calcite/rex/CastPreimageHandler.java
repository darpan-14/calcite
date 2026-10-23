/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rex;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Sarg;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;

/**
 * Preimage handler for a lossless, order-preserving cast between exact-numeric
 * types.
 *
 * <p>When the cast neither rounds, overflows, nor narrows nullability, the
 * predicate's result domain can be tested directly against the source operand,
 * provided every endpoint is exactly representable in the source type. For
 * example {@code CAST(smallint_col AS BIGINT) < 100} becomes
 * {@code smallint_col < 100}, but {@code ... < 40000} is declined because
 * {@code 40000} is outside {@code SMALLINT}.
 */
final class CastPreimageHandler implements PreimageHandler {
  static final CastPreimageHandler INSTANCE = new CastPreimageHandler();

  private CastPreimageHandler() {
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperands().size() != 1) {
      return null;
    }
    final RexNode operand = call.getOperands().get(0);
    final RelDataType sourceType = operand.getType();
    final RelDataType targetType = call.getType();
    if (!SqlTypeUtil.isExactNumeric(sourceType)
        || !SqlTypeUtil.isExactNumeric(targetType)
        || !RexUtil.isLosslessCast(sourceType, targetType)
        // A cast that narrows nullability may reject a null input.
        || sourceType.isNullable() && !targetType.isNullable()) {
      return null;
    }
    final Sarg<BigDecimal> sourceDomain =
        PreimageNumerics.convertDomain(resultDomain, sourceType);
    return sourceDomain == null ? null : new Preimage<>(operand, sourceDomain);
  }
}
