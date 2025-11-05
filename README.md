# play-json-shaper

[![codecov](https://codecov.io/gh/mghmay/play-json-shaper/branch/main/graph/badge.svg)](https://codecov.io/gh/mghmay/play-json-shaper)


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
val p  = p1 |> p2           // or: p1 ++ p2
```
_(There is also an `.andThen` extension, but because it can be confused with `Function1#andThen`, prefer |> in docs and code.)_

### 2) Direct composition (no builder)

Compose plain functions when that reads nicer (for-comprehensions, lists, etc.):

```scala
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
```

You can also compose transformers directly with `|>`

```scala
val t: Transformer =
  set(__ \ "x", JsNumber(2)) |> mergeAt(__ \ "ctx", Json.obj("v" -> 3))
```

---

## The `syntax` import (ergonomics)

The `move`, `set`, `copy` etc. functions are all imported through the syntax object:

```scala
val t: Transformer =
  when(_.keys.contains("admin"))(
    set(__ \ "ctx" \ "isAdmin", JsBoolean(true))
  )
```
- `move(from, to)` uses **Aggressive** cleanup by default. This aggressively prunes the parent tree if it is empty after the move. To override:

```scala
val tombstoneMove = move(__ \ "src", __ \ "dst", SourceCleanup.Tombstone)
// This leaves a null JsValue at the moved from source node.
```

### Pipe operators you’ll use all the time:

- `json |> transformer` - apply a transformer to a JsObject
- `(either |> transformer)` - chain the next transformer if the previous step succeeded
- `transformer |> transformer` - compose transformers
- `builder |> transformer` / `builder |> builder` / `builder ++ builder` - compose fluent pipelines

---

## Conditionals

These are just combinators returning new `Transformer`s:

```scala
when(_.keys.exists(_ == "n"))(set(__ \ "flag", JsBoolean(true)))

ifExists(__ \ "user")(mergeAt(__ \ "seen", Json.obj("user" -> true)))

ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))
```

They compose cleanly both in the fluent builder and direct style.

### Predicates

A predicate is:
```scala
  type Predicate = JsObject => Boolean
```

Predicates determine the execution of conditionals.

```scala
val isAdmin: Predicate  = j => (j \ "user" \ "level").asOpt[Int].exists(_ > 2)
val hasEmail: Predicate = j => (j \ "user" \ "email").asOpt[String].exists(_.nonEmpty)

val adminWithoutEmail = isAdmin and hasEmail.not     // or: isAdmin && !hasEmail or any combination
val dontContact = move(__ \ "user", __ \ "noContact")

val out = when(adminWithoutEmail)(dontContact)(json)
```

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
- This design is based on my work with HMRC where I had to design a small json pipeline and some helper functions.

### Parent creation and replacement

When writing to a nested path, parents are created as objects. **If a parent already exists but isn’t an object, it’s replaced so the write can proceed.**

```scala
// setNestedPath
set(__ \ "a" \ "b", JsNumber(2))(Json.obj("a" -> 1))
// -> Right({ "a": { "b": 2 } })

// deepMergeAt
mergeAt(__ \ "a" \ "b", Json.obj("x" -> 1))(Json.obj("a" -> 1))
// -> Right({ "a": { "b": { "x": 1 } } })

```

Also note:

- Setting {} at a leaf keeps the key (it does not delete it).
- Array path segments (e.g., __ \ "arr" \ 0) are not supported by these helpers and return a JsError.

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

### In-depth example

```scala
import io.github.mghmay.transformer.Transformer
import play.api.libs.json._
import io.github.mghmay.transformer.syntax._

/* 
Our input json, the aim is to transform it in the following ways:
 - if the level of the user is higher than 2 they are an admin and "user" key should be 
   transformed to "admin"
 - user level should be removed
 - name value should be transformed from a full name to an object: {"first", "last"}
 
In order to do this we will make our own transformers using the transformers provided in
ops as building blocks. 
*/

val inJson = Json.parse(
  """
    |{
    |  "user": {
    |    "level": 4,
    |    "name": "Ada Lovelace"
    |  }
    |}
    |""".stripMargin).as[JsObject]

/* 
The nice part about transformers is that the Either[JsError, JsObject] can be taken out
into its own reusable function, in this example, splitName could be deployed wherever
we are given a fullName
*/

def splitName(full: String): Either[JsError, JsObject] =
  full.split(" ") match {
    case Array(first, last) => Right(Json.obj("first" -> first, "last" -> last))
    case _                  => Left(JsError("Expected exactly two name parts"))
  }

/* 
Now we can build a specialised transformer which gets the user name at a specific key in
the json.
*/
def userNameTransformer: Transformer = {
  json =>
    (json \ "user" \ "name").validate[String] match {
      case JsSuccess(name, _) =>
        /*
         We can use the building blocks of the split name function and the set method to
         build a result
         */
        for {
          sName  <- splitName(name)
          result <- set(__ \ "user" \ "name", sName)(json)
        } yield result
      case JsError(e)         => Left(JsError(e))
    }
}

/*
We also want an easy way to transform a user into an admin if their level is higher than 2.
Here is the transformer we can supply into a when transformer
 */
def adminTransformer: Transformer = {
  json =>
    for {
      // First remove the level
      j1 <- pruneGentle(__ \ "user" \ "level")(json)
      // Then move all the rest to admin key 
      j2 <- move(__ \ "user", __ \ "admin")(j1)
    } yield j2
}

/*
In order to find out if a user is an admin or not we need a way to check the level, we build
a Predicate which takes json and checks the user's level.
 */
def isAdmin: Predicate = j => (j \ "user" \ "level").asOpt[Int].exists(_ > 2)

/*
Our final transformation is nice and simple, firstly we transform the user's name. We need to
ensure that it is changed before we transform the user key into admin key. Then we can safely
check if the user is an admin and if they are, transform the "user" key to "admin"
 */
val transformed = for {
  j1 <- userNameTransformer(inJson)
  j2 <- when(isAdmin)(adminTransformer)(j1)
} yield j2

"""
    | transformed:
    | {
    |   "admin": {
    |     "name": {
    |       "first": "Ada",
    |       "last": "Lovelace"
    |     }
    |   }
    | }
    |"""

```

---

## Testing

- Uses `either.toOption.getOrElse(fail("..."))` for success paths, and `either.swap.toOption.getOrElse(fail("..."))` for error paths.
- Asserts failures by checking `JsError` anchors (paths) and messages.
- Builder output equality: both fluent and direct compositions create equal outputs.

### Test coverage

To run test coverage:

```scala
 sbt clean coverage test coverageReport
```

Test coverage is enforced at 100%, both branch and statement. PR's can't be merged if less than 100% code coverage.
  Anything that can't be covered in a test should be marked with `$COVERAGE-OFF$` and then `$COVERAGE-ON$`, a reason as to why should be provided.

Example: 
```scala
// $COVERAGE-OFF$
// Unreachable: IdxPathNode is filtered by the `hasArraySeg` pre-check above.
// This is just a defensive fallback.
current
// $COVERAGE-ON$
```

---

## To-dos

- Filter out non-object intermediary values (enforce stricter parent types)
- Add support for array paths (`IdxPathNode`)
- Add transformation combinators (e.g. `copyPath`, `renamePath`, batch moves)
- Add a way to deal with enums and perhaps map them
- For very large pipelines, the current foldLeft approach creates intermediate functions. For extreme performance, it could be compiled to a single function
- Transformer could return non-blocking errors that get collected in a sequence similar to cats `Validated`
- Add further conditionals, `unless`, `whenAll`, `whenAny`, `choose` etc.

---

## License

MIT © 2025 Mathew May


