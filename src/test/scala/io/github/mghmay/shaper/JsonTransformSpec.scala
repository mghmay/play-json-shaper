/*
 * Copyright 2025
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.shaper

import io.github.mghmay.transformer.JsonHelpers.SourceCleanup
import io.github.mghmay.transformer.{DefaultJsonHelpers, JsonTransform, JsonTransformOps}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class JsonTransformSpec extends AnyFreeSpec with Matchers {

  "Transform" - {

    "start.run with no steps returns the input unchanged" in {
      val in  = Json.obj("a" -> 1, "b" -> Json.obj())
      val out = JsonTransform.start.run(in)
      out mustBe Right(in)
    }

    "fluent pipeline: move, mapAt, mergeAt, pruneGentle, pruneAggressive, copy" in {
      val in =
        Json.parse(
          """{
            |  "old": "hello",
            |  "ctx": { "env": "dev" },
            |  "keep": { "delete": {} },
            |  "delete": { "delete": {} }
            |}""".stripMargin
        ).as[JsObject]

      val out = JsonTransform
        .move(__ \ "old", __ \ "new")
        .mapAt(__ \ "new")(v => v.validate[String].map(s => JsString(s.toUpperCase)))
        .mergeAt(__ \ "ctx", Json.obj("version" -> 7))
        .pruneGentle(__ \ "keep" \ "delete")
        .pruneAggressive(__ \ "delete" \ "delete")
        .copy(__ \ "ctx", __ \ "copied")
        .run(in)
        .toOption
        .get

      out mustBe Json.parse(
        """{
          |  "ctx": { "env" : "dev", "version": 7 },
          |  "keep": {},
          |  "new": "HELLO",
          |  "copied": { "env" : "dev", "version": 7 }
          |}""".stripMargin
      )
    }

    "standalone steps in a for-comprehension yield same result as fluent pipeline" in {
      val in = Json.parse("""{ "a": { "name": "Ada" } }""").as[JsObject]

      val viaFor = for {
        j1 <- JsonTransformOps.move(__ \ "a" \ "name", __ \ "person" \ "name")(in)
        j2 <- JsonTransformOps.mapAt(__ \ "person" \ "name")(v => v.validate[String].map(n => JsString(n.reverse)))(j1)
        j3 <- JsonTransformOps.mergeAt(__ \ "meta", Json.obj("ok" -> true))(j2)
      } yield j3

      val viaFluent = JsonTransform
        .move(__ \ "a" \ "name", __ \ "person" \ "name")
        .mapAt(__ \ "person" \ "name")(v => v.validate[String].map(n => JsString(n.reverse)))
        .mergeAt(__ \ "meta", Json.obj("ok" -> true))
        .run(in)

      viaFor mustBe viaFluent
      (viaFor.toOption.get \ "person" \ "name").as[String] mustBe "adA"
      (viaFluent.toOption.get \ "meta" \ "ok").as[Boolean] mustBe true
    }

    "pipeline.build equals Shaper.apply(Seq(...)) composition (same observable output)" in {
      val in = Json.obj("x" -> 1)

      val f1 = JsonTransform
        .set(__ \ "x", JsNumber(2))
        .mergeAt(__ \ "ctx", Json.obj("v" -> 3))
        .build

      val f2 = JsonTransform(Seq(
        JsonTransformOps.set(__ \ "x", JsNumber(2)),
        JsonTransformOps.mergeAt(__ \ "ctx", Json.obj("v" -> 3))
        ))

      f1(in) mustBe f2(in)
    }

    "andThen concatenates two pipelines left-to-right" in {
      val p1 = JsonTransform.start.set(__ \ "a", JsNumber(1))
      val p2 = JsonTransform.start.set(__ \ "b", JsNumber(2))

      val out = p1.andThen(p2).run(Json.obj()).toOption.get
      out mustBe Json.parse("""{ "a": 1, "b": 2 }""")
    }

    "andThen(transformer) appends a single step" in {
      val p   = JsonTransform.start.set(__ \ "a", JsNumber(1))
      val p2  = p.andThen(JsonTransformOps.set(__ \ "b", JsNumber(2)))
      val out = p2.run(Json.obj()).toOption.get
      out mustBe Json.parse("""{ "a": 1, "b": 2 }""")
    }

    "rename is sugar for move(..., Aggressive) — same observable result" in {
      val in        = Json.parse("""{ "first": "val" }""").as[JsObject]
      val viaRename = JsonTransform.start.rename(__ \ "first", __ \ "second").run(in)
      val viaMove   = JsonTransform.start.move(__ \ "first", __ \ "second", SourceCleanup.Aggressive).run(in)

      viaRename mustBe viaMove
      viaRename.toOption.get mustBe Json.parse("""{ "second": "val" }""")
    }

    "copy keeps source intact and writes destination" in {
      val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.get
      out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
    }

    "mapAt: validator failure surfaces as JsError anchored at the absolute path" in {
      val in = Json.parse("""{ "n": "not-a-number" }""").as[JsObject]

      val res = JsonTransform.start
        .mapAt(__ \ "n")(v => v.validate[Int].map(i => JsNumber(i + 1)))
        .run(in)

      res.isLeft mustBe true
      res.left.get.errors.head._1 mustBe (__ \ "n")
    }

    "short-circuit: once a step fails, subsequent steps are not applied" in {
      val in = Json.obj()

      val failing =
        (_: JsObject) => Left(JsError(__ \ "oops", JsonValidationError("boom")))

      val wouldSet =
        JsonTransformOps.set(__ \ "shouldNotExist", JsBoolean(true))

      val res = JsonTransform(Seq(failing, wouldSet))(in)
      res.isLeft mustBe true
      res.left.get.errors.head._1 mustBe (__ \ "oops")
    }

    "mergeAt at root path composes with set in the same pipeline" in {
      val in = Json.obj("k" -> 1)

      val out = JsonTransform
        .mergeAt(__, Json.obj("x" -> 10)) // merge at root
        .set(__ \ "k", JsNumber(99))
        .apply(in)
        .toOption
        .get

      out mustBe Json.parse("""{ "k": 99, "x": 10 }""")
    }

    "public API propagates helper errors (e.g., unsupported array segment) without re-testing internals" in {
      val in  = Json.parse("""{ "a": { "b": 1 }, "arr": [] }""").as[JsObject]
      val res = JsonTransform.start.move(__ \ "a" \ "b", __ \ "arr" \ 0).run(in)
      res.isLeft mustBe true
      res.left.get.errors.head._2.head.message.toLowerCase must include("unsupported path")
    }

    "when(pred) applies step only when predicate is true" in {
      val in  = Json.obj("n" -> 2)
      val out = JsonTransform
        .when(j => (j \ "n").asOpt[Int].exists(_ > 1))(JsonTransformOps.set(__ \ "flag", JsBoolean(true)))
        .run(in)
        .toOption.get

      out mustBe Json.parse("""{ "n": 2, "flag": true }""")
    }

    "ifExists(path) runs step only when the path is present" in {
      val in  = Json.parse("""{ "old": "x", "other": 1 }""").as[JsObject]
      val out = JsonTransform
        .ifExists(__ \ "old")(JsonTransformOps.move(__ \ "old", __ \ "new"))
        .run(in)
        .toOption.get

      out mustBe Json.parse("""{ "new": "x", "other": 1 }""")
    }

    "ifMissing(path) sets default only when absent" in {
      val in  = Json.parse("""{ "ctx": { "env": "dev" } }""").as[JsObject]
      val out = JsonTransform
        .ifMissing(__ \ "ctx" \ "version")(JsonTransformOps.mergeAt(__ \ "ctx", Json.obj("version" -> 1)))
        .run(in)
        .toOption.get

      out mustBe Json.parse("""{ "ctx": { "env": "dev", "version": 1 } }""")
    }
    "empty pipeline is identity" in {
      val in = Json.obj("x" -> 1)
      JsonTransform.start.run(in) mustBe Right(in)
    }

    "composition is associative" in {
      val a = JsonTransformOps.set(__ \ "a", JsNumber(1))
      val b = JsonTransformOps.set(__ \ "b", JsNumber(2))
      val c = JsonTransformOps.set(__ \ "c", JsNumber(3))

      val left  = JsonTransform.start.andThen(JsonTransform.start.andThen(a).andThen(b)).andThen(c)
      val right = JsonTransform.start.andThen(a).andThen(JsonTransform.start.andThen(b).andThen(c))

      left.run(Json.obj()) mustBe right.run(Json.obj())
    }
  }
}
