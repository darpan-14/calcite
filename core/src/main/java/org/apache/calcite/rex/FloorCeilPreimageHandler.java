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
import org.apache.calcite.rex.PreimageNumerics.ExactNumericDomain;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Preimage handler for unary {@code FLOOR} and {@code CEIL} over exact-numeric
 * operands.
 *
 * <p>For an exact numeric operand the point preimages are
 * {@code preimage(FLOOR, k) = [k, k + 1)} and
 * {@code preimage(CEIL, k) = (k - 1, k]}. The handler applies the matching
 * boundary transformation to every range of the result domain and clips to
 * the declared operand type.
 *
 * <p>Approximate numeric operands are declined: their NaN, infinity, and
 * rounding semantics need a separate proof. The datetime forms of
 * {@code FLOOR}/{@code CEIL} carry a time-unit operand and are handled by
 * {@link org.apache.calcite.rel.rules.DateRangeRules}.
 */
final class FloorCeilPreimageHandler implements PreimageHandler {
  /** Handler for {@code FLOOR}. */
  static final FloorCeilPreimageHandler FLOOR =
      new FloorCeilPreimageHandler(true);

  /** Handler for {@code CEIL}. */
  static final FloorCeilPreimageHandler CEIL =
      new FloorCeilPreimageHandler(false);

  private final boolean floor;

  private FloorCeilPreimageHandler(boolean floor) {
    this.floor = floor;
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperands().size() != 1) {
      // Datetime FLOOR and CEIL have a time-unit operand and are handled by
      // DateRangeRules.
      return null;
    }
    final RexNode operand = call.getOperands().get(0);
    final RelDataType sourceType = operand.getType();
    if (!(SqlTypeUtil.isIntType(sourceType)
        || sourceType.getSqlTypeName() == SqlTypeName.DECIMAL)
        || !SqlTypeUtil.isExactNumeric(call.getType())
        // FLOOR and CEIL are strict; a nullable input must have a nullable
        // result for the Sarg's null policy to remain valid.
        || sourceType.isNullable() && !call.getType().isNullable()) {
      return null;
    }

    // Exact numeric values with scale zero are already integral, therefore
    // unary FLOOR and CEIL are identity functions.
    if (SqlTypeUtil.isIntType(sourceType) || sourceType.getScale() == 0) {
      final Sarg<BigDecimal> sourceDomain =
          PreimageNumerics.convertDomain(resultDomain, sourceType);
      return sourceDomain == null ? null
          : new Preimage<>(operand, sourceDomain);
    }

    final RangeSet<BigDecimal> ranges =
        PreimageNumerics.decimalRanges(resultDomain);
    final ExactNumericDomain domain = ExactNumericDomain.of(sourceType);
    if (ranges == null || domain == null) {
      return null;
    }

    final TreeRangeSet<BigDecimal> preimage = TreeRangeSet.create();
    for (Range<BigDecimal> range : ranges.asRanges()) {
      final @Nullable Range<BigDecimal> inputRange = preimageOf(range);
      if (inputRange != null) {
        preimage.add(inputRange);
      }
    }

    // Sarg endpoints have to be representable as literals of the operand
    // type. Clipping is also required for exactness at the type boundaries;
    // for example, FLOOR(DECIMAL(3, 2)) = 10 has an empty preimage.
    final RangeSet<BigDecimal> normalized =
        PreimageNumerics.normalizeToOperandDomain(
            preimage.subRangeSet(domain.range()), domain.range());
    return new Preimage<>(operand,
        Sarg.of(resultDomain.nullAs,
            PreimageNumerics.rescaleToDomain(normalized, domain)));
  }

  /** Returns the exact preimage of one result range. */
  private @Nullable Range<BigDecimal> preimageOf(Range<BigDecimal> range) {
    final @Nullable BigDecimal lower;
    final @Nullable BigDecimal upper;
    if (range.hasLowerBound()) {
      final BigDecimal endpoint = range.lowerEndpoint();
      if (floor) {
        lower = range.lowerBoundType() == BoundType.CLOSED
            ? ceil(endpoint)
            : floor(endpoint).add(BigDecimal.ONE);
      } else {
        lower = range.lowerBoundType() == BoundType.CLOSED
            ? ceil(endpoint).subtract(BigDecimal.ONE)
            : floor(endpoint);
      }
    } else {
      lower = null;
    }
    if (range.hasUpperBound()) {
      final BigDecimal endpoint = range.upperEndpoint();
      if (floor) {
        upper = range.upperBoundType() == BoundType.CLOSED
            ? floor(endpoint).add(BigDecimal.ONE)
            : ceil(endpoint);
      } else {
        upper = range.upperBoundType() == BoundType.CLOSED
            ? floor(endpoint)
            : ceil(endpoint).subtract(BigDecimal.ONE);
      }
    } else {
      upper = null;
    }

    if (lower != null && upper != null && lower.compareTo(upper) >= 0) {
      return null;
    }
    if (lower == null) {
      return upper == null ? Range.all()
          : floor ? Range.lessThan(upper) : Range.atMost(upper);
    }
    if (upper == null) {
      return floor ? Range.atLeast(lower) : Range.greaterThan(lower);
    }
    return floor
        ? Range.closedOpen(lower, upper)
        : Range.openClosed(lower, upper);
  }

  private static BigDecimal floor(BigDecimal value) {
    return value.setScale(0, RoundingMode.FLOOR);
  }

  private static BigDecimal ceil(BigDecimal value) {
    return value.setScale(0, RoundingMode.CEILING);
  }
}
