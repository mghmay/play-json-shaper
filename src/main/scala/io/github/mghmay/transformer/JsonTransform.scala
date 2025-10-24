/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer

import io.github.mghmay.helpers.SourceCleanup
import play.api.libs.json._

/** Main entry point for JSON transformation operations. Provides a fluent API for building and executing JSON
  * transformation pipelines.
  */
object JsonTransform {

  /** Composes multiple transformers into a single transformer that executes them sequentially.
    *
    * @param steps
    *   The sequence of transformers to compose
    * @return
    *   A single Transformer that executes all steps in sequence
    * @note
    *   If any step fails, the entire composition fails
    * @example
    *   {{{val composed = JsonTransform.run(Seq(transformer1, transformer2, transformer3))}}}
    */
  def compose(steps: Seq[Transformer]): Transformer =
    steps.foldLeft(identity) { (acc, step) => (json: JsObject) => acc(json).flatMap(step) }

  /** Alias for `compose` - composes multiple transformers into a single transformer.
    *
    * @param steps
    *   The varargs of transformers to compose
    * @return
    *   A single Transformer that executes all steps in sequence
    * @example
    *   {{{val composed = JsonTransform(Seq(transformer1, transformer2, transformer3))}}}
    */
  def apply(steps: Transformer*): Transformer =
    compose(steps.toVector)

  /** Creates a new empty transformation pipeline.
    *
    * @return
    *   A new Pipeline instance with no transformation steps
    * @example
    *   {{{ val pipeline = Shaper.start .move(__ \ "old", __ \ "new") .copy(__ \ "source", __ \ "destination")
    *   }}}
    */
  def start: PipelineBuilder = new PipelineBuilder(Vector.empty)

  /** Adds a move operation to the pipeline.
    *
    * @see
    *   [[syntax.move]]
    */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): PipelineBuilder =
    start.move(from, to, cleanup)

  /** Adds a copy operation to the pipeline.
    *
    * @see
    *   [[syntax.copy]]
    */
  def copy(from: JsPath, to: JsPath): PipelineBuilder =
    start.copy(from, to)

  /** Adds a set operation to the pipeline.
    *
    * @see
    *   [[syntax.set]]
    */
  def set(path: JsPath, value: JsValue): PipelineBuilder =
    start.set(path, value)

  /** Adds a merge operation to the pipeline.
    *
    * @see
    *   [[syntax.mergeAt]]
    */
  def mergeAt(path: JsPath, obj: JsObject): PipelineBuilder =
    start.mergeAt(path, obj)

  /** Adds an aggressive prune operation to the pipeline.
    *
    * @see
    *   [[syntax.pruneAggressive]]
    */
  def pruneAggressive(path: JsPath): PipelineBuilder =
    start.pruneAggressive(path)

  /** Adds a gentle prune operation to the pipeline.
    *
    * @see
    *   [[syntax.pruneGentle]]
    */
  def pruneGentle(path: JsPath): PipelineBuilder =
    start.pruneGentle(path)

  /** Adds a rename operation to the pipeline.
    *
    * @see
    *   [[syntax.rename]]
    */
  def rename(from: JsPath, to: JsPath): PipelineBuilder =
    start.rename(from, to)

  /** Adds a map operation to the pipeline.
    *
    * @see
    *   [[syntax.mapAt]]
    */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): PipelineBuilder =
    start.mapAt(path)(vf)

  /** Adds a conditional operation to the pipeline.
    *
    * @see
    *   [[syntax.when]]
    */
  def when(pred: JsObject => Boolean)(step: Transformer): PipelineBuilder =
    start.when(pred)(step)

  /** Adds an existence check operation to the pipeline.
    *
    * @see
    *   [[syntax.ifExists]]
    */
  def ifExists(path: JsPath)(step: Transformer): PipelineBuilder =
    start.ifExists(path)(step)

  /** Adds a missing check operation to the pipeline.
    *
    * @see
    *   [[syntax.ifMissing]]
    */
  def ifMissing(path: JsPath)(step: Transformer): PipelineBuilder =
    start.ifMissing(path)(step)
}

final class PipelineBuilder(private val steps: Vector[Transformer]) {

  /** Appends a single transformer to this pipeline.
    *
    * @param t
    *   The transformer to append
    * @return
    *   A new pipeline with the additional transformer
    */
  def andThen(t: Transformer): PipelineBuilder = new PipelineBuilder(steps = steps :+ t)

  /** Appends a pipeline to this pipeline.
    *
    * @param other
    *   The pipeline to append
    * @return
    *   A new pipeline with the additional transformer
    */
  def andThen(other: PipelineBuilder): PipelineBuilder = new PipelineBuilder(steps = steps ++ other.steps)

  /** Adds a move operation to the pipeline.
    *
    * @see
    *   [[syntax.move]]
    */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): PipelineBuilder =
    andThen(JsonTransformOps.move(from, to, cleanup))

  /** Adds a copy operation to the pipeline.
    *
    * @see
    *   [[syntax.copy]]
    */
  def copy(from: JsPath, to: JsPath): PipelineBuilder =
    andThen(JsonTransformOps.copy(from, to))

  /** Adds a set operation to the pipeline.
    *
    * @see
    *   [[syntax.set]]
    */
  def set(path: JsPath, value: JsValue): PipelineBuilder =
    andThen(JsonTransformOps.set(path, value))

  /** Adds a merge operation to the pipeline.
    *
    * @see
    *   [[syntax.mergeAt]]
    */
  def mergeAt(path: JsPath, obj: JsObject): PipelineBuilder =
    andThen(JsonTransformOps.mergeAt(path, obj))

  /** Adds an aggressive prune operation to the pipeline.
    *
    * @see
    *   [[syntax.pruneAggressive]]
    */
  def pruneAggressive(path: JsPath): PipelineBuilder =
    andThen(JsonTransformOps.pruneAggressive(path))

  /** Adds a gentle prune operation to the pipeline.
    *
    * @see
    *   [[syntax.pruneGentle]]
    */
  def pruneGentle(path: JsPath): PipelineBuilder =
    andThen(JsonTransformOps.pruneGentle(path))

  /** Adds a rename operation to the pipeline.
    *
    * @see
    *   [[syntax.rename]]
    */
  def rename(from: JsPath, to: JsPath): PipelineBuilder =
    andThen(JsonTransformOps.rename(from, to))

  /** Adds a map operation to the pipeline.
    *
    * @see
    *   [[syntax.mapAt]]
    */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): PipelineBuilder =
    andThen(JsonTransformOps.mapAt(path)(vf))

  /** Adds a conditional operation to the pipeline.
    *
    * @see
    *   [[syntax.when]]
    */
  def when(pred: JsObject => Boolean)(step: Transformer): PipelineBuilder =
    andThen(JsonTransformOps.when(pred)(step))

  /** Adds an existence check operation to the pipeline.
    *
    * @see
    *   [[syntax.ifExists]]
    */
  def ifExists(path: JsPath)(step: Transformer): PipelineBuilder =
    andThen(JsonTransformOps.ifExists(path)(step))

  /** Adds a missing check operation to the pipeline.
    *
    * @see
    *   [[syntax.ifMissing]]
    */
  def ifMissing(path: JsPath)(step: Transformer): PipelineBuilder =
    andThen(syntax.ifMissing(path)(step))

  /** Builds the final transformer from this pipeline.
    *
    * @return
    *   A single Transformer that executes all pipeline steps in sequence
    */
  def build: Transformer = JsonTransform.compose(steps)

  /** Executes the pipeline on a JSON object.
    *
    * @param json
    *   The JSON object to transform
    * @return
    *   Either the transformed object or a JsError if any step fails
    */
  def run(json: JsObject): Either[JsError, JsObject] = build(json)

  /** Alias for `run` - executes the pipeline on a JSON object.
    *
    * @param json
    *   The JSON object to transform
    * @return
    *   Either the transformed object or a JsError if any step fails
    */
  def apply(json: JsObject): Either[JsError, JsObject] = run(json)
}
