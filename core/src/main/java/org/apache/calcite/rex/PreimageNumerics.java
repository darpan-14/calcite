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
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import static java.util.Objects.requireNonNull;

/**
 * Shared helpers for exact-numeric preimage derivation.
 *
 * <p>Both {@link PreimageProviders}' function handlers and
 * {@link BisectionPreimageProvider} need to reason about the finite value set
 * of an exact-numeric type and about {@link org.apache.calcite.util.Sarg}s
 * whose endpoints are {@link BigDecimal}s. The logic lives here so the two
 * derivations cannot drift apart.
 */
final class PreimageNumerics {
  private PreimageNumerics() {
  }

  /**
   * The finite set of values of an exact-numeric type, represented as
   * consecutive integers scaled by {@link #scale}.
   *
   * <p>A value {@code v} of the type is {@code v = unscaled * 10^-scale} for an
   * integer {@code unscaled} in {@code [minimum, maximum]}. This integer
   * encoding lets callers bisect a continuous domain over a discrete, totally
   * ordered index.
   */
  static final class ExactNumericDomain {
    final RelDataType type;
    final int scale;
    final BigInteger minimum;
    final BigInteger maximum;

    private ExactNumericDomain(RelDataType type, int scale, BigInteger minimum,
        BigInteger maximum) {
      this.type = type;
      this.scale = scale;
      this.minimum = minimum;
      this.maximum = maximum;
    }

    /** Returns the domain of an exact-numeric type, or null if the type is not
     * a supported finite exact-numeric type. */
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

    /** Returns the decimal value for an unscaled integer coordinate. */
    BigDecimal value(BigInteger coordinate) {
      return new BigDecimal(coordinate, scale);
    }

    /** Returns the closed decimal range this domain covers. */
    Range<BigDecimal> range() {
      return Range.closed(value(minimum), value(maximum));
    }
  }

  /**
   * Casts a {@code Sarg}'s range set to {@code RangeSet<BigDecimal>}, or
   * returns null if any endpoint is not a {@link BigDecimal}.
   *
   * <p>Numeric result domains always carry {@code BigDecimal} endpoints; this
   * both documents that assumption and guards against a caller passing a Sarg
   * over some other comparable type.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static @Nullable RangeSet<BigDecimal> decimalRanges(Sarg<?> domain) {
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

  /**
   * Normalizes a preimage range set against a finite operand domain.
   *
   * <p>When the derived preimage covers the operand type's entire value set,
   * the domain restriction is redundant, so this returns the unbounded range
   * set. That keeps the resulting Sarg as simple as possible and lets a
   * downstream simplification drop a tautological {@code SEARCH}.
   */
  static RangeSet<BigDecimal> normalizeToOperandDomain(
      RangeSet<BigDecimal> preimage, Range<BigDecimal> finiteDomain) {
    return preimage.equals(ImmutableRangeSet.of(finiteDomain))
        ? RangeSets.rangeSetAll()
        : preimage;
  }

  /**
   * Re-scales every finite endpoint of a range set to the domain's scale.
   *
   * <p>Boundary math produces endpoints at an arbitrary scale (for example a
   * reflected or clamped {@code BigDecimal.ZERO} at scale 0), but a Sarg's
   * literals should match the operand type's declared scale. This normalizes
   * them with an exact ({@link RoundingMode#UNNECESSARY}) set-scale, which is
   * always safe here because every endpoint of a preimage over an
   * exact-numeric domain is representable at that scale. The unbounded range
   * set is returned unchanged.
   */
  static RangeSet<BigDecimal> rescaleToDomain(RangeSet<BigDecimal> ranges,
      ExactNumericDomain domain) {
    if (ranges.equals(RangeSets.<BigDecimal>rangeSetAll())) {
      return ranges;
    }
    return RangeSets.copy(ranges,
        value -> value.setScale(domain.scale, RoundingMode.UNNECESSARY));
  }

  /**
   * Converts every finite endpoint of a result domain to {@code targetType},
   * or returns null unless every endpoint is exactly representable without
   * rounding or overflow.
   *
   * <p>The {@code nullAs} policy is carried through unchanged. This is used
   * when a function maps its operand to a result of a different exact-numeric
   * type by an identity or lossless relationship (for example a lossless cast,
   * or {@code FLOOR} of an already-integral operand).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static @Nullable Sarg<BigDecimal> convertDomain(Sarg<?> resultDomain,
      RelDataType targetType) {
    for (Object rangeObject : resultDomain.rangeSet.asRanges()) {
      final Range<?> range = (Range<?>) rangeObject;
      if (range.hasLowerBound()
          && convertEndpoint(range.lowerEndpoint(), targetType) == null) {
        return null;
      }
      if (range.hasUpperBound()
          && convertEndpoint(range.upperEndpoint(), targetType) == null) {
        return null;
      }
    }
    final RangeSet<BigDecimal> rangeSet =
        RangeSets.copy((RangeSet) resultDomain.rangeSet,
            value -> requireNonNull(convertEndpoint(value, targetType)));
    return Sarg.of(resultDomain.nullAs, rangeSet);
  }

  /** Converts one endpoint to {@code targetType} without rounding or overflow,
   * or returns null if it is not exactly representable. */
  static @Nullable BigDecimal convertEndpoint(Object endpoint,
      RelDataType targetType) {
    if (!(endpoint instanceof BigDecimal)) {
      return null;
    }
    final BigDecimal value = (BigDecimal) endpoint;
    if (targetType.getSqlTypeName() == SqlTypeName.DECIMAL) {
      if (!SqlTypeUtil.canBeRepresentedExactly(value, targetType)) {
        return null;
      }
      return value.setScale(targetType.getScale(), RoundingMode.UNNECESSARY);
    }
    if (!SqlTypeUtil.isIntType(targetType)) {
      return null;
    }
    final BigInteger integer;
    try {
      integer = value.toBigIntegerExact();
    } catch (ArithmeticException ignored) {
      return null;
    }
    final BigInteger min = SqlTypeUtil.integerBound(targetType, false);
    final BigInteger max = SqlTypeUtil.integerBound(targetType, true);
    if (min == null || max == null
        || integer.compareTo(min) < 0
        || integer.compareTo(max) > 0) {
      return null;
    }
    return new BigDecimal(integer);
  }
}
