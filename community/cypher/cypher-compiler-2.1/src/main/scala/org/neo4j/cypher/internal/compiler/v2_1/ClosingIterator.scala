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
package org.neo4j.cypher.internal.compiler.v2_1

import org.neo4j.cypher.internal.helpers._
import org.neo4j.cypher.{CypherException, NodeStillHasRelationshipsException}
import org.neo4j.graphdb.ConstraintViolationException
import org.neo4j.graphdb.TransactionFailureException
import org.neo4j.kernel.api.exceptions.Status
import scala.collection

class ClosingIterator(inner: Iterator[collection.Map[String, Any]],
                      closer: TaskCloser,
                      exceptionDecorator: CypherException => CypherException) extends Iterator[Map[String, Any]] {

  lazy val still_has_relationships = "Node record Node\\[(\\d),.*] still has relationships".r

  def hasNext: Boolean = failIfThrows {
    if (closer.isClosed) return false

    val innerHasNext: Boolean = inner.hasNext
    if (!innerHasNext) {
      close(success = true)
    }
    innerHasNext
  }

  def next(): Map[String, Any] = failIfThrows {
    if (closer.isClosed) return Iterator.empty.next()

    val input: collection.Map[String, Any] = inner.next()
    val result: Map[String, Any] = Materialized.mapValues(input, materialize)
    if (!inner.hasNext) {
      close(success = true)
    }
    result
  }

  private def materialize(v: Any): Any = v match {
    case (x: Stream[_]) => x.map(materialize).toList
    case (x: Map[_, _]) => Materialized.mapValues(x, materialize)
    case (x: Iterable[_]) => x.map(materialize)
    case x => x
  }

  def close() {
    close(success = true)
  }

  def close(success: Boolean) = translateException {
    closer.close(success)
  }

  private def translateException[U](f: => U): U = decoratedCypherException({
    try {
      f
    } catch {
      case e: TransactionFailureException =>

        var cause: Throwable = e.getCause()

        if ( cause.isInstanceOf[org.neo4j.kernel.api.exceptions.TransactionFailureException] ) {
          val status = cause.asInstanceOf[org.neo4j.kernel.api.exceptions.TransactionFailureException].status()
          if ( status == Status.Transaction.ValidationFailed ) {
            cause.getMessage match {
              case still_has_relationships(id) => throw new NodeStillHasRelationshipsException(id.toLong, e)
              case _ => throw e
            }
          }
        }

        throw e
    }
  })

  private def failIfThrows[U](f: => U): U = decoratedCypherException({
    try {
      f
    } catch {
      case t: Throwable =>
        close(success = false)
        throw t
    }
  })

  private def decoratedCypherException[U](f: => U): U = try {
    f
  } catch {
    case e: CypherException =>
      throw exceptionDecorator(e)
  }

}
