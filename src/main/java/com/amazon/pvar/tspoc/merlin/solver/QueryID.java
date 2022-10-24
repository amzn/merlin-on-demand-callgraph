package com.amazon.pvar.tspoc.merlin.solver;

/** Used to refer to queries when registering handlers on `LiveSet`s. The
  * following tuple uniquely identifies a sub query:
  *   - Initial query, including direction
  *   - Sub-query, including direction
  *   - Whether the query was launched from an unbalanced pop listener, which
  *     can result in identical subqueries to those issued by other flow
  *     functions.
  */
public record QueryID(
    Query initialQuery,
    Query subQuery,
    boolean inUnbalancedPopListener
) {}

