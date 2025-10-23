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
    (json: JsObject) => H.movePath(from, to, json, cleanup)

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
    (json: JsObject) => H.copyPath(from, to, json)

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
    (json: JsObject) => H.setNestedPath(path, value, json)

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
    (json: JsObject) => H.deepMergeAt(json, path, obj)

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
    (json: JsObject) => H.aggressivePrunePath(path, json)

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
    (json: JsObject) => H.gentlePrunePath(path, json)

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
    (json: JsObject) => H.mapAt(path, json)(vf)

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
      andThen(JsonTransform.thisApi.move(from, to, cleanup))

    /**
     * Adds a copy operation to the pipeline.
     *
     * @see [[JsonTransformImpl.copy]]
     */
    def copy(from: JsPath, to: JsPath): Pipeline =
      andThen(JsonTransform.thisApi.copy(from, to))

    /**
     * Adds a set operation to the pipeline.
     *
     * @see [[JsonTransformImpl.set]]
     */
    def set(path: JsPath, value: JsValue): Pipeline =
      andThen(JsonTransform.thisApi.set(path, value))

    /**
     * Adds a merge operation to the pipeline.
     *
     * @see [[JsonTransformImpl.mergeAt]]
     */
    def mergeAt(path: JsPath, obj: JsObject): Pipeline =
      andThen(JsonTransform.thisApi.mergeAt(path, obj))

    /**
     * Adds an aggressive prune operation to the pipeline.
     *
     * @see [[JsonTransformImpl.pruneAggressive]]
     */
    def pruneAggressive(path: JsPath): Pipeline =
      andThen(JsonTransform.thisApi.pruneAggressive(path))

    /**
     * Adds a gentle prune operation to the pipeline.
     *
     * @see [[JsonTransformImpl.pruneGentle]]
     */
    def pruneGentle(path: JsPath): Pipeline =
      andThen(JsonTransform.thisApi.pruneGentle(path))

    /**
     * Adds a rename operation to the pipeline.
     *
     * @see [[JsonTransformImpl.rename]]
     */
    def rename(from: JsPath, to: JsPath): Pipeline =
      andThen(JsonTransform.thisApi.rename(from, to))

    /**
     * Adds a map operation to the pipeline.
     *
     * @see [[JsonTransformImpl.mapAt]]
     */
    def mapAt(path: JsPath)(vf: JsValue => JsResult[JsValue]): Pipeline =
      andThen(JsonTransform.thisApi.mapAt(path)(vf))

    /**
     * Adds a conditional operation to the pipeline.
     *
     * @see [[JsonTransformImpl.when]]
     */
    def when(pred: JsObject => Boolean)(step: Transformer): Pipeline =
      andThen(JsonTransform.thisApi.when(pred)(step))

    /**
     * Adds an existence check operation to the pipeline.
     *
     * @see [[JsonTransformImpl.ifExists]]
     */
    def ifExists(path: JsPath)(step: Transformer): Pipeline =
      andThen(JsonTransform.thisApi.ifExists(path)(step))

    /**
     * Adds a missing check operation to the pipeline.
     *
     * @see [[JsonTransformImpl.ifMissing]]
     */
    def ifMissing(path: JsPath)(step: Transformer): Pipeline =
      andThen(JsonTransform.thisApi.ifMissing(path)(step))

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