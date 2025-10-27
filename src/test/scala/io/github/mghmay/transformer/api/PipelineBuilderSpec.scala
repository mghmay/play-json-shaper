package io.github.mghmay.transformer.api

import io.github.mghmay.helpers.SourceCleanup
import io.github.mghmay.transformer.syntax._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class PipelineBuilderSpec extends AnyFreeSpec with Matchers {

  "PipelineBuilder" - {

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
        .toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.parse(
        """{
          |  "ctx": { "env" : "dev", "version": 7 },
          |  "keep": {},
          |  "new": "HELLO",
          |  "copied": { "env" : "dev", "version": 7 }
          |}""".stripMargin
      )
    }

    "pipeline.build equals JsonTransform.apply(Seq(...)) (same output)" in {
      val in = Json.obj("x" -> 1)

      val f1 = JsonTransform
        .set(__ \ "x", JsNumber(2))
        .mergeAt(__ \ "ctx", Json.obj("v" -> 3))
        .build

      val f2 = JsonTransform(
        set(__ \ "x", JsNumber(2)),
        mergeAt(__ \ "ctx", Json.obj("v" -> 3))
      )

      f1(in) mustBe f2(in)
    }

    "andThen concatenates two pipelines left-to-right" in {
      val p1 = JsonTransform.start.set(__ \ "a", JsNumber(1))
      val p2 = JsonTransform.start.set(__ \ "b", JsNumber(2))

      val out = p1.andThen(p2).run(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": 1, "b": 2 }""")
    }

    "andThen(transformer) appends a single step" in {
      val p   = JsonTransform.start.set(__ \ "a", JsNumber(1))
      val p2  = p andThen set(__ \ "b", JsNumber(2))
      val out = p2.run(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": 1, "b": 2 }""")
    }

    "rename sugar equals move(..., Aggressive) — same observable result" in {
      val in        = Json.parse("""{ "first": "val" }""").as[JsObject]
      val viaRename = JsonTransform.start.rename(__ \ "first", __ \ "second").run(in)
      val viaMove   = JsonTransform.start.move(__ \ "first", __ \ "second", SourceCleanup.Aggressive).run(in)

      viaRename mustBe viaMove
      viaRename.toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.parse("""{ "second": "val" }""")
    }

    "copy via builder keeps source intact and writes destination" in {
      val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
    }

    "public API errors flow through builder (unsupported array segment)" in {
      val in  = Json.parse("""{ "a": { "b": 1 }, "arr": [] }""").as[JsObject]
      val res = JsonTransform.start.move(__ \ "a" \ "b", __ \ "arr" \ 0).run(in)
      res.isLeft mustBe true
      val out = res.swap.getOrElse(fail("Expected successful transformation"))
        out.errors.head._2.head.message.toLowerCase must include("unsupported path")
    }

    "conditionals: when / ifExists / ifMissing" in {
      val in  = Json.parse("""{ "n": 2, "old": "x", "ctx": { "env": "dev" } }""").as[JsObject]

      val out = JsonTransform.start
        .when(j => (j \ "n").asOpt[Int].exists(_ > 1))(set(__ \ "flag", JsBoolean(true)))
        .ifExists(__ \ "old")(move(__ \ "old", __ \ "new"))
        .ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))
        .run(in).toOption.getOrElse(fail("Expected successful transformation"))

      out mustBe Json.parse(
        """{ "n": 2, "new": "x", "flag": true, "ctx": { "env": "dev", "version": 1 } }"""
      )
    }
  }
}
