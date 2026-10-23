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
 * Preimage handler for unary minus ({@code -x}) over exact-numeric operands.
 *
 * <p>Unary minus is strictly decreasing, so the preimage of a result range is
 * that range reflected across zero: {@code -x IN [lo, hi]} iff
 * {@code x IN [-hi, -lo]}, with the bound types swapped. This is a closed-form
 * alternative to {@link BisectionPreimageProvider}, avoiding planner-time
 * evaluation.
 *
 * <p>The same overflow care as {@code ABS} applies: for a two's-complement
 * integer type, {@code -min} overflows, so this handler declines when the
 * operand domain is asymmetric. {@code DECIMAL} is symmetric and always
 * eligible. Null is propagated and the predicate's {@code nullAs} policy is
 * preserved.
 */
final class UnaryMinusPreimageHandler implements PreimageHandler {
  static final UnaryMinusPreimageHandler INSTANCE =
      new UnaryMinusPreimageHandler();

  private UnaryMinusPreimageHandler() {
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
        || sourceType.isNullable() && !call.getType().isNullable()) {
      return null;
    }

    final ExactNumericDomain domain = ExactNumericDomain.of(sourceType);
    final RangeSet<BigDecimal> resultRanges =
        PreimageNumerics.decimalRanges(resultDomain);
    if (domain == null || resultRanges == null) {
      return null;
    }

    // -min overflows for an asymmetric (two's-complement integer) domain.
    final BigDecimal min = domain.value(domain.minimum);
    final BigDecimal max = domain.value(domain.maximum);
    if (min.negate().compareTo(max) > 0) {
      return null;
    }

    final TreeRangeSet<BigDecimal> preimage = TreeRangeSet.create();
    for (Range<BigDecimal> range : resultRanges.asRanges()) {
      preimage.add(reflect(range));
    }
    final RangeSet<BigDecimal> normalized =
        PreimageNumerics.normalizeToOperandDomain(
            preimage.subRangeSet(domain.range()), domain.range());
    return new Preimage<>(operand,
        Sarg.of(resultDomain.nullAs,
            PreimageNumerics.rescaleToDomain(normalized, domain)));
  }

  /** Reflects {@code [lo, hi]} across zero to {@code [-hi, -lo]}, swapping
   * bound types and handling unbounded ends. */
  private static Range<BigDecimal> reflect(Range<BigDecimal> range) {
    if (!range.hasLowerBound() && !range.hasUpperBound()) {
      return Range.all();
    }
    if (!range.hasUpperBound()) {
      // [lo, +inf) -> (-inf, -lo]
      return Range.upTo(range.lowerEndpoint().negate(), range.lowerBoundType());
    }
    if (!range.hasLowerBound()) {
      // (-inf, hi] -> [-hi, +inf)
      return Range.downTo(range.upperEndpoint().negate(),
          range.upperBoundType());
    }
    final BigDecimal newLower = range.upperEndpoint().negate();
    final BoundType newLowerType = range.upperBoundType();
    final BigDecimal newUpper = range.lowerEndpoint().negate();
    final BoundType newUpperType = range.lowerBoundType();
    return Range.range(newLower, newLowerType, newUpper, newUpperType);
  }
}
