/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer.api

import io.github.mghmay.transformer.Transformer
import io.github.mghmay.transformer.syntax._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class JsonTransformSpec extends AnyFreeSpec with Matchers {

  "JsonTransform" - {

    "compose / apply: short-circuits on first failure" in {
      val in = Json.obj()

      val failing: Transformer =
        (_: JsObject) => Left(JsError(__ \ "oops", JsonValidationError("boom")))

      val wouldSet: Transformer =
        set(__ \ "shouldNotExist", JsBoolean(true))

      val res = JsonTransform(failing, wouldSet)(in)
      res.isLeft mustBe true
      val out = res.left.getOrElse(fail("Expected successful transformation"))
        out.errors.head._1 mustBe (__ \ "oops")
    }

    "apply(varargs) equals compose(Seq(...)) for observable output" in {
      val in = Json.obj("x" -> 1)

      val t1 = set(__ \ "x", JsNumber(2))
      val t2 = mergeAt(__ \ "ctx", Json.obj("v" -> 3))

      val viaApply   = JsonTransform(t1, t2)(in)
      val viaCompose = JsonTransform.compose(Seq(t1, t2))(in)

      viaApply mustBe viaCompose
    }

    "copy: keeps source intact and writes destination" in {
      val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out = JsonTransform.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
    }

    "copy: overwrites destination and leaves source unchanged" in {
      val in  = Json.parse("""{ "a": { "b": 1 }, "x": 999 }""").as[JsObject]
      val out = JsonTransform.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
    }

    "copy: destination descendant of source" in {
      val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out = JsonTransform.copy(__ \ "a", __ \ "a" \ "copy").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": 1, "copy": { "b": 1 } } }""")
    }

    "rename: moves a field at the top level" in {
      val in  = Json.obj("oldKey" -> "v")
      val out = JsonTransform.rename(__ \ "oldKey", __ \ "newKey").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.obj("newKey" -> "v")
    }

    "pruneAggressive: removes key and empties parents" in {
      val in  = Json.parse("""{ "a": { "b": { "c": 1 } } }""").as[JsObject]
      val out = JsonTransform.pruneAggressive(__ \ "a" \ "b" \ "c").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.obj()
    }

    "pruneGentle: removes key but keeps parents" in {
      val in  = Json.parse("""{ "a": { "b": { "c": 1 } } }""").as[JsObject]
      val out = JsonTransform.pruneGentle(__ \ "a" \ "b" \ "c").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": { } } }""")
    }

    "mapAt: modifies a scalar via validator" in {
      val in  = Json.parse("""{ "x": 10 }""").as[JsObject]
      val out = JsonTransform.mapAt(__ \ "x")(v => v.validate[Int].map(i => JsNumber(i + 5)))
        .run(in).toOption.getOrElse(fail("Expected successful transformation"))
      (out \ "x").as[Int] mustBe 15
    }

    "mergeAt at root composes with subsequent set" in {
      val in = Json.obj("k" -> 1)

      val out = JsonTransform
        .mergeAt(__, Json.obj("x" -> 10))
        .set(__ \ "k", JsNumber(99))
        .apply(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "k": 99, "x": 10 }""")
    }

    "when(pred): applies step only when predicate is true" in {
      val in  = Json.obj("n" -> 2)
      val out = JsonTransform
        .when(j => (j \ "n").asOpt[Int].exists(_ > 1))(set(__ \ "flag", JsBoolean(true)))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.obj("n" -> 2, "flag" -> true)
    }

    "when(pred): does not execute step when predicate is false (even if step would fail)" in {
      val in      = Json.obj("a" -> 1)
      val failing = (_: JsObject) => Left(JsError(__ \ "shouldNot", JsonValidationError("should not run")))

      val res = JsonTransform.when(_ => false)(failing).run(in)
      res mustBe Right(in)
    }

    "ifExists(path): runs step only when path resolves to a single value" in {
      val in  = Json.parse("""{ "old": "x", "other": 1 }""").as[JsObject]
      val out = JsonTransform
        .ifExists(__ \ "old")(move(__ \ "old", __ \ "new"))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "new": "x", "other": 1 }""")
    }

    "ifExists(path): ambiguous resolution counts as not existing returns no-op" in {
      val in  = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]
      val out = JsonTransform
        .ifExists(__ \ "arr" \ "b")(set(__ \ "touched", JsBoolean(true)))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe in
    }

    "ifExists(path): missing path returns no-op" in {
      val in  = Json.parse("""{ "other": 1 }""").as[JsObject]
      val out = JsonTransform
        .ifExists(__ \ "old")(set(__ \ "touched", JsBoolean(true)))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe in
    }

    "ifMissing(path): runs step when path is absent" in {
      val in  = Json.parse("""{ "ctx": { "env": "dev" } }""").as[JsObject]
      val out = JsonTransform
        .ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "ctx": { "env": "dev", "version": 1 } }""")
    }

    "ifMissing(path): path exists returns no-op" in {
      val in  = Json.parse("""{ "ctx": { "env": "dev", "version": 2 } }""").as[JsObject]
      val out = JsonTransform
        .ifMissing(__ \ "ctx" \ "version")(set(__ \ "shouldNot", JsBoolean(true)))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe in
    }

    "ifMissing(path): ambiguous resolution counts as missing step runs" in {
      val in  = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]
      val out = JsonTransform
        .ifMissing(__ \ "arr" \ "b")(set(__ \ "added", JsString("ran")))
        .run(in)
        .toOption.getOrElse(fail("Expected successful transformation"))

      (out \ "added").as[String] mustBe "ran"
    }
  }
}
