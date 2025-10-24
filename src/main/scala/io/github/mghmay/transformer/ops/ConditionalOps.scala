/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer.ops

import io.github.mghmay.transformer.{Predicate, Transformer}
import play.api.libs.json.{JsObject, JsPath}

protected[transformer] object ConditionalOps {

  /** Conditionally executes a transformation based on a predicate.
    *
    * @param pred
    *   The predicate function that determines whether to execute the step
    * @param step
    *   The transformation to execute conditionally
    * @return
    *   A Transformer that executes the step only when the predicate returns true
    * @example
    *   {{{val conditional = JsonTransform.when(_.keys.contains("admin"))(adminTransformer)}}}
    */
  def when(pred: Predicate)(step: Transformer): Transformer =
    (json: JsObject) => if (pred(json)) step(json) else Right(json)

  /** Executes a transformation only if the specified path exists and resolves to a single value.
    *
    * @param path
    *   The path to check for existence
    * @param step
    *   The transformation to execute if the path exists
    * @return
    *   A Transformer that executes the step only when the path exists
    * @example
    *   {{{val ifUserExists = JsonTransform.ifExists(__ \ "user")(userTransformer)}}}
    */
  def ifExists(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isDefined)(step)

  /** Executes a transformation only if the specified path is missing or doesn't resolve to a single value.
    *
    * @param path
    *   The path to check for absence
    * @param step
    *   The transformation to execute if the path is missing
    * @return
    *   A Transformer that executes the step only when the path is missing
    * @example
    *   {{{val ifNoUser = JsonTransform.ifMissing(__ \ "user")(defaultUserTransformer)}}}
    */
  def ifMissing(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isEmpty)(step)

}
