/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer

import io.github.mghmay.helpers.{JsonHelpers, SourceCleanup}
import play.api.libs.json.{JsObject, JsPath, JsResult, JsValue}

protected[transformer] object JsonTransformOps extends JsonHelpers {

  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Transformer =
    (json: JsObject) => movePath(from, to, json, cleanup)

  def copy(from: JsPath, to: JsPath): Transformer =
    (json: JsObject) => copyPath(from, to, json)

  def set(path: JsPath, value: JsValue): Transformer =
    (json: JsObject) => setNestedPath(path, value, json)

  def mergeAt(path: JsPath, obj: JsObject): Transformer =
    (json: JsObject) => deepMergeAt(json, path, obj)

  def pruneAggressive(path: JsPath): Transformer =
    (json: JsObject) => aggressivePrunePath(path, json)

  def pruneGentle(path: JsPath): Transformer =
    (json: JsObject) => gentlePrunePath(path, json)

  def rename(from: JsPath, to: JsPath): Transformer =
    move(from, to, SourceCleanup.Aggressive)

  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Transformer =
    (json: JsObject) => mapAt(path, json)(vf)

  def when(pred: JsObject => Boolean)(step: Transformer): Transformer =
    (json: JsObject) => if (pred(json)) step(json) else Right(json)

  def ifExists(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isDefined)(step)

  def ifMissing(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isEmpty)(step)

}
