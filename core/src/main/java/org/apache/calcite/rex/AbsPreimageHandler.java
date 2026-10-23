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
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;

/**
 * Preimage handler for {@code ABS} over exact-numeric operands.
 *
 * <p>{@code ABS} is not monotonic, so it is a good illustration that a
 * preimage is a domain transformation rather than a scalar inverse. The
 * mathematical preimage of a result range {@code [lo, hi]} with {@code lo >= 0}
 * is the symmetric union {@code [-hi, -lo] &cup; [lo, hi]}; the point
 * {@code ABS(x) = c} therefore maps to {@code x IN (-c, c)}.
 *
 * <h2>Overflow</h2>
 *
 * <p>For a two's-complement integer type the domain is asymmetric
 * ({@code min = -(max + 1)}), and {@code ABS(min)} overflows. Because a
 * rewrite to {@code SEARCH(x, ...)} cannot reproduce that runtime error, this
 * handler declines whenever the operand's minimum lies below {@code -max},
 * i.e. for the asymmetric integer types. {@code DECIMAL} has a symmetric
 * declared range, so {@code ABS} never overflows and is always eligible.
 *
 * <h2>Null</h2>
 *
 * <p>{@code ABS} propagates null. The derived Sarg keeps the predicate's own
 * {@code nullAs} policy, so a null operand is treated exactly as it was for
 * {@code ABS(x)}.
 */
final class AbsPreimageHandler implements PreimageHandler {
  static final AbsPreimageHandler INSTANCE = new AbsPreimageHandler();

  private AbsPreimageHandler() {
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    if (call.getOperands().size() != 1) {
      return null;
    }
    final RexNode operand = call.getOperands().get(0);
    final RelDataType sourceType = operand.getType();
    if (!SqlTypeUtil.isExactNumeric(sourceType)
        || !SqlTypeUtil.isExactNumeric(call.getType())
        // ABS is strict; a nullable input must have a nullable result for the
        // Sarg's null policy to remain valid.
        || sourceType.isNullable() && !call.getType().isNullable()) {
      return null;
    }

    final ExactNumericDomain domain = ExactNumericDomain.of(sourceType);
    final RangeSet<BigDecimal> resultRanges =
        PreimageNumerics.decimalRanges(resultDomain);
    if (domain == null || resultRanges == null) {
      return null;
    }

    // ABS(min) overflows when the operand domain is asymmetric, as it is for
    // two's-complement integers. A SEARCH on the operand cannot reproduce that
    // error, so the rewrite would not be an exact equivalence.
    final BigDecimal min = domain.value(domain.minimum);
    final BigDecimal max = domain.value(domain.maximum);
    if (min.negate().compareTo(max) > 0) {
      return null;
    }

    final TreeRangeSet<BigDecimal> preimage = TreeRangeSet.create();
    for (Range<BigDecimal> range : resultRanges.asRanges()) {
      // ABS(x) only takes non-negative values; intersect the result range
      // with [0, +inf) before mirroring it onto the operand axis.
      final Range<BigDecimal> nonNegative = clampToNonNegative(range);
      if (nonNegative == null) {
        continue;
      }
      preimage.add(nonNegative);
      preimage.add(mirror(nonNegative));
    }

    final RangeSet<BigDecimal> normalized =
        PreimageNumerics.normalizeToOperandDomain(
            preimage.subRangeSet(domain.range()), domain.range());
    return new Preimage<>(operand,
        Sarg.of(resultDomain.nullAs,
            PreimageNumerics.rescaleToDomain(normalized, domain)));
  }

  /** Intersects a result range with {@code [0, +inf)}, or returns null if it
   * contains no non-negative value. */
  private static @Nullable Range<BigDecimal> clampToNonNegative(
      Range<BigDecimal> range) {
    final Range<BigDecimal> nonNegative = Range.atLeast(BigDecimal.ZERO);
    if (!range.isConnected(nonNegative)) {
      return null;
    }
    final Range<BigDecimal> intersection = range.intersection(nonNegative);
    return intersection.isEmpty() ? null : intersection;
  }

  /** Reflects a clamped non-negative range across zero. The clamped range is
   * always lower-bounded (at worst by 0), so {@code [lo, hi]} maps to
   * {@code [-hi, -lo]} and {@code [lo, +inf)} maps to {@code (-inf, -lo]}. */
  private static Range<BigDecimal> mirror(Range<BigDecimal> range) {
    final BigDecimal newUpper = range.lowerEndpoint().negate();
    final BoundType newUpperType = range.lowerBoundType();
    if (!range.hasUpperBound()) {
      return Range.upTo(newUpper, newUpperType);
    }
    final BigDecimal newLower = range.upperEndpoint().negate();
    final BoundType newLowerType = range.upperBoundType();
    return Range.range(newLower, newLowerType, newUpper, newUpperType);
  }
}
