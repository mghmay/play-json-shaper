/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer

import io.github.mghmay.helpers.SourceCleanup
import io.github.mghmay.transformer.api.PipelineBuilder
import io.github.mghmay.transformer.ops._
import play.api.libs.json._

object syntax {

  object move {
    def apply(from: JsPath, to: JsPath): Transformer =
      TransformOps.move(from, to, SourceCleanup.Aggressive)

    def apply(from: JsPath, to: JsPath, cleanup: SourceCleanup): Transformer =
      TransformOps.move(from, to, cleanup)
  }

  def copy: (JsPath, JsPath) => Transformer = TransformOps.copy

  def set: (JsPath, JsValue) => Transformer = TransformOps.set

  def mergeAt: (JsPath, JsObject) => Transformer = TransformOps.mergeAt

  def pruneAggressive: JsPath => Transformer = TransformOps.pruneAggressive

  def pruneGentle: JsPath => Transformer = TransformOps.pruneGentle

  def rename: (JsPath, JsPath) => Transformer = TransformOps.rename

  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Transformer = TransformOps.mapAt(path)(vf)

  def when(pred: Predicate)(step: Transformer): Transformer = ConditionalOps.when(pred)(step)

  def ifExists(path: JsPath)(step: Transformer): Transformer = ConditionalOps.ifExists(path)(step)

  def ifMissing(path: JsPath)(step: Transformer): Transformer = ConditionalOps.ifMissing(path)(step)

  implicit final class PredicateOps(private val p: Predicate) extends AnyVal {
    def &&(q: Predicate): Predicate = j => p(j) && q(j)
    def ||(q: Predicate): Predicate = j => p(j) || q(j)
    def unary_! : Predicate         = j => !p(j)

    def and(q: Predicate): Predicate = j => p(j) && q(j)
    def or(q: Predicate): Predicate  = j => p(j) || q(j)
    def not: Predicate               = j => !p(j)
  }

  /** Pipe a JsObject into a Transformer: json |> t */
  implicit final class PipeJsonOps(private val json: JsObject) extends AnyVal {
    @inline def |>(t: Transformer): Either[JsError, JsObject] = t(json)
  }

  /** Pipe an Either result into the next Transformer: (json |> t1) |> t2 */
  implicit final class PipeEitherOps(private val res: Either[JsError, JsObject]) extends AnyVal {
    @inline def |>(t: Transformer): Either[JsError, JsObject] = res.flatMap(t)
  }

  /** Compose transformers with pipe style: t1 |> t2 produces a Transformer */
  implicit final class PipeTransformerOps(private val t: Transformer) extends AnyVal {
    @inline def |>(next: Transformer): Transformer      = json => t(json).flatMap(next)
    @inline def andThen(next: Transformer): Transformer = json => t(json).flatMap(next)
  }

  /** Compose pipeline with pipe style: t1 |> t2 produces a Transformer */
  implicit final class PipePipelineOps(private val p: PipelineBuilder) extends AnyVal {
    @inline def |>(t: Transformer): PipelineBuilder         = p.andThen(t)
    @inline def |>(other: PipelineBuilder): PipelineBuilder = p.andThen(other)
    @inline def ++(other: PipelineBuilder): PipelineBuilder = p.andThen(other)
  }
}
