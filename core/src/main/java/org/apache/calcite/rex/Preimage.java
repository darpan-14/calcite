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

import static java.util.Objects.requireNonNull;

/**
 * Exact preimage of a result domain for a direct operand of a function call.
 *
 * <p>If {@code f(x)} is the original call and {@code D} is its result domain,
 * a preimage contains a direct {@link #operand} and a {@link #sarg} such that
 * {@code SEARCH(f(x), D)} and {@code SEARCH(operand, sarg)} have the same
 * truth value for every input, including null inputs.
 *
 * @param <C> Value type of the operand domain
 */
@API(since = "1.43.0", status = API.Status.EXPERIMENTAL)
public final class Preimage<C extends Comparable<C>> {
  public final RexNode operand;
  public final Sarg<C> sarg;

  /** Creates a preimage. */
  public Preimage(RexNode operand, Sarg<C> sarg) {
    this.operand = requireNonNull(operand, "operand");
    this.sarg = requireNonNull(sarg, "sarg");
  }
}
