package io.github.mghmay.shaper

import io.github.mghmay.shaper.JsonHelpers.SourceCleanup
import play.api.libs.json._

class ShaperApi(private val H: JsonHelpers) {

  type TransformerStep = JsObject => Either[JsError, JsObject]

  def pipeline(steps: Seq[TransformerStep]): TransformerStep =
    steps.foldLeft[TransformerStep](Right(_)) { (acc, step) => (json: JsObject) => acc(json).flatMap(step) }

  def start: Pipeline = Pipeline(Vector.empty)

  def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): TransformerStep =
    (json: JsObject) => H.movePath(from, to, json, cleanup)

  def copy(from: JsPath, to: JsPath): TransformerStep =
    (json: JsObject) => H.copyPath(from, to, json)

  def set(path: JsPath, value: JsValue): TransformerStep =
    (json: JsObject) => H.setNestedPath(path, value, json)

  def mergeAt(path: JsPath, obj: JsObject): TransformerStep =
    (json: JsObject) => H.deepMergeAt(json, path, obj)

  def pruneAggressive(path: JsPath): TransformerStep =
    (json: JsObject) => H.aggressivePrunePath(path, json)

  def pruneGentle(path: JsPath): TransformerStep =
    (json: JsObject) => H.gentlePrunePath(path, json)

  def rename(from: JsPath, to: JsPath): TransformerStep =
    move(from, to, SourceCleanup.Aggressive)

  def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): TransformerStep =
    (json: JsObject) =>
      path.asSingleJson(json) match {
        case _: JsUndefined =>
          Left(JsError(Seq(path -> Seq(JsonValidationError("mapAt: path not found or not unique")))))
        case JsDefined(v)   =>
          vf(v) match {
            case JsSuccess(next, _) => H.setNestedPath(path, next, json)
            case JsError(errs)      =>
              val prefixed = errs.map { case (p, es) => (path ++ p, es) }
              Left(JsError(prefixed))
          }
      }

  /** Conditionally run a step; otherwise return input unchanged. */
  def when(pred: JsObject => Boolean)(step: TransformerStep): TransformerStep =
    (json: JsObject) => if (pred(json)) step(json) else Right(json)

  /** Run only if `path` resolves to a single value (like asSingleJson isDefined). */
  def ifExists(path: JsPath)(step: TransformerStep): TransformerStep =
    when(json => path.asSingleJson(json).isDefined)(step)

  /** Run only if `path` is missing (or not uniquely addressed). */
  def ifMissing(path: JsPath)(step: TransformerStep): TransformerStep =
    when(json => path.asSingleJson(json).isEmpty)(step)


  final case class Pipeline(private val steps: Vector[TransformerStep]) {

    def andThen(other: Pipeline): Pipeline = Pipeline(steps ++ other.steps)

    def step(f: TransformerStep): Pipeline = Pipeline(steps :+ f)

    def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Pipeline =
      step(Shaper.thisApi.move(from, to, cleanup))

    def copy(from: JsPath, to: JsPath): Pipeline =
      step(Shaper.thisApi.copy(from, to))

    def set(path: JsPath, value: JsValue): Pipeline =
      step(Shaper.thisApi.set(path, value))

    def mergeAt(path: JsPath, obj: JsObject): Pipeline =
      step(Shaper.thisApi.mergeAt(path, obj))

    def pruneAggressive(path: JsPath): Pipeline =
      step(Shaper.thisApi.pruneAggressive(path))

    def pruneGentle(path: JsPath): Pipeline =
      step(Shaper.thisApi.pruneGentle(path))

    def rename(from: JsPath, to: JsPath): Pipeline =
      step(Shaper.thisApi.rename(from, to))

    def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Pipeline =
      step(Shaper.thisApi.mapAt(path)(vf))

    def when(pred: JsObject => Boolean)(build: ShaperApi => TransformerStep): Pipeline =
      step(Shaper.thisApi.when(pred)(build(Shaper.thisApi)))

    def ifExists(path: JsPath)(build: ShaperApi => TransformerStep): Pipeline =
      step(Shaper.thisApi.ifExists(path)(build(Shaper.thisApi)))

    def ifMissing(path: JsPath)(build: ShaperApi => TransformerStep): Pipeline =
      step(Shaper.thisApi.ifMissing(path)(build(Shaper.thisApi)))

    def toStep: TransformerStep = Shaper.thisApi.pipeline(steps)

    def run(json: JsObject): Either[JsError, JsObject] = toStep(json)

  }
}

object Shaper extends ShaperApi(DefaultJsonHelpers) {
  private[shaper] val thisApi: ShaperApi = this
}
