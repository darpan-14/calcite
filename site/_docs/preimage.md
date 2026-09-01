---
layout: docs
title: Function preimages
permalink: /docs/preimage.html
---
<!--
{% comment %}
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
{% endcomment %}
-->

A function preimage describes the input values for which the result of a
function belongs to a given domain. For a function `f` and result domain `D`,
the preimage is

{% highlight text %}
preimage(f, D) = {x | f(x) is in D}.
{% endhighlight %}

This lets an optimizer replace a predicate on a computed expression with an
equivalent predicate on its input:

{% highlight text %}
SEARCH(f(column), D) = SEARCH(column, preimage(f, D)).
{% endhighlight %}

The equality here is semantic equality. It must hold for every input and must
preserve null and error behavior, not only the ordinary non-null values.

* TOC
{:toc}

## Why preimages are useful

Predicates on base columns are more likely to be understood by a storage
adapter than predicates on computed expressions. Moving a predicate through a
function can therefore enable partition, file, and row-group pruning using
metadata such as minimum and maximum values. It can also avoid evaluating the
function for every row: the constant domain is transformed once instead.

For example, subject to the exact semantics of the `YEAR` function and its
operand type,

{% highlight sql %}
YEAR(order_date) = 2025
{% endhighlight %}

has the preimage

{% highlight sql %}
order_date >= DATE '2025-01-01'
AND order_date < DATE '2026-01-01'.
{% endhighlight %}

The half-open range is directly useful to a scan that has statistics on
`order_date`.

## Preimages are not scalar inverses

A preimage is a domain transformation, not necessarily the application of an
inverse function to one constant. A function may not have a scalar inverse,
yet still have an exact preimage that is useful to the optimizer. For example,

{% highlight sql %}
ABS(x) = 5
{% endhighlight %}

has the mathematical preimage `x IN (-5, 5)`. A provider for a fixed-width
runtime type must additionally preserve behavior such as overflow at its most
negative value; otherwise it must decline the rewrite.

Monotonic functions are particularly convenient because the preimage of an
interval can be found from its boundaries and is generally another interval.
Monotonicity alone is not sufficient, however. A rewrite must also account for
the function's exact type, precision, null, overflow, error, time zone, and
session-dependent behavior. Monotonicity is also not required: Calcite's
`Sarg` representation can describe a union of ranges such as the two points in
the `ABS` example.

## Architecture

Preimage support has three layers.

### Exact domain derivation

`PreimageProvider` is the extension point for exact domain derivation. It
receives a `RexCall`, a result `Sarg`, and a request-scoped `PreimageContext`,
and either returns an exact `Preimage` for one direct operand or declines the
request. A returned preimage is a proof obligation: replacing the original
predicate must preserve its truth value for every input. Requiring a direct
operand also makes repeated rule application progress toward simpler
expressions.

`PreimageContext` is created for each condition rewrite. Besides the expression
builder, executor, and limits, it delegates `unwrap` requests to Calcite's
planner `Context`. Applications can therefore expose adapter-specific services
to a provider without global state or a Calcite-specific context subclass.

Providers should identify operators by identity rather than by name. They must
be conservative when behavior depends on type families, precision, overflow,
nullability, conformance, time zones, locales, or other execution semantics.

Calcite has two built-in implementations. `FunctionPreimageProvider` contains
closed-form derivations for functions where a formula is clearer or cheaper
than evaluation. It currently handles lossless, order-preserving casts and
unary `FLOOR` and `CEIL` over exact numeric types. The cast derivation rewrites
a domain only when every finite endpoint is exactly representable in the source
type. For example,

{% highlight sql %}
CAST(smallint_column AS BIGINT) < 100
{% endhighlight %}

can become `smallint_column < 100`. A narrowing cast, or an endpoint that
requires rounding or overflows the source type, is not rewritten.

For an exact numeric operand, the point preimages of `FLOOR` and `CEIL` are

{% highlight text %}
preimage(FLOOR, k) = [k, k + 1)
preimage(CEIL,  k) = (k - 1, k].
{% endhighlight %}

The provider applies the corresponding boundary transformation to every range
in the result `Sarg` and clips the result to the declared operand type. It does
not handle approximate numeric types, whose NaN, infinity, and rounding
semantics require a separate proof.

`BisectionPreimageProvider` is the generic fallback for deterministic unary
functions with known increasing or decreasing monotonicity over a finite exact
numeric domain. For each result boundary `c`, it uses the configured
`RexExecutor` to locate these input boundaries:

{% highlight text %}
first x where f(x) >= c
first x where f(x) >  c
{% endhighlight %}

For example, the equality preimage of an increasing function is the interval
between those two boundaries. A 64-bit domain requires at most 64 probes per
boundary; the implementation uses arbitrary-precision integer coordinates so
midpoints and boundary sentinels cannot overflow. Probe results are cached
within one derivation.

The bisection provider requires strict null propagation and treats an
increasing or decreasing operator monotonicity result as a contract that the
function is total and order-preserving over its non-null operand domain. It
declines the rewrite if there is no planner executor, a probe is not reducible
to a non-null literal, the type is not supported, or monotonicity is not known.

### Relational rules

`PreimageRules.FilterPreimageRule` and `PreimageRules.CalcPreimageRule` apply
configured providers to conditions. They convert supported predicate forms to
`SEARCH`, ask the providers for an exact input domain, and replace the original
predicate only when a provider succeeds.

Providers are tried in configured order and the first successful exact result
wins. The default order is `FunctionPreimageProvider` followed by
`BisectionPreimageProvider`: a closed-form derivation avoids planner-time
execution, while bisection covers eligible functions without function-specific
preimage code. Returning null means that a provider has declined the request,
so the next provider may try. Applications may add, remove, or reorder
providers through the rule configuration.

Keeping activation in planner rules makes the optimization explicit. A
planner can schedule the rules before adapter predicate pushdown and can choose
the provider set and maximum accepted `Sarg` complexity. Applications can add
providers for functions whose runtime semantics they control without changing
global expression simplification.

### Expression normalization

The rules use `RexSimplify` to normalize the input condition and clean up the
result. Preimage derivation is not enabled implicitly in `RexSimplify` because
that class is used in many contexts that do not share the filtering semantics,
provider policy, or rule scheduling needs of scan pushdown.

## Relationship to existing rewrites

Function-specific rules remain useful when they implement broader or more
specialized behavior. In particular, `DateRangeRules` already expands
predicates involving `EXTRACT`, `FLOOR`, and `CEIL` on datetime values. Those
rewrites also have to respect calendars, precision, and time semantics. The
numeric `FLOOR` and `CEIL` provider handles only the unary exact-numeric form,
so it does not duplicate or replace those datetime rewrites. Common providers
should be added only where they provide a clear semantic and maintenance
advantage.

Exact-preimage derivation is not globally conditional on monotonicity. A
non-monotonic function can still have an exact, possibly multi-range preimage.
Rather, each provider must inspect the call and return a result only for the
argument and type combinations for which it can prove the equivalence.

Likewise, existing algebraic simplifications such as those for `ABS` can
remain in place. A function-specific simplification may produce a more compact
expression, while a provider is useful when the same domain contract should be
available uniformly to `Filter`, `Calc`, and adapter-specific functions.

## Follow-up: pruning domains

The exact contract intentionally excludes useful necessary conditions that are
not equivalent to the original predicate. A possible follow-up extension is a
`PruningDomainProvider` with a weaker implication contract:

{% highlight text %}
original predicate implies derived domain.
{% endhighlight %}

Such a domain can be pushed toward a scan for pruning, but the original
predicate must remain as a residual filter. For example, a coarse domain may
rule out files that cannot match while still admitting rows that fail the
original function predicate. Keeping exact preimages and pruning domains as
separate interfaces prevents an approximate result from accidentally replacing
the original predicate.

A future pruning provider could reuse exact boundaries from a preimage provider
or derive weaker, outward-rounded bounds for domains that exact preimage
derivation does not support. If the derived domain is only necessary rather
than equivalent, the original predicate must remain as a residual.
