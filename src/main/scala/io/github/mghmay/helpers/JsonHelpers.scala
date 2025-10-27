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
    *   - Destination: writes the captured value at 'to' (overwrites if present). No pruning at destination.
    *   - Source cleanup:
    *     - [[SourceCleanup.Aggressive]]: remove the moved key and recursively prune empty parents.
    *     - [[SourceCleanup.Tombstone]] : set a 'null' tombstone at the exact 'from' path (parents unchanged).
    *
    * Overlapping paths are well-defined (capture, cleanup, set). If 'to' is a descendant of 'from' and
    * cleanup is Tombstone, the tombstone may be replaced when writing to 'to' (parents are recreated).
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
              case _ =>
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
        else Left(JsError(Seq(path -> Seq(JsonValidationError("prune: path not found")))))

      case KeyPathNode(parent) :: rest =>
        (cur \ parent).toOption match {
          case Some(child: JsObject) =>
            loop(child, rest).map { prunedChild =>
              if (prunedChild.value.isEmpty) cur - parent else cur + (parent -> prunedChild)
            }
          case Some(_)               =>
            Left(JsError(Seq(path -> Seq(JsonValidationError(s"prune: expected object at '$parent'")))))
          case None                  =>
            Left(JsError(Seq(path -> Seq(JsonValidationError("prune: path not found")))))
        }

      case _ =>
        Left(JsError(
            Seq(path -> Seq(JsonValidationError("prune: unsupported path segment (arrays not supported)")))))
    }

    path.path match {
      case Nil => Left(JsError(Seq(path -> Seq(JsonValidationError("prune: empty path")))))
      case ns  => loop(json, ns)
    }
  }

  /** Gentle prune: removes the node, keeps now-empty parents intact. Fails if the path does not exist or
    * contains unsupported segments.
    */
  final def gentlePrunePath(path: JsPath, json: JsObject): Either[JsError, JsObject] = {
    path.asSingleJson(json) match {
      case _: JsUndefined =>
        Left(JsError(Seq(path -> Seq(JsonValidationError("prune: path not found")))))
      case JsDefined(_)   =>
        path.prune(json) match {
          case JsSuccess(updated, _) => Right(updated)
          case JsError(_)            =>
            Left(JsError(Seq(path -> Seq(
                  JsonValidationError("prune: path not found or unsupported (arrays not supported)")
                ))))
        }
    }
  }

  private def getChildObj(current: JsObject, k: String) =
    (current \ k).toOption.collect { case o: JsObject => o }.getOrElse(Json.obj())

  /** Deep-merge a JsObject at path, creating parents as needed. Fails with JsError if the path contains array
    * indices (IdxPathNode).
    */
  final def deepMergeAt(json: JsObject, path: JsPath, value: JsObject): Either[JsError, JsObject] = {
    val hasArraySeg = path.path.exists {
      case _: IdxPathNode => true
      case _              => false
    }
    if (hasArraySeg)
      Left(JsError(Seq(path -> Seq(
            JsonValidationError("deepMergeAt: array segments (IdxPathNode) not supported at this time")))))
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

  /** Recursive set that builds parent objects as needed. */
  final def setNestedPath(path: JsPath, value: JsValue, json: JsObject): Either[JsError, JsObject] =
    path.path match {
      case Nil                    => Right(json)
      case KeyPathNode(k) :: Nil =>
        value match {
          case o: JsObject if o.value.isEmpty =>
            Right(json - k)
          case _ =>
            Right(json + (k -> value))
        }
      case KeyPathNode(h) :: tail =>
        val child = getChildObj(json, h)
        setNestedPath(JsPath(tail), value, child).map { nested =>
          json + (h -> nested)
        }
      case _                      =>
        Left(JsError(
            Seq(path -> Seq(JsonValidationError("set: unsupported path segment (arrays not supported)")))))
    }
}
