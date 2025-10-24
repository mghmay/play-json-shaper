/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer.api

import io.github.mghmay.helpers.SourceCleanup
import io.github.mghmay.transformer.ops.{ConditionalOps, TransformOps}
import io.github.mghmay.transformer.{Predicate, Transformer}
import play.api.libs.json._

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
    *   [[TransformOps.move]]
    */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): PipelineBuilder =
    andThen(TransformOps.move(from, to, cleanup))

  /** Adds a copy operation to the pipeline.
    *
    * @see
    *   [[TransformOps.copy]]
    */
  def copy(from: JsPath, to: JsPath): PipelineBuilder =
    andThen(TransformOps.copy(from, to))

  /** Adds a set operation to the pipeline.
    *
    * @see
    *   [[TransformOps.set]]
    */
  def set(path: JsPath, value: JsValue): PipelineBuilder =
    andThen(TransformOps.set(path, value))

  /** Adds a merge operation to the pipeline.
    *
    * @see
    *   [[TransformOps.mergeAt]]
    */
  def mergeAt(path: JsPath, obj: JsObject): PipelineBuilder =
    andThen(TransformOps.mergeAt(path, obj))

  /** Adds an aggressive prune operation to the pipeline.
    *
    * @see
    *   [[TransformOps.pruneAggressive]]
    */
  def pruneAggressive(path: JsPath): PipelineBuilder =
    andThen(TransformOps.pruneAggressive(path))

  /** Adds a gentle prune operation to the pipeline.
    *
    * @see
    *   [[TransformOps.pruneGentle]]
    */
  def pruneGentle(path: JsPath): PipelineBuilder =
    andThen(TransformOps.pruneGentle(path))

  /** Adds a rename operation to the pipeline.
    *
    * @see
    *   [[TransformOps.rename]]
    */
  def rename(from: JsPath, to: JsPath): PipelineBuilder =
    andThen(TransformOps.rename(from, to))

  /** Adds a map operation to the pipeline.
    *
    * @see
    *   [[TransformOps.mapAt]]
    */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): PipelineBuilder =
    andThen(TransformOps.mapAt(path)(vf))

  /** Adds a conditional operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.when]]
    */
  def when(pred: Predicate)(step: Transformer): PipelineBuilder =
    andThen(ConditionalOps.when(pred)(step))

  /** Adds an existence check operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.ifExists]]
    */
  def ifExists(path: JsPath)(step: Transformer): PipelineBuilder =
    andThen(ConditionalOps.ifExists(path)(step))

  /** Adds a missing check operation to the pipeline.
    *
    * @see
    *   [[ConditionalOps.ifMissing]]
    */
  def ifMissing(path: JsPath)(step: Transformer): PipelineBuilder =
    andThen(ConditionalOps.ifMissing(path)(step))

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
