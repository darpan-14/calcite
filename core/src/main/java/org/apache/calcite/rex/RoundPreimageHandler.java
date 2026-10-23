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
 * Preimage handler for single-argument {@code ROUND} over exact-numeric
 * operands.
 *
 * <p>Calcite's {@code ROUND} rounds half away from zero
 * ({@link RoundingMode#HALF_UP}), so the point preimage is <em>not</em>
 * symmetric and depends on the sign of the result:
 *
 * <blockquote><pre>
 * ROUND(x) =  k  (k &gt; 0)  &lt;=&gt;  x IN [k - 0.5, k + 0.5)
 * ROUND(x) =  k  (k &lt; 0)  &lt;=&gt;  x IN (k - 0.5, k + 0.5]
 * ROUND(x) =  0            &lt;=&gt;  x IN (-0.5, 0.5)
 * </pre></blockquote>
 *
 * <p>The handler applies the matching half-open rule to each boundary of the
 * result domain and clips to the declared operand type. Only the two-argument
 * form with a non-zero scale is out of scope; the unary form is equivalent to
 * {@code ROUND(x, 0)}.
 *
 * <p>Approximate numeric operands are declined (NaN/infinity/rounding
 * semantics need a separate proof); the two-argument {@code ROUND(x, n)} form
 * is declined so this stays a pure unary rounding rewrite.
 */
final class RoundPreimageHandler implements PreimageHandler {
  static final RoundPreimageHandler INSTANCE = new RoundPreimageHandler();

  private static final BigDecimal HALF = new BigDecimal("0.5");

  private RoundPreimageHandler() {
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperands().size() != 1) {
      // ROUND(x, n) with an explicit scale is out of scope.
      return null;
    }
    final RexNode operand = call.getOperands().get(0);
    final RelDataType sourceType = operand.getType();
    if (!(SqlTypeUtil.isIntType(sourceType)
        || sourceType.getSqlTypeName() == SqlTypeName.DECIMAL)
        || !SqlTypeUtil.isExactNumeric(call.getType())
        // ROUND is strict; a nullable input must have a nullable result for the
        // Sarg's null policy to remain valid.
        || sourceType.isNullable() && !call.getType().isNullable()) {
      return null;
    }

    // An integral operand rounds to itself.
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

    final RangeSet<BigDecimal> normalized =
        PreimageNumerics.normalizeToOperandDomain(
            preimage.subRangeSet(domain.range()), domain.range());
    return new Preimage<>(operand,
        Sarg.of(resultDomain.nullAs,
            PreimageNumerics.rescaleToDomain(normalized, domain)));
  }

  /**
   * Returns the exact preimage of one result range under HALF_UP rounding.
   *
   * <p>A result value {@code r} is reached by exactly the inputs in the
   * half-open interval {@code lowerOf(r) .. upperOf(r)} (see the class
   * Javadoc). For a range, the preimage runs from the lower edge of its lowest
   * admissible result to the upper edge of its highest admissible result. The
   * edges are taken from the range's own endpoints: because every endpoint is
   * itself a value {@code ROUND} could return, {@code lowerOf}/{@code upperOf}
   * applied to the endpoints bound the union exactly.
   */
  private static @Nullable Range<BigDecimal> preimageOf(Range<BigDecimal> range) {
    final @Nullable BigDecimal lower;
    final @Nullable BigDecimal upper;
    if (range.hasLowerBound()) {
      // A closed lower bound admits its own result; an open one starts at the
      // next integer up.
      final BigDecimal first = range.lowerBoundType() == BoundType.CLOSED
          ? ceilToInteger(range.lowerEndpoint())
          : floorToInteger(range.lowerEndpoint()).add(BigDecimal.ONE);
      lower = lowerEdge(first);
    } else {
      lower = null;
    }
    if (range.hasUpperBound()) {
      final BigDecimal last = range.upperBoundType() == BoundType.CLOSED
          ? floorToInteger(range.upperEndpoint())
          : ceilToInteger(range.upperEndpoint()).subtract(BigDecimal.ONE);
      upper = upperEdge(last);
    } else {
      upper = null;
    }

    if (lower != null && upper != null && lower.compareTo(upper) > 0) {
      return null;
    }
    final BoundType lowerType = lower != null ? lowerEdgeType(lower) : null;
    final BoundType upperType = upper != null ? upperEdgeType(upper) : null;
    if (lower == null) {
      return upper == null ? Range.all()
          : Range.upTo(upper, upperType);
    }
    if (upper == null) {
      return Range.downTo(lower, lowerType);
    }
    return Range.range(lower, lowerType, upper, upperType);
  }

  /** Lower edge of the preimage interval of integer result {@code k}:
   * {@code k - 0.5}. */
  private static BigDecimal lowerEdge(BigDecimal k) {
    return k.subtract(HALF);
  }

  /** Upper edge of the preimage interval of integer result {@code k}:
   * {@code k + 0.5}. */
  private static BigDecimal upperEdge(BigDecimal k) {
    return k.add(HALF);
  }

  /** Bound type at {@code k - 0.5}: closed when {@code k >= 0} (half-up pulls
   * {@code k - 0.5} up to {@code k}), open when {@code k < 0}. The edge value
   * passed in is {@code k - 0.5}, so {@code k = edge + 0.5}. */
  private static BoundType lowerEdgeType(BigDecimal edge) {
    final BigDecimal k = edge.add(HALF);
    return k.signum() >= 0 ? BoundType.CLOSED : BoundType.OPEN;
  }

  /** Bound type at {@code k + 0.5}: open when {@code k >= 0}, closed when
   * {@code k < 0}. The edge value passed in is {@code k + 0.5}. */
  private static BoundType upperEdgeType(BigDecimal edge) {
    final BigDecimal k = edge.subtract(HALF);
    return k.signum() >= 0 ? BoundType.OPEN : BoundType.CLOSED;
  }

  private static BigDecimal floorToInteger(BigDecimal value) {
    return value.setScale(0, RoundingMode.FLOOR);
  }

  private static BigDecimal ceilToInteger(BigDecimal value) {
    return value.setScale(0, RoundingMode.CEILING);
  }
}
