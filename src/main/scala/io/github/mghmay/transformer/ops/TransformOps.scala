/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer.ops

import io.github.mghmay.helpers.{JsonHelpers, SourceCleanup}
import io.github.mghmay.transformer.{Predicate, Transformer}
import play.api.libs.json.{JsObject, JsPath, JsResult, JsValue}

protected[transformer] object TransformOps extends JsonHelpers {

  /** Moves a JSON value from one path to another.
    *
    * @param from
    *   The source path to move the value from
    * @param to
    *   The destination path to move the value to
    * @param cleanup
    *   The cleanup strategy for the source location (default: Aggressive)
    * @return
    *   A Transformer that performs the move operation
    * @note
    *   Fails if the source path does not exist or resolves to multiple values
    * @example
    *   {{{val mover = JsonTransform.move(__ \ "user" \ "name", __ \ "person" \ "fullName")}}}
    */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Transformer =
    (json: JsObject) => movePath(from, to, json, cleanup)

  /** Copies a JSON value from one path to another.
    *
    * @param from
    *   The source path to copy the value from
    * @param to
    *   The destination path to copy the value to
    * @return
    *   A Transformer that performs the copy operation
    * @note
    *   Fails if the source path does not exist or resolves to multiple values
    * @example
    *   {{{val copier = JsonTransform.copy(__ \ "original", __ \ "backup")}}}
    */
  def copy(from: JsPath, to: JsPath): Transformer =
    (json: JsObject) => copyPath(from, to, json)

  /** Sets a JSON value at the specified path, creating intermediate objects as needed.
    *
    * @param path
    *   The path where the value should be set
    * @param value
    *   The value to set at the specified path
    * @return
    *   A Transformer that sets the value at the given path
    * @example
    *   {{{val setter = JsonTransform.set(__ \ "metadata" \ "version", JsString("1.0"))}}}
    */
  def set(path: JsPath, value: JsValue): Transformer =
    (json: JsObject) => setNestedPath(path, value, json)

  /** Deeply merges a JsObject at the specified path.
    *
    * @param path
    *   The path where the merge should occur
    * @param obj
    *   The object to merge at the specified path
    * @return
    *   A Transformer that performs the deep merge operation
    * @note
    *   Currently does not support array path segments
    * @example
    *   {{{val merger = JsonTransform.mergeAt(__ \ "config", Json.obj("debug" -> JsTrue))}}}
    */
  def mergeAt(path: JsPath, obj: JsObject): Transformer =
    (json: JsObject) => deepMergeAt(json, path, obj)

  /** Aggressively prunes a path, removing the target node and any empty parent objects.
    *
    * @param path
    *   The path to prune
    * @return
    *   A Transformer that performs aggressive pruning
    * @note
    *   Fails if the path contains array segments or doesn't exist
    * @example
    *   {{{val pruner = JsonTransform.pruneAggressive(__ \ "temp" \ "cache")}}}
    */
  def pruneAggressive(path: JsPath): Transformer =
    (json: JsObject) => aggressivePrunePath(path, json)

  /** Gently prunes a path, removing only the target node while keeping parent objects intact.
    *
    * @param path
    *   The path to prune
    * @return
    *   A Transformer that performs gentle pruning
    * @note
    *   Fails if the path contains array segments or doesn't exist
    * @example
    *   {{{val pruner = JsonTransform.pruneGentle(__ \ "metadata" \ "timestamp")}}}
    */
  def pruneGentle(path: JsPath): Transformer =
    (json: JsObject) => gentlePrunePath(path, json)

  /** Renames a field by moving it to a new path with aggressive source cleanup.
    *
    * @param from
    *   The source path of the field to rename
    * @param to
    *   The destination path for the renamed field
    * @return
    *   A Transformer that performs the rename operation
    * @example
    *   {{{val renamer = JsonTransform.rename(__ \ "oldName", __ \ "newName")}}}
    */
  def rename(from: JsPath, to: JsPath): Transformer =
    move(from, to, SourceCleanup.Aggressive)

  /** Transforms a value at the specified path using a mapping function.
    *
    * @param path
    *   The path of the value to transform
    * @param vf
    *   The transformation function that takes the current value and returns the new value or error
    * @return
    *   A Transformer that applies the mapping function at the specified path
    * @example
    *   {{{ val mapper = JsonTransform.mapAt(__ \ "count") { case JsNumber(n) => JsSuccess(JsNumber(n + 1))
    *   case _ => JsError("Expected number") } }}}
    */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Transformer =
    (json: JsObject) => mapAt(path, json)(vf)

}
