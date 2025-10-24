/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer.api

import io.github.mghmay.helpers.SourceCleanup
import io.github.mghmay.transformer._
import io.github.mghmay.transformer.ops._
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
    *   {{{ val pipeline = JsonTransform.start .move(__ \ "old", __ \ "new") .copy(__ \ "source", __ \
    *   "destination") }}}
    */
  def start: PipelineBuilder = new PipelineBuilder(Vector.empty)

  /** Adds a move operation to the pipeline.
    *
    * @see
    *   [[TransformOps.move]]
    */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): PipelineBuilder =
    start.move(from, to, cleanup)

  /** Adds a copy operation to the pipeline.
    *
    * @see
    *   [[TransformOps.copy]]
    */
  def copy(from: JsPath, to: JsPath): PipelineBuilder =
    start.copy(from, to)

  /** Adds a set operation to the pipeline.
    *
    * @see
    *   [[TransformOps.set]]
    */
  def set(path: JsPath, value: JsValue): PipelineBuilder =
    start.set(path, value)

  /** Adds a merge operation to the pipeline.
    *
    * @see
    *   [[TransformOps.mergeAt]]
    */
  def mergeAt(path: JsPath, obj: JsObject): PipelineBuilder =
    start.mergeAt(path, obj)

  /** Adds an aggressive prune operation to the pipeline.
    *
    * @see
    *   [[TransformOps.pruneAggressive]]
    */
  def pruneAggressive(path: JsPath): PipelineBuilder =
    start.pruneAggressive(path)

  /** Adds a gentle prune operation to the pipeline.
    *
    * @see
    *   [[TransformOps.pruneGentle]]
    */
  def pruneGentle(path: JsPath): PipelineBuilder =
    start.pruneGentle(path)

  /** Adds a rename operation to the pipeline.
    *
    * @see
    *   [[TransformOps.rename]]
    */
  def rename(from: JsPath, to: JsPath): PipelineBuilder =
    start.rename(from, to)

  /** Adds a map operation to the pipeline.
    *
    * @see
    *   [[TransformOps.mapAt]]
    */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): PipelineBuilder =
    start.mapAt(path)(vf)

  /** Adds a conditional operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.when]]
    */
  def when(pred: Predicate)(step: Transformer): PipelineBuilder =
    start.when(pred)(step)

  /** Adds an existence check operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.ifExists]]
    */
  def ifExists(path: JsPath)(step: Transformer): PipelineBuilder =
    start.ifExists(path)(step)

  /** Adds a missing check operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.ifMissing]]
    */
  def ifMissing(path: JsPath)(step: Transformer): PipelineBuilder =
    start.ifMissing(path)(step)
}
