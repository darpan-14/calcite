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
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.logical.LogicalCalc;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rex.BisectionPreimageProvider;
import org.apache.calcite.rex.FunctionPreimageProvider;
import org.apache.calcite.rex.Preimage;
import org.apache.calcite.rex.PreimageContext;
import org.apache.calcite.rex.PreimageProvider;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSimplify;
import org.apache.calcite.rex.RexUnknownAs;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Sarg;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;

import java.math.BigDecimal;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Planner rules that replace predicates on function results with predicates on
 * exact function preimages.
 *
 * <p>The rules are explicitly configured with {@link PreimageProvider}s. They
 * use {@link RexSimplify} to normalize and clean up conditions, but do not
 * enable preimage rewriting for other {@code RexSimplify} callers.
 */
public abstract class PreimageRules {
  private PreimageRules() {
  }

  /** Rewrites a condition, or returns null if no provider derived a preimage. */
  private static @Nullable RexNode rewriteCondition(RelNode rel,
      RexNode condition, Config config) {
    final RexBuilder rexBuilder = rel.getCluster().getRexBuilder();
    final @Nullable RexExecutor executor =
        rel.getCluster().getPlanner().getExecutor();
    final RexSimplify simplify =
        new RexSimplify(rexBuilder, RelOptPredicateList.EMPTY,
            Util.first(executor, RexUtil.EXECUTOR));
    final RexNode normalized = simplify.simplifyUnknownAsFalse(condition);
    final PreimageContext context =
        new PreimageContext(rexBuilder, executor, config.maxSargComplexity(),
            rel.getCluster().getPlanner().getContext());
    final Rewriter rewriter =
        new Rewriter(context, config.preimageProviders());
    final RexNode rewritten = normalized.accept(rewriter);
    if (rewriter.rewriteCount == 0) {
      return null;
    }
    return simplify.simplifyUnknownAsFalse(rewritten);
  }

  /** Rule that rewrites the condition of a {@link Filter}. */
  public static class FilterPreimageRule
      extends RelRule<FilterPreimageRule.FilterPreimageRuleConfig>
      implements TransformationRule {
    /** Creates a FilterPreimageRule. */
    protected FilterPreimageRule(FilterPreimageRuleConfig config) {
      super(config);
    }

    @Override public void onMatch(RelOptRuleCall call) {
      final Filter filter = call.rel(0);
      final RexNode condition =
          rewriteCondition(filter, filter.getCondition(), config);
      if (condition == null || condition.equals(filter.getCondition())) {
        return;
      }
      call.transformTo(
          filter.copy(filter.getTraitSet(), filter.getInput(), condition));
    }

    /** Rule configuration. */
    @Value.Immutable
    public interface FilterPreimageRuleConfig extends PreimageRules.Config {
      FilterPreimageRuleConfig DEFAULT = ImmutableFilterPreimageRuleConfig.of()
          .withOperandSupplier(b -> b.operand(LogicalFilter.class).anyInputs())
          .withDescription("PreimageRule(Filter)")
          .as(FilterPreimageRuleConfig.class);

      @Override default FilterPreimageRule toRule() {
        return new FilterPreimageRule(this);
      }

      @Override FilterPreimageRuleConfig withPreimageProviders(
          Iterable<? extends PreimageProvider> preimageProviders);

      @Override FilterPreimageRuleConfig withMaxSargComplexity(
          int maxSargComplexity);
    }
  }

  /** Rule that rewrites the condition of a {@link Calc}. */
  public static class CalcPreimageRule
      extends RelRule<CalcPreimageRule.CalcPreimageRuleConfig>
      implements TransformationRule {
    /** Creates a CalcPreimageRule. */
    protected CalcPreimageRule(CalcPreimageRuleConfig config) {
      super(config);
    }

    @Override public void onMatch(RelOptRuleCall call) {
      final Calc calc = call.rel(0);
      final RexProgram program = calc.getProgram();
      final RexLocalRef conditionRef =
          requireNonNull(program.getCondition(), "condition");
      final RexNode oldCondition = program.expandLocalRef(conditionRef);
      final RexNode newCondition =
          rewriteCondition(calc, oldCondition, config);
      if (newCondition == null || newCondition.equals(oldCondition)) {
        return;
      }

      final List<RexNode> projects =
          program.expandList(program.getProjectList());
      final RexProgram newProgram =
          RexProgram.create(program.getInputRowType(), projects, newCondition,
              program.getOutputRowType(), calc.getCluster().getRexBuilder());
      call.transformTo(
          calc.copy(calc.getTraitSet(), calc.getInput(), newProgram));
    }

    /** Rule configuration. */
    @Value.Immutable
    public interface CalcPreimageRuleConfig extends PreimageRules.Config {
      CalcPreimageRuleConfig DEFAULT = ImmutableCalcPreimageRuleConfig.of()
          .withOperandSupplier(b ->
              b.operand(LogicalCalc.class)
                  .predicate(calc -> calc.getProgram().getCondition() != null)
                  .anyInputs())
          .withDescription("PreimageRule(Calc)")
          .as(CalcPreimageRuleConfig.class);

      @Override default CalcPreimageRule toRule() {
        return new CalcPreimageRule(this);
      }

      @Override CalcPreimageRuleConfig withPreimageProviders(
          Iterable<? extends PreimageProvider> preimageProviders);

      @Override CalcPreimageRuleConfig withMaxSargComplexity(
          int maxSargComplexity);
    }
  }

  /** Common configuration for preimage rules. */
  public interface Config extends RelRule.Config {
    /** Providers to try in order. The first provider that returns an exact
     * preimage wins. */
    @Value.Default default ImmutableList<PreimageProvider> preimageProviders() {
      return ImmutableList.of(FunctionPreimageProvider.INSTANCE,
          BisectionPreimageProvider.INSTANCE);
    }

    /** Sets the providers to try, in order. */
    Config withPreimageProviders(
        Iterable<? extends PreimageProvider> preimageProviders);

    /** Maximum allowed complexity of a derived Sarg, or -1 for no limit. */
    @Value.Default default int maxSargComplexity() {
      return -1;
    }

    /** Sets the maximum allowed complexity of a derived Sarg. */
    Config withMaxSargComplexity(int maxSargComplexity);
  }

  /** Rewrites supported predicate forms to SEARCH calls over preimages. */
  private static class Rewriter extends RexShuttle {
    private final PreimageContext context;
    private final ImmutableList<PreimageProvider> providers;
    private int rewriteCount;

    Rewriter(PreimageContext context,
        Iterable<? extends PreimageProvider> providers) {
      this.context = context;
      this.providers = ImmutableList.copyOf(providers);
    }

    @Override public RexNode visitCall(RexCall call) {
      final RexNode node = super.visitCall(call);
      if (!(node instanceof RexCall)) {
        return node;
      }
      final Search search = toSearch((RexCall) node, context.rexBuilder());
      if (search == null) {
        return node;
      }

      for (PreimageProvider provider : providers) {
        final Preimage<?> preimage =
            provider.getPreimage(search.call, search.sarg, context);
        if (preimage == null
            || !search.call.getOperands().contains(preimage.operand)) {
          continue;
        }
        final int maxComplexity = context.maxSargComplexity();
        if (maxComplexity >= 0
            && preimage.sarg.complexity() > maxComplexity) {
          continue;
        }
        ++rewriteCount;
        return makeSearch(search, preimage, context.rexBuilder());
      }
      return node;
    }
  }

  /** A function call and the domain tested by its enclosing predicate. */
  private static class Search {
    final RexCall predicate;
    final RexCall call;
    final Sarg<?> sarg;

    Search(RexCall predicate, RexCall call, Sarg<?> sarg) {
      this.predicate = predicate;
      this.call = call;
      this.sarg = sarg;
    }
  }

  /** Converts a supported predicate into a function-result domain. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static @Nullable Search toSearch(RexCall predicate,
      RexBuilder rexBuilder) {
    if (predicate.getKind() == SqlKind.SEARCH) {
      final RexNode operand = predicate.getOperands().get(0);
      final RexNode domain = predicate.getOperands().get(1);
      if (!(operand instanceof RexCall) || !(domain instanceof RexLiteral)
          || !RexUtil.isDeterministic(operand)
          || !SqlTypeUtil.equalSansNullability(rexBuilder.getTypeFactory(),
              operand.getType(), domain.getType())) {
        return null;
      }
      final Sarg<?> sarg = ((RexLiteral) domain).getValueAs(Sarg.class);
      return sarg == null ? null
          : new Search(predicate, (RexCall) operand, sarg);
    }

    switch (predicate.getKind()) {
    case IS_NULL:
    case IS_NOT_NULL:
      final RexNode operand = predicate.getOperands().get(0);
      if (!(operand instanceof RexCall) || !RexUtil.isDeterministic(operand)) {
        return null;
      }
      final Sarg<?> nullSarg = predicate.getKind() == SqlKind.IS_NULL
          ? Sarg.of(RexUnknownAs.TRUE,
              TreeRangeSet.<BigDecimal>create())
          : Sarg.of(RexUnknownAs.FALSE,
              RangeSets.<BigDecimal>rangeSetAll());
      return new Search(predicate, (RexCall) operand, nullSarg);
    default:
      break;
    }

    switch (predicate.getKind()) {
    case LESS_THAN:
    case LESS_THAN_OR_EQUAL:
    case GREATER_THAN:
    case GREATER_THAN_OR_EQUAL:
    case EQUALS:
    case NOT_EQUALS:
    case IS_NOT_DISTINCT_FROM:
    case IS_DISTINCT_FROM:
      break;
    default:
      return null;
    }

    final RexNode left;
    final RexLiteral literal;
    final SqlKind kind;
    final RexNode operand0 = predicate.getOperands().get(0);
    final RexNode operand1 = predicate.getOperands().get(1);
    if (operand0 instanceof RexCall && operand1 instanceof RexLiteral) {
      left = operand0;
      literal = (RexLiteral) operand1;
      kind = predicate.getKind();
    } else if (operand0 instanceof RexLiteral && operand1 instanceof RexCall) {
      left = operand1;
      literal = (RexLiteral) operand0;
      kind = predicate.getKind().reverse();
    } else {
      return null;
    }
    if (!RexUtil.isDeterministic(left) || literal.isNull()
        || !SqlTypeUtil.equalSansNullability(rexBuilder.getTypeFactory(),
            left.getType(), literal.getType())) {
      return null;
    }
    final Comparable value = literal.getValueAs(Comparable.class);
    if (value == null) {
      return null;
    }

    final RangeSet<Comparable> ranges = TreeRangeSet.create();
    RexUnknownAs nullAs = RexUnknownAs.UNKNOWN;
    switch (kind) {
    case LESS_THAN:
      ranges.add(Range.lessThan(value));
      break;
    case LESS_THAN_OR_EQUAL:
      ranges.add(Range.atMost(value));
      break;
    case GREATER_THAN:
      ranges.add(Range.greaterThan(value));
      break;
    case GREATER_THAN_OR_EQUAL:
      ranges.add(Range.atLeast(value));
      break;
    case EQUALS:
      ranges.add(Range.singleton(value));
      break;
    case NOT_EQUALS:
      ranges.add(Range.lessThan(value));
      ranges.add(Range.greaterThan(value));
      break;
    case IS_NOT_DISTINCT_FROM:
      ranges.add(Range.singleton(value));
      nullAs = RexUnknownAs.FALSE;
      break;
    case IS_DISTINCT_FROM:
      ranges.add(Range.lessThan(value));
      ranges.add(Range.greaterThan(value));
      nullAs = RexUnknownAs.TRUE;
      break;
    default:
      return null;
    }
    return new Search(predicate, (RexCall) left, Sarg.of(nullAs, ranges));
  }

  /** Creates a SEARCH call over a provider's preimage. */
  private static RexNode makeSearch(Search search, Preimage<?> preimage,
      RexBuilder rexBuilder) {
    return rexBuilder.makeCall(search.predicate.getParserPosition(),
        SqlStdOperatorTable.SEARCH, preimage.operand,
        rexBuilder.makeSearchArgumentLiteral(preimage.sarg,
            preimage.operand.getType()));
  }
}
