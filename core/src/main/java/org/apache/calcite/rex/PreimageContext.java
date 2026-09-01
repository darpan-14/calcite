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

import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.Contexts;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/** Services and limits available while deriving a function preimage. */
@API(since = "1.43.0", status = API.Status.EXPERIMENTAL)
public final class PreimageContext implements Context {
  private final RexBuilder rexBuilder;
  private final @Nullable RexExecutor executor;
  private final int maxSargComplexity;
  private final Context plannerContext;

  /** Creates a context.
   *
   * @param rexBuilder Rex expression builder
   * @param executor Optional constant-expression executor
   * @param maxSargComplexity Maximum allowed complexity of a derived Sarg,
   *     or -1 for no limit
   */
  public PreimageContext(RexBuilder rexBuilder, @Nullable RexExecutor executor,
      int maxSargComplexity) {
    this(rexBuilder, executor, maxSargComplexity, Contexts.empty());
  }

  /** Creates a context with access to the enclosing planner context. */
  public PreimageContext(RexBuilder rexBuilder, @Nullable RexExecutor executor,
      int maxSargComplexity, Context plannerContext) {
    checkArgument(maxSargComplexity >= -1,
        "maxSargComplexity must be at least -1");
    this.rexBuilder = requireNonNull(rexBuilder, "rexBuilder");
    this.executor = executor;
    this.maxSargComplexity = maxSargComplexity;
    this.plannerContext = requireNonNull(plannerContext, "plannerContext");
  }

  /** Returns the Rex expression builder. */
  public RexBuilder rexBuilder() {
    return rexBuilder;
  }

  /** Returns the constant-expression executor, or null if none is available. */
  public @Nullable RexExecutor executor() {
    return executor;
  }

  /** Returns the maximum allowed complexity of a derived Sarg, or -1 if
   * there is no limit. */
  public int maxSargComplexity() {
    return maxSargComplexity;
  }

  /** Returns a service from the enclosing planner context. */
  @Override public <T> @Nullable T unwrap(Class<T> clazz) {
    if (clazz.isInstance(this)) {
      return clazz.cast(this);
    }
    return plannerContext.unwrap(clazz);
  }
}
