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
    assertSame(FunctionPreimageProvider.INSTANCE,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
            cast, resultDomain, new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand, is(operand));
    assertThat(preimage.sarg,
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
        FunctionPreimageProvider.INSTANCE.getPreimage(
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
        FunctionPreimageProvider.INSTANCE.getPreimage(
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
        FunctionPreimageProvider.INSTANCE.getPreimage(
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand, is(operand));
    assertThat(preimage.sarg,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                ceil, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.operand, is(operand));
    assertThat(preimage.sarg,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                ceil, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg,
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
            FunctionPreimageProvider.INSTANCE.getPreimage(
                floor, resultDomain,
                new PreimageContext(rexBuilder, executor, -1)));

    assertThat(preimage.sarg,
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
        FunctionPreimageProvider.INSTANCE.getPreimage(
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

    assertThat(preimage.operand, is(operand));
    assertThat(preimage.sarg,
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

    assertThat(preimage.operand, is(operand));
    assertThat(preimage.sarg,
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
