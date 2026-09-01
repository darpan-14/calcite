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

import org.apache.calcite.plan.Strong;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlMonotonicity;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.BoundType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives exact preimages of monotonic functions by bisection.
 *
 * <p>The provider currently supports deterministic unary functions over finite
 * exact-numeric domains. It treats an increasing or decreasing result from
 * {@link org.apache.calcite.sql.SqlOperator#getMonotonicity} as a contract that
 * the function is total and order-preserving over the operand's non-null
 * domain. Every probe is evaluated by the planner's {@link RexExecutor}; the
 * provider declines the rewrite if no executor is available or a probe cannot
 * be reduced to a non-null literal.
 */
public final class BisectionPreimageProvider implements PreimageProvider {
  /** Default bisection provider. */
  public static final BisectionPreimageProvider INSTANCE =
      new BisectionPreimageProvider();

  private BisectionPreimageProvider() {
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    // CAST has special binding semantics and already has an algebraic provider.
    if (call.getOperator() == SqlStdOperatorTable.CAST
        || call.getOperands().size() != 1
        || !call.getOperator().isDeterministic()
        || Strong.policy(call.getOperator()) != Strong.Policy.ANY
        || context.executor() == null) {
      return null;
    }

    final RexNode operand = call.getOperands().get(0);
    final RelDataType operandType = operand.getType();
    final ExactNumericDomain operandDomain =
        ExactNumericDomain.of(operandType);
    if (operandDomain == null
        || !SqlTypeUtil.isExactNumeric(call.getType())
        // A call that narrows nullability may not preserve SEARCH's null policy.
        || operandType.isNullable() && !call.getType().isNullable()) {
      return null;
    }

    final RangeSet<BigDecimal> resultRanges = decimalRanges(resultDomain);
    if (resultRanges == null) {
      return null;
    }
    final SqlMonotonicity monotonicity = monotonicity(call, context);
    final boolean increasing;
    switch (monotonicity) {
    case STRICTLY_INCREASING:
    case INCREASING:
      increasing = true;
      break;
    case STRICTLY_DECREASING:
    case DECREASING:
      increasing = false;
      break;
    default:
      return null;
    }

    final Probe probe = new Probe(call, operandDomain, context);
    final TreeRangeSet<BigDecimal> preimage = TreeRangeSet.create();
    for (Range<BigDecimal> resultRange : resultRanges.asRanges()) {
      final @Nullable Range<BigDecimal> inputRange =
          inputRange(resultRange, increasing, operandDomain, probe);
      if (!probe.ok) {
        return null;
      }
      if (inputRange != null) {
        preimage.add(inputRange);
      }
    }

    final Range<BigDecimal> finiteDomain = operandDomain.range();
    final RangeSet<BigDecimal> normalized =
        preimage.equals(ImmutableRangeSet.of(finiteDomain))
            ? RangeSets.rangeSetAll()
            : preimage;
    return new Preimage<>(operand, Sarg.of(resultDomain.nullAs, normalized));
  }

  /** Returns the call's monotonicity when its only operand increases. */
  private static SqlMonotonicity monotonicity(RexCall call,
      PreimageContext context) {
    final RexCallBinding binding =
        new RexCallBinding(context.rexBuilder().getTypeFactory(),
            call.getOperator(), call.getOperands(), ImmutableList.of()) {
          @Override public SqlMonotonicity getOperandMonotonicity(int ordinal) {
            return ordinal == 0
                ? SqlMonotonicity.STRICTLY_INCREASING
                : SqlMonotonicity.NOT_MONOTONIC;
          }
        };
    return call.getOperator().getMonotonicity(binding);
  }

  /** Returns the input interval whose function results occur in one result
   * interval. */
  private static @Nullable Range<BigDecimal> inputRange(
      Range<BigDecimal> resultRange, boolean increasing,
      ExactNumericDomain domain, Probe probe) {
    final @Nullable BigInteger lower;
    final @Nullable BigInteger upper;
    if (increasing) {
      lower = increasingLower(resultRange, domain, probe);
      upper = increasingUpper(resultRange, domain, probe);
    } else {
      lower = decreasingLower(resultRange, domain, probe);
      upper = decreasingUpper(resultRange, domain, probe);
    }
    if (lower == null || upper == null) {
      return null;
    }

    final BigInteger clippedLower = lower.max(domain.minimum);
    final BigInteger clippedUpper = upper.min(domain.maximum);
    if (clippedLower.compareTo(clippedUpper) > 0) {
      return null;
    }
    return Range.closed(domain.value(clippedLower), domain.value(clippedUpper));
  }

  private static @Nullable BigInteger increasingLower(
      Range<BigDecimal> range, ExactNumericDomain domain, Probe probe) {
    if (!range.hasLowerBound()) {
      return domain.minimum;
    }
    return firstTrue(probe, range.lowerEndpoint(),
        range.lowerBoundType() == BoundType.OPEN, domain);
  }

  private static @Nullable BigInteger increasingUpper(
      Range<BigDecimal> range, ExactNumericDomain domain, Probe probe) {
    if (!range.hasUpperBound()) {
      return domain.maximum;
    }
    final BigInteger firstExcluded =
        firstTrue(probe, range.upperEndpoint(),
            range.upperBoundType() == BoundType.CLOSED, domain);
    return firstExcluded == null ? null : firstExcluded.subtract(BigInteger.ONE);
  }

  private static @Nullable BigInteger decreasingLower(
      Range<BigDecimal> range, ExactNumericDomain domain, Probe probe) {
    if (!range.hasUpperBound()) {
      return domain.minimum;
    }
    final BigInteger lastExcluded =
        lastTrue(probe, range.upperEndpoint(),
            range.upperBoundType() == BoundType.CLOSED, domain);
    return lastExcluded == null ? null : lastExcluded.add(BigInteger.ONE);
  }

  private static @Nullable BigInteger decreasingUpper(
      Range<BigDecimal> range, ExactNumericDomain domain, Probe probe) {
    if (!range.hasLowerBound()) {
      return domain.maximum;
    }
    return lastTrue(probe, range.lowerEndpoint(),
        range.lowerBoundType() == BoundType.OPEN, domain);
  }

  /** Finds the first input where {@code f(input) >= boundary}, or where it is
   * greater when {@code strict} is true. */
  private static @Nullable BigInteger firstTrue(Probe probe,
      BigDecimal boundary, boolean strict, ExactNumericDomain domain) {
    final @Nullable Boolean highValue =
        probe.test(domain.maximum, boundary, strict);
    if (highValue == null) {
      return null;
    }
    if (!highValue) {
      return domain.maximum.add(BigInteger.ONE);
    }
    final @Nullable Boolean lowValue =
        probe.test(domain.minimum, boundary, strict);
    if (lowValue == null) {
      return null;
    }
    if (lowValue) {
      return domain.minimum;
    }

    BigInteger low = domain.minimum;
    BigInteger high = domain.maximum;
    while (high.subtract(low).compareTo(BigInteger.ONE) > 0) {
      final BigInteger middle = midpoint(low, high);
      final @Nullable Boolean middleValue =
          probe.test(middle, boundary, strict);
      if (middleValue == null) {
        return null;
      }
      if (middleValue) {
        high = middle;
      } else {
        low = middle;
      }
    }
    return high;
  }

  /** Finds the last input where {@code f(input) >= boundary}, or where it is
   * greater when {@code strict} is true. */
  private static @Nullable BigInteger lastTrue(Probe probe,
      BigDecimal boundary, boolean strict, ExactNumericDomain domain) {
    final @Nullable Boolean lowValue =
        probe.test(domain.minimum, boundary, strict);
    if (lowValue == null) {
      return null;
    }
    if (!lowValue) {
      return domain.minimum.subtract(BigInteger.ONE);
    }
    final @Nullable Boolean highValue =
        probe.test(domain.maximum, boundary, strict);
    if (highValue == null) {
      return null;
    }
    if (highValue) {
      return domain.maximum;
    }

    BigInteger low = domain.minimum;
    BigInteger high = domain.maximum;
    while (high.subtract(low).compareTo(BigInteger.ONE) > 0) {
      final BigInteger middle = midpoint(low, high);
      final @Nullable Boolean middleValue =
          probe.test(middle, boundary, strict);
      if (middleValue == null) {
        return null;
      }
      if (middleValue) {
        low = middle;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static BigInteger midpoint(BigInteger lower, BigInteger upper) {
    return lower.add(upper.subtract(lower).shiftRight(1));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
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
    return (RangeSet) domain.rangeSet;
  }

  /** Evaluates and caches the function at bisection coordinates. */
  private static class Probe {
    private final RexCall call;
    private final ExactNumericDomain domain;
    private final PreimageContext context;
    private final Map<BigInteger, BigDecimal> values = new HashMap<>();
    private boolean ok = true;

    Probe(RexCall call, ExactNumericDomain domain, PreimageContext context) {
      this.call = call;
      this.domain = domain;
      this.context = context;
    }

    @Nullable Boolean test(BigInteger coordinate, BigDecimal boundary,
        boolean strict) {
      final @Nullable BigDecimal value = value(coordinate);
      if (value == null) {
        return null;
      }
      final int comparison = value.compareTo(boundary);
      return strict ? comparison > 0 : comparison >= 0;
    }

    private @Nullable BigDecimal value(BigInteger coordinate) {
      if (values.containsKey(coordinate)) {
        return values.get(coordinate);
      }
      final RexExecutor executor = context.executor();
      if (executor == null) {
        ok = false;
        return null;
      }
      final RexBuilder rexBuilder = context.rexBuilder();
      final RelDataType literalType =
          rexBuilder.getTypeFactory().createTypeWithNullability(domain.type,
              false);
      final RexLiteral literal =
          rexBuilder.makeExactLiteral(domain.value(coordinate), literalType);
      final RexCall probeCall =
          call.clone(call.getType(), ImmutableList.of(literal));
      final List<RexNode> reduced = new ArrayList<>(1);
      try {
        executor.reduce(rexBuilder, ImmutableList.of(probeCall), reduced);
      } catch (RuntimeException ignored) {
        ok = false;
        return null;
      }
      if (reduced.size() != 1 || !RexUtil.isLiteral(reduced.get(0), true)
          || RexUtil.isNullLiteral(reduced.get(0), true)) {
        ok = false;
        return null;
      }
      final @Nullable BigDecimal value;
      try {
        value = RexLiteral.bigDecimalValue(reduced.get(0));
      } catch (RuntimeException ignored) {
        ok = false;
        return null;
      }
      if (value == null) {
        ok = false;
        return null;
      }
      values.put(coordinate, value);
      return value;
    }
  }

  /** Finite exact-numeric domain represented by consecutive integers. */
  private static class ExactNumericDomain {
    final RelDataType type;
    final int scale;
    final BigInteger minimum;
    final BigInteger maximum;

    ExactNumericDomain(RelDataType type, int scale, BigInteger minimum,
        BigInteger maximum) {
      this.type = type;
      this.scale = scale;
      this.minimum = minimum;
      this.maximum = maximum;
    }

    static @Nullable ExactNumericDomain of(RelDataType type) {
      if (SqlTypeUtil.isIntType(type)) {
        final BigInteger minimum = SqlTypeUtil.integerBound(type, false);
        final BigInteger maximum = SqlTypeUtil.integerBound(type, true);
        return minimum == null || maximum == null ? null
            : new ExactNumericDomain(type, 0, minimum, maximum);
      }
      if (type.getSqlTypeName() != SqlTypeName.DECIMAL
          || type.getPrecision() <= 0 || type.getScale() < 0) {
        return null;
      }
      final BigInteger maximum =
          BigInteger.TEN.pow(type.getPrecision()).subtract(BigInteger.ONE);
      return new ExactNumericDomain(type, type.getScale(), maximum.negate(),
          maximum);
    }

    BigDecimal value(BigInteger coordinate) {
      return new BigDecimal(coordinate, scale);
    }

    Range<BigDecimal> range() {
      return Range.closed(value(minimum), value(maximum));
    }
  }
}
