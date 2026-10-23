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
import org.apache.calcite.util.Sarg;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Derives the exact preimage of a result domain for a single SQL function.
 *
 * <p>A handler is the per-function extension point. It is registered against
 * one {@link SqlOperator} in a {@link PreimageProviders} registry, and is only
 * invoked for calls to that operator, so an implementation may assume
 * {@code call.getOperator()} is the operator it was registered for. To add
 * preimage support for a new function, write a handler and register it; no
 * central dispatch has to change.
 *
 * <p>The contract is identical to {@link PreimageProvider}: the returned
 * preimage must preserve the truth value of
 * {@code SEARCH(call, resultDomain)} for every input, including null and error
 * behavior, and its operand must be a direct operand of {@code call}. A handler
 * returns null when it cannot prove the equivalence, for example because of
 * nullability, overflow, precision, time zone, or conformance concerns.
 *
 * @see PreimageProviders
 */
@FunctionalInterface
@API(since = "1.43.0", status = API.Status.EXPERIMENTAL)
public interface PreimageHandler {
  /** Returns the exact preimage for this function call, or null if it cannot
   * be derived.
   *
   * @param call Call to the operator this handler is registered for
   * @param resultDomain Domain the predicate tests against the call's result
   * @param context Services and limits for this derivation
   */
  @Nullable Preimage<?> getPreimage(RexCall call, Sarg<?> resultDomain,
      PreimageContext context);
}
