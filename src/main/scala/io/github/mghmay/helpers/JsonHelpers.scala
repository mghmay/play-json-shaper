/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.helpers

import play.api.libs.json._

/** Low-level helpers for shaping Play JSON JsObjects. These power the public Shaper API and can be reused if
  * needed.
  */
trait JsonHelpers {

  /** Move the JSON node at 'from' to 'to', creating destination parents as needed.
    *
    * Semantics
    *   - Strict: fails if 'from' does not resolve to a single value.
    *   - Destination: writes the captured value at 'to' (object values are deep-merged at the destination).
    *     No pruning at destination.
    *   - Source cleanup:
    *     - [[SourceCleanup.Aggressive]]: remove the moved key and recursively prune empty parents.
    *     - [[SourceCleanup.Tombstone]] : set a 'null' tombstone at the exact 'from' path (parents unchanged).
    *
    * Overlapping paths are well-defined (capture, cleanup, set). If 'to' is a descendant of 'from' and
    * cleanup is Tombstone, the tombstone may be replaced when writing to 'to' (parents are recreated).
    *
    * Moving an object deep-merges at the destination; intermediate non-object parents are replaced with
    * objects as per deepMergeAt semantics.
    */
  final def movePath(
      from: JsPath,
      to: JsPath,
      json: JsObject,
      cleanup: SourceCleanup = SourceCleanup.Aggressive
  ): Either[JsError, JsObject] = {
    if (from == to) Right(json)
    else
      from.asSingleJson(json) match {
        case JsDefined(value) =>
          val afterSource: Either[JsError, JsObject] = cleanup match {
            case SourceCleanup.Aggressive => aggressivePrunePath(from, json)
            case SourceCleanup.Tombstone  => setNestedPath(from, JsNull, json)
          }

          afterSource.flatMap { withoutOld =>
            value match {
              case o: JsObject =>
                deepMergeAt(withoutOld, to, o)
              case _           =>
                setNestedPath(to, value, withoutOld)
            }
          }

        case _: JsUndefined =>
          Left(
            JsError(
              Seq(from -> Seq(
                JsonValidationError(s"movePath: source '$from' not found or not unique; target='$to'")))
            )
          )
      }
  }

  /** Copy the JSON node at 'from' to 'to', creating destination parents as needed.
    *
    * If 'to' is a descendant of 'from', the write proceeds and source parents remain untouched.
    */
  final def copyPath(from: JsPath, to: JsPath, json: JsObject): Either[JsError, JsObject] =
    if (from == to) Right(json)
    else
      from.asSingleJson(json) match {
        case JsDefined(value) => setNestedPath(to, value, json)
        case _: JsUndefined   =>
          Left(JsError(Seq(from -> Seq(JsonValidationError(
                  s"copyPath: source '$from' not found or not unique; target='$to'"
                )))))
      }

  /** Transform the value at 'path' using a validator/mapping function 'vf'.
    *
    * @param path
    *   Path of the node to transform. Must resolve to a single value.
    * @param json
    *   Input object.
    * @param vf
    *   Function that takes the current JsValue and returns the replacement JsValue or a JsError.
    */
  final def mapAt(
      path: JsPath,
      json: JsObject
  )(vf: JsValue => JsResult[JsValue]): Either[JsError, JsObject] =
    path.asSingleJson(json) match {
      case _: JsUndefined =>
        Left(JsError(Seq(path -> Seq(JsonValidationError("mapAt: path not found or not unique")))))
      case JsDefined(v)   =>
        vf(v) match {
          case JsSuccess(next, _) => setNestedPath(path, next, json)
          case JsError(errs)      =>
            val prefixed = errs.map { case (p, es) => (path ++ p, es) }
            Left(JsError(prefixed))
        }
    }

  /** Remove value at path and any empty parent objects. Fails if any segment is unsupported (e.g.
    * IdxPathNode) or the path doesn't exist.
    */
  final def aggressivePrunePath(path: JsPath, json: JsObject): Either[JsError, JsObject] = {
    def loop(cur: JsObject, nodes: List[PathNode]): Either[JsError, JsObject] = nodes match {
      case KeyPathNode(k) :: Nil =>
        if (cur.keys.contains(k)) Right(cur - k)
        else Left(JsError(Seq(path -> Seq(JsonValidationError("aggressivePrunePath: path not found")))))

      case KeyPathNode(parent) :: rest =>
        (cur \ parent).toOption match {
          case Some(child: JsObject) =>
            loop(child, rest).map { prunedChild =>
              if (prunedChild.value.isEmpty) cur - parent else cur + (parent -> prunedChild)
            }
          case Some(_)               =>
            Left(JsError(Seq(path -> Seq(JsonValidationError(s"aggressivePrunePath: expected object at '$parent'")))))
          case None                  =>
            Left(JsError(Seq(path -> Seq(JsonValidationError("aggressivePrunePath: path not found")))))
        }

      case _ =>
        Left(JsError(
            Seq(path -> Seq(JsonValidationError("aggressivePrunePath: unsupported path segment (arrays not supported)")))))
    }

    path.path match {
      case Nil => Left(JsError(Seq(path -> Seq(JsonValidationError("aggressivePrunePath: empty path")))))
      case ns  => loop(json, ns)
    }
  }

  /** Gentle prune: removes the node, keeps now-empty parents intact. Fails if the path does not exist or
    * contains unsupported segments.
    */
  final def gentlePrunePath(path: JsPath, json: JsObject): Either[JsError, JsObject] = {
    path.asSingleJson(json) match {
      case _: JsUndefined =>
        Left(JsError(Seq(path -> Seq(JsonValidationError("gentlePrunePath: path not found")))))
      case JsDefined(_)   =>
        path.prune(json) match {
          case JsSuccess(updated, _) => Right(updated)
          case JsError(_)            =>
            Left(JsError(Seq(path -> Seq(
                  JsonValidationError("gentlePrunePath: path not found or unsupported (arrays not supported)")
                ))))
        }
    }
  }

  private def getChildObj(current: JsObject, k: String) =
    (current \ k).toOption.collect { case o: JsObject => o }.getOrElse(Json.obj())

  /** Deep-merge a JsObject at path, creating parents as needed.
    *
    * Semantics
    *   - Creates any missing parents as empty objects.
    *   - If a parent exists but is not a JsObject, it is **replaced** with a new JsObject so the merge can
    *     proceed at the target.
    *   - At the target node, performs a deep merge (object fields are merged; non-conflicting keys are
    *     preserved). Array segments (IdxPathNode) are not supported.
    *
    * Examples:
    * {{{
    * // Replace a scalar parent and merge at a deeper node: //
    * { "a": 1 } -> deepMergeAt(__ \ "a" \
    * "b", { "x": 1 }) // becomes { "a": { "b": { "x": 1 } } }
    *
    * // Merge into existing object: //
    * { "ctx": { "env": "dev" } } + mergeAt(__ \ "ctx", { "version": 3 }) //
    * -> { "ctx": { "env": "dev", "version": 3 } }
    * }}}
    */

  final def deepMergeAt(json: JsObject, path: JsPath, value: JsObject): Either[JsError, JsObject] = {
    val hasArraySeg = path.path.exists {
      case _: IdxPathNode => true
      case _              => false
    }
    if (hasArraySeg)
      Left(JsError(Seq(path -> Seq(
            JsonValidationError("deepMergeAt: unsupported path segment (arrays not supported)")))))
    else {
      def loop(current: JsObject, nodes: List[PathNode]): JsObject = nodes match {
        case Nil                    =>
          current.deepMerge(value)
        case KeyPathNode(k) :: tail =>
          val child = getChildObj(current, k)
          current + (k -> loop(child, tail))
        case _                      =>
          // $COVERAGE-OFF$
          // Unreachable: IdxPathNode is filtered by the `hasArraySeg` pre-check above.
          // This is just a defensive fallback.
          current
        // $COVERAGE-ON$
      }
      Right(loop(json, path.path))
    }
  }

  /** Recursive set that builds parent objects as needed.
    *
    * Semantics
    *   - Creates any missing parents as empty objects.
    *   - If a parent exists but is not a JsObject (e.g., number/string/bool/null), it is **replaced** with a
    *     new JsObject to allow descending to the target leaf.
    *   - Setting an empty object `{}` at a leaf **keeps** the key (does not remove it).
    *   - Array segments (IdxPathNode) are not supported and return a JsError.
    *
    * Examples
    * {{{
    * // Replace a scalar parent with an object to set a deeper field: //
    * { "a": 1 } ->
    * setNestedPath(__ \ "a" \ "b", 2) -> { "a": { "b": 2 } }
    *
    * // Create missing parents: //
    * { } -> setNestedPath(__ \ "x" \ "y", 1) -> { "x": { "y": 1 } }
    * }}}
    */

  final def setNestedPath(path: JsPath, value: JsValue, json: JsObject): Either[JsError, JsObject] =
    path.path match {
      case Nil                    => Right(json)
      case KeyPathNode(k) :: Nil  => Right(json + (k -> value))
      case KeyPathNode(h) :: tail =>
        val child = getChildObj(json, h)
        setNestedPath(JsPath(tail), value, child).map { nested =>
          json + (h -> nested)
        }
      case _                      =>
        Left(JsError(
            Seq(path -> Seq(
              JsonValidationError("setNestedPath: unsupported path segment (arrays not supported)")))))
    }
}
