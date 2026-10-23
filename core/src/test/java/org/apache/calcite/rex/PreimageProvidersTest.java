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

import org.apache.calcite.plan.Contexts;
import org.apache.calcite.rel.rules.PreimageRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertSame;

import static java.util.Objects.requireNonNull;

/** Tests for built-in {@link PreimageProvider}s. */
class PreimageProvidersTest extends RexProgramBuilderBase {
  @Test void testDefaultProviderOrder() {
    assertThat(PreimageRules.FilterPreimageRule.FilterPreimageRuleConfig.DEFAULT
        .preimageProviders(), hasSize(2));
    assertSame(PreimageProviders.DEFAULT,
        PreimageRules.FilterPreimageRule.FilterPreimageRuleConfig.DEFAULT
            .preimageProviders().get(0));
    assertSame(BisectionPreimageProvider.INSTANCE,
        PreimageRules.FilterPreimageRule.FilterPreimageRuleConfig.DEFAULT
            .preimageProviders().get(1));
  }

  @Test void testLosslessNumericCast() {
    final RexNode operand = vSmallInt();
    final RexCall cast =
        (RexCall) rexBuilder.makeAbstractCast(tBigInt(true), operand, false);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.TRUE,
            ImmutableRangeSet.of(Range.lessThan(BigDecimal.valueOf(100))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
            cast, resultDomain, new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.TRUE,
            ImmutableRangeSet.of(
                Range.lessThan(BigDecimal.valueOf(100))))));
  }

  @Test void testRejectsEndpointOutsideSourceType() {
    final RexCall cast =
        (RexCall) rexBuilder.makeAbstractCast(tBigInt(true), vSmallInt(), false);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(
                Range.atLeast(BigDecimal.valueOf(40_000))));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
        cast, resultDomain, new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  @Test void testRejectsEndpointThatRequiresRounding() {
    RelDataType targetType =
        typeFactory.createSqlType(SqlTypeName.DECIMAL, 11, 1);
    targetType = typeFactory.createTypeWithNullability(targetType, true);
    final RexCall cast =
        (RexCall) rexBuilder.makeAbstractCast(targetType, vInt(), false);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(
                Range.lessThan(new BigDecimal("3.7"))));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
        cast, resultDomain, new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  @Test void testRejectsNarrowingCast() {
    final RexNode operand = input(tBigInt(true), 0);
    final RexCall cast =
        (RexCall) rexBuilder.makeAbstractCast(tInt(true), operand, false);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.ONE)));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
        cast, resultDomain, new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  @Test void testExactNumericFloorEquality() {
    final RelDataType type = decimalType(5, 2, true);
    final RexNode operand = input(type, 0);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(
                Range.closedOpen(new BigDecimal("5.00"),
                    new BigDecimal("6.00"))))));
  }

  @Test void testExactNumericCeilEquality() {
    final RelDataType type = decimalType(5, 2, true);
    final RexNode operand = input(type, 0);
    final RexCall ceil =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.CEIL, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.TRUE,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                ceil, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.TRUE,
            ImmutableRangeSet.of(
                Range.openClosed(new BigDecimal("4.00"),
                    new BigDecimal("5.00"))))));
  }

  @Test void testExactNumericFloorRange() {
    final RelDataType type = decimalType(5, 2, false);
    final RexNode operand = input(type, 0);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(
                Range.openClosed(BigDecimal.valueOf(-2),
                    BigDecimal.valueOf(2))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(
                Range.closedOpen(new BigDecimal("-1.00"),
                    new BigDecimal("3.00"))))));
  }

  @Test void testExactNumericCeilRange() {
    final RelDataType type = decimalType(5, 2, false);
    final RexNode operand = input(type, 0);
    final RexCall ceil =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.CEIL, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(
                Range.closedOpen(BigDecimal.valueOf(-2),
                    BigDecimal.valueOf(2))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                ceil, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(
                Range.openClosed(new BigDecimal("-3.00"),
                    new BigDecimal("1.00"))))));
  }

  @Test void testExactNumericFloorClipsToEmptyDomain() {
    final RelDataType type = decimalType(3, 2, true);
    final RexNode operand = input(type, 0);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.TEN)));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.<BigDecimal>of())));
  }

  @Test void testExactNumericFloorPreservesUnboundedDomain() {
    final RelDataType type = decimalType(3, 2, true);
    final RexNode operand = input(type, 0);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE, RangeSets.<BigDecimal>rangeSetAll());

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
            RangeSets.<BigDecimal>rangeSetAll())));
  }

  @Test void testRejectsApproximateNumericFloor() {
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR,
            input(tDouble(true), 0));
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.ONE)));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
            floor, resultDomain,
            new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  @Test void testBisectionIncreasingFunction() {
    final RelDataType type = decimalType(3, 1, true);
    final RexNode operand = input(type, 0);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            BisectionPreimageProvider.INSTANCE.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
                ImmutableRangeSet.of(
                    Range.closed(new BigDecimal("5.0"),
                        new BigDecimal("5.9"))))));
  }

  @Test void testBisectionDecreasingFunction() {
    final RelDataType type = decimalType(3, 1, false);
    final RexNode operand = input(type, 0);
    final RexCall negate =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            BisectionPreimageProvider.INSTANCE.getPreimage(
                negate, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
                ImmutableRangeSet.of(
                    Range.singleton(new BigDecimal("-5.0"))))));
  }

  @Test void testBisectionRequiresExecutor() {
    final RelDataType type = decimalType(3, 1, true);
    final RexCall floor =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.FLOOR,
            input(type, 0));
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.ONE)));

    assertThat(
        BisectionPreimageProvider.INSTANCE.getPreimage(
            floor, resultDomain,
            new PreimageContext(rexBuilder, null, -1)),
        nullValue());
  }

  @Test void testPreimageContextExposesPlannerServices() {
    final TestService service = new TestService();
    final PreimageContext context =
        new PreimageContext(rexBuilder, executor, -1, Contexts.of(service));

    assertSame(context, context.unwrap(PreimageContext.class));
    assertSame(service, context.unwrap(TestService.class));
  }

  // --- ABS ------------------------------------------------------------------

  @Test void testAbsEqualityIsSymmetricPair() {
    // ABS(x) = 5  <=>  x IN (-5, 5). ABS is not monotonic, so this exercises
    // the two-point (union of ranges) preimage.
    final RelDataType type = decimalType(5, 0, true);
    final RexNode operand = input(type, 0);
    final RexCall abs =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ABS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                abs, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
                ImmutableRangeSet.<BigDecimal>unionOf(
                    com.google.common.collect.ImmutableList.of(
                        Range.singleton(BigDecimal.valueOf(-5)),
                        Range.singleton(BigDecimal.valueOf(5)))))));
  }

  @Test void testAbsUpperBoundIsClosedInterval() {
    // ABS(x) <= 5  <=>  x IN [-5, 5].
    final RelDataType type = decimalType(5, 0, false);
    final RexNode operand = input(type, 0);
    final RexCall abs =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ABS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(Range.atMost(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                abs, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
                ImmutableRangeSet.of(
                    Range.closed(BigDecimal.valueOf(-5),
                        BigDecimal.valueOf(5))))));
  }

  @Test void testAbsPreservesNullPolicy() {
    // The predicate's nullAs policy (here TRUE, as from IS NULL semantics) must
    // carry through unchanged: a null operand behaves exactly as for ABS(x).
    final RelDataType type = decimalType(5, 0, true);
    final RexNode operand = input(type, 0);
    final RexCall abs =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ABS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.TRUE,
            ImmutableRangeSet.of(Range.atMost(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                abs, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg().nullAs, is(RexUnknownAs.TRUE));
  }

  @Test void testAbsRejectsAsymmetricIntegerDomain() {
    // ABS(min) overflows for two's-complement integers, and a SEARCH on the
    // operand cannot reproduce that error, so the handler must decline.
    final RexNode operand = input(tInt(true), 0);
    final RexCall abs =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ABS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
            abs, resultDomain,
            new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  // --- unary minus ----------------------------------------------------------

  @Test void testUnaryMinusReflectsRange() {
    // -x < 5  <=>  x > -5, i.e. (-inf, 5) reflects to (-5, +inf).
    final RelDataType type = decimalType(5, 0, true);
    final RexNode operand = input(type, 0);
    final RexCall negate =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.lessThan(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                negate, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
                ImmutableRangeSet.of(
                    Range.openClosed(BigDecimal.valueOf(-5),
                        BigDecimal.valueOf(99999))))));
  }

  @Test void testUnaryMinusRejectsAsymmetricIntegerDomain() {
    // -min overflows for two's-complement integers: decline.
    final RexNode operand = input(tBigInt(true), 0);
    final RexCall negate =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.ONE)));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
            negate, resultDomain,
            new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  @Test void testRegistryIsExtensible() {
    // Adding a function is one register() call; the defaults are retained.
    final PreimageProviders extended = PreimageProviders.DEFAULT.toBuilder()
        .register(SqlStdOperatorTable.SQRT,
            (call, domain, ctx) -> null)
        .build();
    // A default function still resolves through the extended registry.
    final RelDataType type = decimalType(5, 0, true);
    final RexNode operand = input(type, 0);
    final RexCall negate =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.UNARY_MINUS, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.lessThan(BigDecimal.valueOf(5))));

    assertThat(
        extended.getPreimage(negate, resultDomain,
            new PreimageContext(rexBuilder, executor, -1)),
        is(notNullValue()));
  }

  // --- ROUND ----------------------------------------------------------------

  @Test void testRoundPositiveEqualityIsClosedOpen() {
    // ROUND(x) = 5  <=>  x IN [4.5, 5.5)  (half away from zero).
    final RelDataType type = decimalType(5, 2, true);
    final RexNode operand = input(type, 0);
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                round, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
                ImmutableRangeSet.of(
                    Range.closedOpen(new BigDecimal("4.50"),
                        new BigDecimal("5.50"))))));
  }

  @Test void testRoundNegativeEqualityIsOpenClosed() {
    // ROUND(x) = -5  <=>  x IN (-5.5, -4.5]  (half away from zero).
    final RelDataType type = decimalType(5, 2, false);
    final RexNode operand = input(type, 0);
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(-5))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                round, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
                ImmutableRangeSet.of(
                    Range.openClosed(new BigDecimal("-5.50"),
                        new BigDecimal("-4.50"))))));
  }

  @Test void testRoundZeroCrossingRange() {
    // ROUND(x) IN [-1, 1]  <=>  x IN (-1.5, 1.5): the contiguous union of
    // (-1.5,-0.5], (-0.5,0.5), [0.5,1.5).
    final RelDataType type = decimalType(5, 2, false);
    final RexNode operand = input(type, 0);
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(
                Range.closed(BigDecimal.valueOf(-1), BigDecimal.valueOf(1))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                round, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
                ImmutableRangeSet.of(
                    Range.open(new BigDecimal("-1.50"),
                        new BigDecimal("1.50"))))));
  }

  @Test void testRoundLessThanDropsHalf() {
    // ROUND(x) < 1  <=>  ROUND(x) <= 0  <=>  x < 0.5.
    final RelDataType type = decimalType(5, 2, false);
    final RexNode operand = input(type, 0);
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.FALSE,
            ImmutableRangeSet.of(Range.lessThan(BigDecimal.valueOf(1))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                round, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.FALSE,
                ImmutableRangeSet.of(
                    Range.closedOpen(new BigDecimal("-999.99"),
                        new BigDecimal("0.50"))))));
  }

  @Test void testRoundIntegralOperandIsIdentity() {
    // ROUND of an already-integral operand is the identity.
    final RexNode operand = input(tInt(true), 0);
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND, operand);
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.lessThan(BigDecimal.valueOf(100))));

    final Preimage<?> preimage =
        requireNonNull(
            PreimageProviders.DEFAULT.getPreimage(
                round, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand(), is(operand));
    assertThat(preimage.sarg(),
        is(
            Sarg.of(RexUnknownAs.UNKNOWN,
                ImmutableRangeSet.of(
                    Range.lessThan(BigDecimal.valueOf(100))))));
  }

  @Test void testRoundRejectsTwoArgumentForm() {
    // ROUND(x, n) carries a scale argument and is out of scope.
    final RexCall round =
        (RexCall) rexBuilder.makeCall(SqlStdOperatorTable.ROUND,
            input(decimalType(5, 2, true), 0),
            rexBuilder.makeExactLiteral(BigDecimal.ONE));
    final Sarg<BigDecimal> resultDomain =
        Sarg.of(RexUnknownAs.UNKNOWN,
            ImmutableRangeSet.of(Range.singleton(BigDecimal.valueOf(5))));

    assertThat(
        PreimageProviders.DEFAULT.getPreimage(
            round, resultDomain,
            new PreimageContext(rexBuilder, executor, -1)),
        nullValue());
  }

  private RelDataType decimalType(int precision, int scale,
      boolean nullable) {
    final RelDataType type =
        typeFactory.createSqlType(SqlTypeName.DECIMAL, precision, scale);
    return typeFactory.createTypeWithNullability(type, nullable);
  }

  /** Service exposed through a planner context. */
  private static class TestService {
  }
}
