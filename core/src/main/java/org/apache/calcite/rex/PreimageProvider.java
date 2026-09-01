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

import org.apache.calcite.util.Sarg;

import org.apiguardian.api.API;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Derives an exact input domain for a predicate on a function result.
 *
 * <p>A provider must return a result only if replacing
 * {@code SEARCH(call, resultDomain)} with the returned preimage preserves the
 * truth value for every input. In particular, it must preserve null and error
 * behavior as well as ordinary non-null values. The returned operand must be
 * a direct operand of the call, which guarantees that repeated rule
 * application makes progress. A provider returns null when it cannot prove
 * the equivalence.
 *
 * @see FunctionPreimageProvider
 * @see BisectionPreimageProvider
 */
@FunctionalInterface
@API(since = "1.43.0", status = API.Status.EXPERIMENTAL)
public interface PreimageProvider {
  /** Returns the exact preimage, or null if this provider cannot derive one. */
  @Nullable Preimage<?> getPreimage(RexCall call, Sarg<?> resultDomain,
      PreimageContext context);
}
