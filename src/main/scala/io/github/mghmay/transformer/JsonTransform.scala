package io.github.mghmay.transformer

import JsonHelpers.SourceCleanup
import play.api.libs.json._

/**
 * Main entry point for JSON transformation operations.
 * Provides a fluent API for building and executing JSON transformation pipelines.
 */
object JsonTransform extends JsonTransformImpl(DefaultJsonHelpers) {
  private[transformer] val thisApi: JsonTransformImpl = this
}

/**
 * Core implementation of the JSON transformation API.
 *
 * @param H The JsonHelpers instance providing low-level JSON manipulation operations
 */
class JsonTransformImpl(private val H: JsonHelpers) {

  /** A function that transforms a JsObject, returning either a transformed object or a JsError */
  type Transformer = JsObject => Either[JsError, JsObject]

  /**
   * Creates a new empty transformation pipeline.
   *
   * @return A new Pipeline instance with no transformation steps
   * @example {{{
   * val pipeline = Shaper.start
   *   .move(__ \ "old", __ \ "new")
   *   .copy(__ \ "source", __ \ "destination")
   * }}}
   */
  def start: Pipeline = Pipeline(Vector.empty)

  def empty: Pipeline = Pipeline(Vector.empty)

  /**
   * Composes multiple transformers into a single transformer that executes them sequentially.
   *
   * @param steps The sequence of transformers to compose
   * @return A single Transformer that executes all steps in sequence
   * @note If any step fails, the entire composition fails
   * @example {{{
   * val composed = Shaper.run(Seq(transformer1, transformer2, transformer3))
   * }}}
   */
  def run(steps: Seq[Transformer]): Transformer =
    steps.foldLeft[Transformer](Right(_)) { (acc, step) => (json: JsObject) => acc(json).flatMap(step) }

  /**
   * Alias for `run` - composes multiple transformers into a single transformer.
   *
   * @param steps The sequence of transformers to compose
   * @return A single Transformer that executes all steps in sequence
   * @example {{{
   * val composed = Shaper(transformer1, transformer2, transformer3)
   * }}}
   */
  def apply(steps: Seq[Transformer]): Transformer =
    run(steps)

  /**
   * A builder for creating transformation pipelines with a fluent API.
   *
   * @param steps The sequence of transformers in this pipeline
   */
  final case class Pipeline(private val steps: Vector[Transformer]) {

    /**
     * Appends another pipeline to this pipeline.
     *
     * @param other The pipeline to append
     * @return A new pipeline with both pipeline's steps combined
     */
    def andThen(other: Pipeline): Pipeline = Pipeline(steps ++ other.steps)

    /**
     * Appends a single transformer to this pipeline.
     *
     * @param f The transformer to append
     * @return A new pipeline with the additional transformer
     */
    def andThen(f: Transformer): Pipeline = Pipeline(steps :+ f)

    /**
     * Adds a move operation to the pipeline.
     *
     * @see [[JsonTransformImpl.move]]
     */
    def move(from: JsPath, to: JsPath, cleanup: SourceCleanup = SourceCleanup.Aggressive): Pipeline =
      andThen(JsonTransformOps.move(from, to, cleanup))

    /**
     * Adds a copy operation to the pipeline.
     *
     * @see [[JsonTransformImpl.copy]]
     */
    def copy(from: JsPath, to: JsPath): Pipeline =
      andThen(JsonTransformOps.copy(from, to))

    /**
     * Adds a set operation to the pipeline.
     *
     * @see [[JsonTransformImpl.set]]
     */
    def set(path: JsPath, value: JsValue): Pipeline =
      andThen(JsonTransformOps.set(path, value))

    /**
     * Adds a merge operation to the pipeline.
     *
     * @see [[JsonTransformImpl.mergeAt]]
     */
    def mergeAt(path: JsPath, obj: JsObject): Pipeline =
      andThen(JsonTransformOps.mergeAt(path, obj))

    /**
     * Adds an aggressive prune operation to the pipeline.
     *
     * @see [[JsonTransformImpl.pruneAggressive]]
     */
    def pruneAggressive(path: JsPath): Pipeline =
      andThen(JsonTransformOps.pruneAggressive(path))

    /**
     * Adds a gentle prune operation to the pipeline.
     *
     * @see [[JsonTransformImpl.pruneGentle]]
     */
    def pruneGentle(path: JsPath): Pipeline =
      andThen(JsonTransformOps.pruneGentle(path))

    /**
     * Adds a rename operation to the pipeline.
     *
     * @see [[JsonTransformImpl.rename]]
     */
    def rename(from: JsPath, to: JsPath): Pipeline =
      andThen(JsonTransformOps.rename(from, to))

    /**
     * Adds a map operation to the pipeline.
     *
     * @see [[JsonTransformImpl.mapAt]]
     */
    def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Pipeline =
      andThen(JsonTransformOps.mapAt(path)(vf))

    /**
     * Adds a conditional operation to the pipeline.
     *
     * @see [[JsonTransformImpl.when]]
     */
    def when(pred: JsObject => Boolean)(step: Transformer): Pipeline =
      andThen(JsonTransformOps.when(pred)(step))

    /**
     * Adds an existence check operation to the pipeline.
     *
     * @see [[JsonTransformImpl.ifExists]]
     */
    def ifExists(path: JsPath)(step: Transformer): Pipeline =
      andThen(JsonTransformOps.ifExists(path)(step))

    /**
     * Adds a missing check operation to the pipeline.
     *
     * @see [[JsonTransformImpl.ifMissing]]
     */
    def ifMissing(path: JsPath)(step: Transformer): Pipeline =
      andThen(JsonTransformOps.ifMissing(path)(step))

    /**
     * Builds the final transformer from this pipeline.
     *
     * @return A single Transformer that executes all pipeline steps in sequence
     */
    def build: Transformer = JsonTransformImpl.this(steps)

    /**
     * Executes the pipeline on a JSON object.
     *
     * @param json The JSON object to transform
     * @return Either the transformed object or a JsError if any step fails
     */
    def run(json: JsObject): Either[JsError, JsObject] = build(json)

    /**
     * Alias for `run` - executes the pipeline on a JSON object.
     *
     * @param json The JSON object to transform
     * @return Either the transformed object or a JsError if any step fails
     */
    def apply(json: JsObject): Either[JsError, JsObject] = run(json)
  }
}