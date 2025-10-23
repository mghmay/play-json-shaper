package io.github.mghmay.transformer

import io.github.mghmay.transformer.JsonHelpers.SourceCleanup
import play.api.libs.json.{JsObject, JsPath, JsResult, JsValue}

object JsonTransformOps {
  /**
   * Moves a JSON value from one path to another.
   *
   * @param from The source path to move the value from
   * @param to The destination path to move the value to
   * @param cleanup The cleanup strategy for the source location (default: Aggressive)
   * @return A Transformer that performs the move operation
   * @note Fails if the source path does not exist or resolves to multiple values
   * @example {{{
   * val mover = Shaper.move(__ \ "user" \ "name", __ \ "person" \ "fullName")
   * }}}
   */
  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Transformer =
    (json: JsObject) => DefaultJsonHelpers.movePath(from, to, json, cleanup)

  /**
   * Copies a JSON value from one path to another.
   *
   * @param from The source path to copy the value from
   * @param to The destination path to copy the value to
   * @return A Transformer that performs the copy operation
   * @note Fails if the source path does not exist or resolves to multiple values
   * @example {{{
   * val copier = Shaper.copy(__ \ "original", __ \ "backup")
   * }}}
   */
  def copy(from: JsPath, to: JsPath): Transformer =
    (json: JsObject) => DefaultJsonHelpers.copyPath(from, to, json)

  /**
   * Sets a JSON value at the specified path, creating intermediate objects as needed.
   *
   * @param path The path where the value should be set
   * @param value The value to set at the specified path
   * @return A Transformer that sets the value at the given path
   * @example {{{
   * val setter = Shaper.set(__ \ "metadata" \ "version", JsString("1.0"))
   * }}}
   */
  def set(path: JsPath, value: JsValue): Transformer =
    (json: JsObject) => DefaultJsonHelpers.setNestedPath(path, value, json)

  /**
   * Deeply merges a JsObject at the specified path.
   *
   * @param path The path where the merge should occur
   * @param obj The object to merge at the specified path
   * @return A Transformer that performs the deep merge operation
   * @note Currently does not support array path segments
   * @example {{{
   * val merger = Shaper.mergeAt(__ \ "config", Json.obj("debug" -> JsTrue))
   * }}}
   */
  def mergeAt(path: JsPath, obj: JsObject): Transformer =
    (json: JsObject) => DefaultJsonHelpers.deepMergeAt(json, path, obj)

  /**
   * Aggressively prunes a path, removing the target node and any empty parent objects.
   *
   * @param path The path to prune
   * @return A Transformer that performs aggressive pruning
   * @note Fails if the path contains array segments or doesn't exist
   * @example {{{
   * val pruner = Shaper.pruneAggressive(__ \ "temp" \ "cache")
   * }}}
   */
  def pruneAggressive(path: JsPath): Transformer =
    (json: JsObject) => DefaultJsonHelpers.aggressivePrunePath(path, json)

  /**
   * Gently prunes a path, removing only the target node while keeping parent objects intact.
   *
   * @param path The path to prune
   * @return A Transformer that performs gentle pruning
   * @note Fails if the path contains array segments or doesn't exist
   * @example {{{
   * val pruner = Shaper.pruneGentle(__ \ "metadata" \ "timestamp")
   * }}}
   */
  def pruneGentle(path: JsPath): Transformer =
    (json: JsObject) => DefaultJsonHelpers.gentlePrunePath(path, json)

  /**
   * Renames a field by moving it to a new path with aggressive source cleanup.
   *
   * @param from The source path of the field to rename
   * @param to The destination path for the renamed field
   * @return A Transformer that performs the rename operation
   * @example {{{
   * val renamer = Shaper.rename(__ \ "oldName", __ \ "newName")
   * }}}
   */
  def rename(from: JsPath, to: JsPath): Transformer =
    move(from, to, SourceCleanup.Aggressive)

  /**
   * Transforms a value at the specified path using a mapping function.
   *
   * @param path The path of the value to transform
   * @param vf The transformation function that takes the current value and returns the new value or error
   * @return A Transformer that applies the mapping function at the specified path
   * @example {{{
   * val mapper = Shaper.mapAt(__ \ "count") {
   *   case JsNumber(n) => JsSuccess(JsNumber(n + 1))
   *   case _ => JsError("Expected number")
   * }
   * }}}
   */
  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Transformer =
    (json: JsObject) => DefaultJsonHelpers.mapAt(path, json)(vf)

  /**
   * Conditionally executes a transformation based on a predicate.
   *
   * @param pred The predicate function that determines whether to execute the step
   * @param step The transformation to execute conditionally
   * @return A Transformer that executes the step only when the predicate returns true
   * @example {{{
   * val conditional = Shaper.when(_.keys.contains("admin"))(adminTransformer)
   * }}}
   */
  def when(pred: JsObject => Boolean)(step: Transformer): Transformer =
    (json: JsObject) => if (pred(json)) step(json) else Right(json)

  /**
   * Executes a transformation only if the specified path exists and resolves to a single value.
   *
   * @param path The path to check for existence
   * @param step The transformation to execute if the path exists
   * @return A Transformer that executes the step only when the path exists
   * @example {{{
   * val ifUserExists = Shaper.ifExists(__ \ "user")(userTransformer)
   * }}}
   */
  def ifExists(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isDefined)(step)

  /**
   * Executes a transformation only if the specified path is missing or doesn't resolve to a single value.
   *
   * @param path The path to check for absence
   * @param step The transformation to execute if the path is missing
   * @return A Transformer that executes the step only when the path is missing
   * @example {{{
   * val ifNoUser = Shaper.ifMissing(__ \ "user")(defaultUserTransformer)
   * }}}
   */
  def ifMissing(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isEmpty)(step)

}
