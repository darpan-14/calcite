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

import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.Sarg;

import com.google.common.collect.ImmutableMap;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * A {@link PreimageProvider} backed by a registry of per-function
 * {@link PreimageHandler}s, keyed by {@link SqlOperator}.
 *
 * <p>This is the function-specific provider. It looks up the handler
 * registered for the call's operator and delegates to it, returning null for
 * an unregistered operator so a later provider (such as
 * {@link BisectionPreimageProvider}) may try. Because handlers are keyed by
 * operator identity, adding preimage support for a new function is a matter of
 * writing one {@link PreimageHandler} and registering it &mdash; no central
 * dispatch grows.
 *
 * <p>Build a registry with {@link #builder()}, or start from the Calcite
 * defaults with {@link #DEFAULT} and {@link #toBuilder()} to add
 * application-specific functions.
 */
@API(since = "1.43.0", status = API.Status.EXPERIMENTAL)
public final class PreimageProviders implements PreimageProvider {
  /** Default registry covering Calcite's built-in exact-numeric functions. */
  public static final PreimageProviders DEFAULT = builder()
      .register(SqlStdOperatorTable.CAST, CastPreimageHandler.INSTANCE)
      .register(SqlStdOperatorTable.FLOOR, FloorCeilPreimageHandler.FLOOR)
      .register(SqlStdOperatorTable.CEIL, FloorCeilPreimageHandler.CEIL)
      .register(SqlStdOperatorTable.ABS, AbsPreimageHandler.INSTANCE)
      .register(SqlStdOperatorTable.UNARY_MINUS,
          UnaryMinusPreimageHandler.INSTANCE)
      .register(SqlStdOperatorTable.ROUND, RoundPreimageHandler.INSTANCE)
      .build();

  private final ImmutableMap<SqlOperator, PreimageHandler> handlers;

  private PreimageProviders(ImmutableMap<SqlOperator, PreimageHandler> handlers) {
    this.handlers = handlers;
  }

  @Override public @Nullable Preimage<?> getPreimage(RexCall call,
      Sarg<?> resultDomain, PreimageContext context) {
    final PreimageHandler handler = handlers.get(call.getOperator());
    return handler == null ? null
        : handler.getPreimage(call, resultDomain, context);
  }

  /** Creates an empty builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Creates a builder seeded with this registry's handlers, so an application
   * can add or replace functions without losing the Calcite defaults. */
  public Builder toBuilder() {
    return new Builder().registerAll(handlers);
  }

  /** Builds a {@link PreimageProviders} registry. */
  public static final class Builder {
    private final ImmutableMap.Builder<SqlOperator, PreimageHandler> handlers =
        ImmutableMap.builder();

    private Builder() {
    }

    /** Registers a handler for one operator. A second registration for the
     * same operator is rejected when the map is built. */
    public Builder register(SqlOperator operator, PreimageHandler handler) {
      handlers.put(requireNonNull(operator, "operator"),
          requireNonNull(handler, "handler"));
      return this;
    }

    Builder registerAll(ImmutableMap<SqlOperator, PreimageHandler> existing) {
      handlers.putAll(existing);
      return this;
    }

    /** Builds the registry. The last handler registered for an operator wins,
     * so a {@link #toBuilder()} caller can override a default. */
    public PreimageProviders build() {
      return new PreimageProviders(handlers.buildKeepingLast());
    }
  }
}
