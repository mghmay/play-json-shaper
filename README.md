# play-json-shaper

Tiny, opinionated helpers for building **pure**, **composable** JSON transformations on top of Play JSON.

- Two ways to build pipelines: **fluent** or **direct composition**
- Safe short-circuiting: failures stop the pipeline with a `JsError`
- Low-level helpers (move/copy/set/merge/prune/mapAt) with clear semantics  
  *(See the Scaladoc on each helper—this README keeps things high level.)*

---

## Getting started

```scala
// build.sbt
libraryDependencies += "io.github.mghmay" %% "play-json-shaper" % "<version>"
```

```scala
import io.github.mghmay.transformer._
import io.github.mghmay.transformer.syntax._   // <- brings move, set, mergeAt, ...
import play.api.libs.json._
```

A `Transformer` is:

```scala
type Transformer = JsObject => Either[JsError, JsObject]
```
This base type either returns a transformed `JsObject` or a `JsError` explaining the problem. Transformers are then chained together to create composable pipelines.

---

## Two ways to use it

### 1) Fluent pipelines

Use `JsonTransform.start` and chain steps. Build once, run many.

```scala
import io.github.mghmay.transformer._
import io.github.mghmay.transformer.syntax._
import play.api.libs.json._

val pipeline =
  JsonTransform
    .move(__ \ "old", __ \ "new")  // default Aggressive source cleanup
    .mapAt(__ \ "new")(v => v.validate[String].map(s => JsString(s.toUpperCase)))
    .mergeAt(__ \ "ctx", Json.obj("version" -> 7))
    .pruneGentle(__ \ "keep" \ "delete")
    .pruneAggressive(__ \ "delete" \ "delete")
    .copy(__ \ "ctx", __ \ "copied")
    .build

val in  = Json.obj("old" -> "hello", "ctx" -> Json.obj("env" -> "dev"))
val out = pipeline(in)
// Either[JsError, JsObject]
```

You can also compose pipelines:

```scala
val p1 = JsonTransform.set(__ \ "a", JsNumber(1))
val p2 = JsonTransform.set(__ \ "b", JsNumber(2))
val p  = p1 andThen p2
```

### 2) Direct composition (no builder)

Compose plain functions when that reads nicer (for-comprehensions, lists, etc.):

```scala
import io.github.mghmay.transformer.syntax._
import play.api.libs.json._

val viaFor = for {
  j1 <- move(__ \ "a" \ "name", __ \ "person" \ "name")
    (Json.obj("a" -> Json.obj("name" -> "Ada")))
  j2 <- mapAt(__ \ "person" \ "name")
    (v => v.validate[String].map(n => JsString(n.reverse)))(j1)
  j3 <- mergeAt(__ \ "meta", Json.obj("ok" -> true))(j2)
} yield j3
```

Or use `JsonTransform.apply`/`compose` to fold a sequence of steps:

```scala
val f: Transformer = JsonTransform(
  set(__ \ "x", JsNumber(2)),
  mergeAt(__ \ "ctx", Json.obj("v" -> 3))
)
val out = f(Json.obj("x" -> 1))
```

---

## The `syntax` import (ergonomics)

The `move`, `set`, `copy` etc. functions are all imported through the syntax object:

```scala
import io.github.mghmay.transformer.syntax._

val t: Transformer =
  when(_.keys.contains("admin"))(
    set(__ \ "ctx" \ "isAdmin", JsBoolean(true))
  )
```
- `move(from, to)` uses **Aggressive** cleanup by default. This aggressively prunes the parent tree if it is empty after the move. To override:

```scala
import io.github.mghmay.transformer.syntax._
import io.github.mghmay.transformer.JsonHelpers.SourceCleanup

val tombstoneMove = move(__ \ "src", __ \ "dst", SourceCleanup.Tombstone)
// This leaves a null JsValue at the moved from source node.
```

---

## Conditionals

These are just combinators returning new `Transformer`s:

```scala
when(_.keys.exists(_ == "n"))(set(__ \ "flag", JsBoolean(true)))

ifExists(__ \ "user")(mergeAt(__ \ "seen", Json.obj("user" -> true)))

ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))
```

They compose cleanly both in the fluent builder and direct style.

---

## Error handling & short-circuiting

If a step fails, the **entire pipeline stops**, and you get that `JsError` back:

```scala
val failing: Transformer = _ => Left(JsError(__ \ "oops", JsonValidationError("boom")))
val wouldSet            = set(__ \ "shouldNotExist", JsBoolean(true))

val out = JsonTransform(failing, wouldSet)(Json.obj())
// => Left(JsError(...)) ; second step is not applied
```

---

## What each operation does (semantics)

This is documented in `JsonHelpers` and `syntax`. The public operations (`move`, `copy`, `set`, `mergeAt`, `pruneAggressive`, `pruneGentle`, `rename`, `mapAt`).

---

## Design notes

- **Separation of concerns**
    - `JsonHelpers`: precise, low-level json operations.
    - `syntax`: tiny, user-facing import with nice defaults and names.
    - `JsonTransform`: builder + composition utilities.
- **No hidden state**. All pieces are pure functions over `JsObject`.
- **Default cleanup** for `move` is **Aggressive** (tombstones opt-in).
- **Pipelines** are highly composable and reusable.
- This design is based on my work with HMRC where I had to design a small json pipeline and some helper functions

---

## Examples

**Rename by moving:**
```scala
JsonTransform
  .rename(__ \ "firstName", __ \ "name")
  .build
```

**Compute & set:**
```scala
val incCount =
  mapAt(__ \ "count") {
    case JsNumber(n) => JsSuccess(JsNumber(n + 1))
    case _           => JsError("Expected number")
  }
```

**Guarded write:**
```scala
val ensureVersion =
  ifMissing(__ \ "ctx" \ "version")(
    mergeAt(__ \ "ctx", Json.obj("version" -> 1))
  )
```

---

## Testing

- Asserts success paths with `.toOption.get` on the `Either`.
- Asserts failures by checking `JsError` anchors (paths) and messages.
- Builder output equality: both fluent and direct compositions create equal outputs

---

## License

MIT © 2025 Mathew May


---

### To-dos

- Filter out non-object intermediary values (enforce stricter parent types)
- Add support for array paths (`IdxPathNode`)
- Add transformation combinators (e.g. `copyPath`, `renamePath`, batch moves)
- Add a way to deal with enums and perhaps map them
- For very large pipelines, the current foldLeft approach creates intermediate functions. For extreme performance, it could be compiled to a single function
- Transformer could return non-blocking errors that get collected in a sequence similar to cats `Validated`
