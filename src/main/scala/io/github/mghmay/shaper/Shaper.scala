package io.github.mghmay.shaper

import io.github.mghmay.shaper.JsonHelpers.SourceCleanup
import play.api.libs.json._

class ShaperApi(private val H: JsonHelpers) {

  type Transformer = JsObject => Either[JsError, JsObject]

  def apply(steps: Seq[Transformer]): Transformer =
    steps.foldLeft[Transformer](Right(_)) { (acc, step) => (json: JsObject) => acc(json).flatMap(step) }

  def start: Pipeline = Pipeline(Vector.empty)

  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Transformer =
    (json: JsObject) => H.movePath(from, to, json, cleanup)

  def copy(from: JsPath, to: JsPath): Transformer =
    (json: JsObject) => H.copyPath(from, to, json)

  def set(path: JsPath, value: JsValue): Transformer =
    (json: JsObject) => H.setNestedPath(path, value, json)

  def mergeAt(path: JsPath, obj: JsObject): Transformer =
    (json: JsObject) => H.deepMergeAt(json, path, obj)

  def pruneAggressive(path: JsPath): Transformer =
    (json: JsObject) => H.aggressivePrunePath(path, json)

  def pruneGentle(path: JsPath): Transformer =
    (json: JsObject) => H.gentlePrunePath(path, json)

  def rename(from: JsPath, to: JsPath): Transformer =
    move(from, to, SourceCleanup.Aggressive)

  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Transformer =
    (json: JsObject) => H.mapAt(path, json)(vf)

  /** Conditionally run a step; otherwise return input unchanged. */
  def when(pred: JsObject => Boolean)(step: Transformer): Transformer =
    (json: JsObject) => if (pred(json)) step(json) else Right(json)

  /** Run only if `path` resolves to a single value (like asSingleJson isDefined). */
  def ifExists(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isDefined)(step)

  /** Run only if `path` is missing (or not uniquely addressed). */
  def ifMissing(path: JsPath)(step: Transformer): Transformer =
    when(json => path.asSingleJson(json).isEmpty)(step)


  final case class Pipeline(private val steps: Vector[Transformer]) {

    def andThen(other: Pipeline): Pipeline = Pipeline(steps ++ other.steps)

    def andThen(f: Transformer): Pipeline = Pipeline(steps :+ f)

    def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Pipeline =
      andThen(Shaper.thisApi.move(from, to, cleanup))

    def copy(from: JsPath, to: JsPath): Pipeline =
      andThen(Shaper.thisApi.copy(from, to))

    def set(path: JsPath, value: JsValue): Pipeline =
      andThen(Shaper.thisApi.set(path, value))

    def mergeAt(path: JsPath, obj: JsObject): Pipeline =
      andThen(Shaper.thisApi.mergeAt(path, obj))

    def pruneAggressive(path: JsPath): Pipeline =
      andThen(Shaper.thisApi.pruneAggressive(path))

    def pruneGentle(path: JsPath): Pipeline =
      andThen(Shaper.thisApi.pruneGentle(path))

    def rename(from: JsPath, to: JsPath): Pipeline =
      andThen(Shaper.thisApi.rename(from, to))

    def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Pipeline =
      andThen(Shaper.thisApi.mapAt(path)(vf))

    def when(pred: JsObject => Boolean)(step: Transformer): Pipeline =
      andThen(Shaper.thisApi.when(pred)(step))

    def ifExists(path: JsPath)(step: Transformer): Pipeline =
      andThen(Shaper.thisApi.ifExists(path)(step))

    def ifMissing(path: JsPath)(step: Transformer): Pipeline =
      andThen(Shaper.thisApi.ifMissing(path)(step))

    def build: Transformer = Shaper.thisApi(steps)

    def run(json: JsObject): Either[JsError, JsObject] = build(json)

  }
}

object Shaper extends ShaperApi(DefaultJsonHelpers) {
  private[shaper] val thisApi: ShaperApi = this
}
