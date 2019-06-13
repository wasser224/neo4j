/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.runtime.spec._
import org.neo4j.cypher.internal.logical.plans.GetValue
import org.neo4j.cypher.internal.{CypherRuntime, RuntimeContext}

abstract class NodeIndexContainsScanTestBase[CONTEXT <: RuntimeContext](
                                                             edition: Edition[CONTEXT],
                                                             runtime: CypherRuntime[CONTEXT],
                                                             sizeHint: Int
                                                           ) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should be case sensitive for CONTAINS with indexes") {
    // given
    nodePropertyGraph(sizeHint, {
      case i if i % 2 == 0 => Map("text" -> "CASE")
      case i if i % 2 == 1 => Map("text" -> "case")
    },"Label")
    index("Label", "text")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("text")
      .projection("x.text AS text")
      .nodeIndexOperator("x:Label(text CONTAINS 'as')")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = List.fill(sizeHint / 2)("case")
    runtimeResult should beColumns("text").withRows(singleColumn(expected))
  }

  test("should be case sensitive for CONTAINS with unique indexes") {
    // given
    nodePropertyGraph(sizeHint, {
      case i if i % 2 == 0 => Map("text" -> s"CASE$i")
      case i if i % 2 == 1 => Map("text" -> s"case$i")
    },"Label")
    uniqueIndex("Label", "text")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("text")
      .projection("x.text AS text")
      .nodeIndexOperator("x:Label(text CONTAINS 'as')")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = (1 to sizeHint by 2).map(i => s"case$i")
    runtimeResult should beColumns("text").withRows(singleColumn(expected))
  }

  test("should cache properties") {
    // given
    val nodes = nodePropertyGraph(sizeHint, {
      case i => Map("text" -> i.toString)
    },"Label")
    index("Label", "text")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "text")
      .projection("cache[x.text] AS text")
      .nodeIndexOperator("x:Label(text CONTAINS '1')", GetValue)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = nodes.zipWithIndex.collect{ case (n, i) if i.toString.contains("1") => Array(n, i.toString)}
    runtimeResult should beColumns("x", "text").withRows(expected)
  }

  test("should cache properties with a unique index") {
    // given
    val nodes = nodePropertyGraph(sizeHint, {
      case i => Map("text" -> i.toString)
    },"Label")
    uniqueIndex("Label", "text")

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "text")
      .projection("cache[x.text] AS text")
      .nodeIndexOperator("x:Label(text CONTAINS '1')", GetValue)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = nodes.zipWithIndex.collect{ case (n, i) if i.toString.contains("1") => Array(n, i.toString)}
    runtimeResult should beColumns("x", "text").withRows(expected)
  }
}