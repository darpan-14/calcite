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
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import static java.util.Objects.requireNonNull;

/**
 * Derives exact preimages using formulas for particular SQL functions.
 *
 * <p>This provider is intended to run before a generic provider because its
 * formulas do not require planner-time function evaluation. It returns null
 * when it does not recognize a call or cannot prove an exact preimage, allowing
 * a later provider to try.
 */
public final class FunctionPreimageProvider implements PreimageProvider {
  /** Default function-specific provider. */
  public static final FunctionPreimageProvider INSTANCE =
      new FunctionPreimageProvider();

  private FunctionPreimageProvider() {
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperator() == SqlStdOperatorTable.CAST) {
      return losslessNumericCast(call, resultDomain, context);
    }
    if (call.getOperator() == SqlStdOperatorTable.FLOOR
        || call.getOperator() == SqlStdOperatorTable.CEIL) {
      return exactNumericFloorCeil(call, resultDomain, context);
    }
    return null;
  }

  private static @Nullable Preimage<BigDecimal> losslessNumericCast(
      RexCall call, Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperator() != SqlStdOperatorTable.CAST
        || call.getOperands().size() != 1) {
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
        convertDomain(resultDomain, sourceType);
    return sourceDomain == null ? null : new Preimage<>(operand, sourceDomain);
  }

  private static @Nullable Preimage<BigDecimal> exactNumericFloorCeil(
      RexCall call, Sarg<?> resultDomain, PreimageContext context) {
    final boolean floor;
    if (call.getOperator() == SqlStdOperatorTable.FLOOR) {
      floor = true;
    } else if (call.getOperator() == SqlStdOperatorTable.CEIL) {
      floor = false;
    } else {
      return null;
    }
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
          convertDomain(resultDomain, sourceType);
      return sourceDomain == null ? null
          : new Preimage<>(operand, sourceDomain);
    }

    final RangeSet<BigDecimal> ranges = decimalRanges(resultDomain);
    final Range<BigDecimal> sourceDomain = exactNumericDomain(sourceType);
    if (ranges == null || sourceDomain == null) {
      return null;
    }

    final TreeRangeSet<BigDecimal> preimage = TreeRangeSet.create();
    for (Range<BigDecimal> range : ranges.asRanges()) {
      final @Nullable Range<BigDecimal> inputRange =
          floorCeilPreimage(range, floor);
      if (inputRange != null) {
        preimage.add(inputRange);
      }
    }

    // Sarg endpoints have to be representable as literals of the operand
    // type. Clipping is also required for exactness at the type boundaries;
    // for example, FLOOR(DECIMAL(3, 2)) = 10 has an empty preimage.
    final RangeSet<BigDecimal> clipped = preimage.subRangeSet(sourceDomain);
    final RangeSet<BigDecimal> normalized;
    if (clipped.equals(ImmutableRangeSet.of(sourceDomain))) {
      // The declared type already restricts every value to sourceDomain.
      normalized = RangeSets.rangeSetAll();
    } else {
      final int scale = SqlTypeUtil.isIntType(sourceType)
          ? 0
          : sourceType.getScale();
      normalized =
          RangeSets.copy(clipped, value -> value.setScale(scale, RoundingMode.UNNECESSARY));
    }
    return new Preimage<>(operand,
        Sarg.of(resultDomain.nullAs, normalized));
  }

  private static @Nullable RangeSet<BigDecimal> decimalRanges(
      Sarg<?> domain) {
    for (Object rangeObject : domain.rangeSet.asRanges()) {
      final Range<?> range = (Range<?>) rangeObject;
      if (range.hasLowerBound()
          && !(range.lowerEndpoint() instanceof BigDecimal)
          || range.hasUpperBound()
          && !(range.upperEndpoint() instanceof BigDecimal)) {
        return null;
      }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    final RangeSet<BigDecimal> ranges =
        (RangeSet) domain.rangeSet;
    return ranges;
  }

  /** Returns the exact preimage of one result range. */
  private static @Nullable Range<BigDecimal> floorCeilPreimage(
      Range<BigDecimal> range, boolean floor) {
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

    if (lower != null && upper != null
        && lower.compareTo(upper) >= 0) {
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

  /** Returns the finite domain of an exact numeric type. */
  private static @Nullable Range<BigDecimal> exactNumericDomain(
      RelDataType type) {
    final BigDecimal max;
    if (SqlTypeUtil.isIntType(type)) {
      final BigInteger bound = SqlTypeUtil.integerBound(type, true);
      if (bound == null) {
        return null;
      }
      max = new BigDecimal(bound);
    } else if (type.getSqlTypeName() == SqlTypeName.DECIMAL
        && type.getPrecision() > 0 && type.getScale() >= 0) {
      max = BigDecimal.TEN.pow(type.getPrecision())
          .subtract(BigDecimal.ONE)
          .movePointLeft(type.getScale())
          .setScale(type.getScale(), RoundingMode.UNNECESSARY);
    } else {
      return null;
    }
    final BigDecimal min;
    if (SqlTypeUtil.isIntType(type)) {
      final BigInteger bound = SqlTypeUtil.integerBound(type, false);
      if (bound == null) {
        return null;
      }
      min = new BigDecimal(bound);
    } else {
      min = max.negate();
    }
    return Range.closed(min, max);
  }

  /** Converts every finite endpoint to the source type. Returns null unless
   * every endpoint is exactly representable. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static @Nullable Sarg<BigDecimal> convertDomain(Sarg<?> resultDomain,
      RelDataType sourceType) {
    for (Object rangeObject : resultDomain.rangeSet.asRanges()) {
      final Range<?> range = (Range<?>) rangeObject;
      if (range.hasLowerBound()
          && convertEndpoint(range.lowerEndpoint(), sourceType) == null) {
        return null;
      }
      if (range.hasUpperBound()
          && convertEndpoint(range.upperEndpoint(), sourceType) == null) {
        return null;
      }
    }

    final RangeSet<BigDecimal> rangeSet =
        RangeSets.copy((RangeSet) resultDomain.rangeSet,
            value -> requireNonNull(convertEndpoint(value, sourceType)));
    return Sarg.of(resultDomain.nullAs, rangeSet);
  }

  /** Converts one result-domain endpoint to the source type without rounding
   * or overflow. */
  private static @Nullable BigDecimal convertEndpoint(Object endpoint,
      RelDataType sourceType) {
    if (!(endpoint instanceof BigDecimal)) {
      return null;
    }
    final BigDecimal value = (BigDecimal) endpoint;
    if (sourceType.getSqlTypeName() == SqlTypeName.DECIMAL) {
      if (!SqlTypeUtil.canBeRepresentedExactly(value, sourceType)) {
        return null;
      }
      return value.setScale(sourceType.getScale(), RoundingMode.UNNECESSARY);
    }
    if (!SqlTypeUtil.isIntType(sourceType)) {
      return null;
    }

    final BigInteger integer;
    try {
      integer = value.toBigIntegerExact();
    } catch (ArithmeticException ignored) {
      return null;
    }
    final BigInteger min = SqlTypeUtil.integerBound(sourceType, false);
    final BigInteger max = SqlTypeUtil.integerBound(sourceType, true);
    if (min == null || max == null
        || integer.compareTo(min) < 0
        || integer.compareTo(max) > 0) {
      return null;
    }
    return new BigDecimal(integer);
  }
}
