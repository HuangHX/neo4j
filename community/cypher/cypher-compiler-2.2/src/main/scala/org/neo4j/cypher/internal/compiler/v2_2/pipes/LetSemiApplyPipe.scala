/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.cypher.internal.compiler.v2_2.pipes

import org.neo4j.cypher.internal.compiler.v2_2.ExecutionContext
import org.neo4j.cypher.internal.compiler.v2_2.executionplan.Effects
import org.neo4j.cypher.internal.compiler.v2_2.planDescription.{PlanDescriptionImpl, TwoChildren}
import org.neo4j.cypher.internal.compiler.v2_2.symbols._

case class LetSemiApplyPipe(source: Pipe, inner: Pipe, letVarName: String, negated: Boolean)
                           (val estimatedCardinality: Option[Long] = None)
                           (implicit pipeMonitor: PipeMonitor) extends PipeWithSource(source, pipeMonitor) with RonjaPipe {
  def internalCreateResults(input: Iterator[ExecutionContext], state: QueryState): Iterator[ExecutionContext] = {
    input.map {
      (outerContext) =>
        val innerState = state.copy(initialContext = Some(outerContext))
        val innerResults = inner.createResults(innerState)
        val holds = if (negated) innerResults.isEmpty else innerResults.nonEmpty
        outerContext += (letVarName -> holds)
    }
  }

  private def name = if (negated) "LetAntiSemiApply" else "LetSemiApply"

  def planDescription = PlanDescriptionImpl(this, name, TwoChildren(source.planDescription, inner.planDescription), Seq.empty, identifiers)

  def symbols: SymbolTable = source.symbols.add(letVarName, CTBoolean)

  override val sources = Seq(source, inner)

  def dup(sources: List[Pipe]): Pipe = {
    val (source :: inner :: Nil) = sources
    copy(source = source, inner = inner)(estimatedCardinality)
  }

  override def localEffects = Effects.NONE

  def withEstimatedCardinality(estimated: Long) = copy()(Some(estimated))
}
