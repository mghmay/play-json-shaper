/*
 * Copyright 2025
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.shaper

import io.github.mghmay.shaper.JsonHelpers.SourceCleanup
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class ShaperSpec extends AnyFreeSpec with Matchers with JsonHelpers {

  import Shaper._

  "Shaper (public API)" - {

    "start.run with no steps returns the input unchanged" in {
      val in  = Json.obj("a" -> 1, "b" -> Json.obj())
      val out = Shaper.start.run(in)
      out mustBe Right(in)
    }

    "fluent pipeline: move, mapAt, mergeAt, pruneGentle, pruneAgressive, copy" in {
      val in =
        Json.parse(
          """{ "old": "hello", "ctx": { "env": "dev" }, "keep": { "delete": {} }, "delete": { "delete": {} } }""").as[
          JsObject]

      val out = Shaper
        .start
        .move(__ \ "old", __ \ "new")                                                 // create new if doesn't exist, defaults to prune empty
        .mapAt(__ \ "new")(v => v.validate[String].map(s => JsString(s.toUpperCase))) // transform at new key
        .mergeAt(__ \ "ctx", Json.obj("version" -> 7)) // insert new json at
        .pruneGentle(__ \ "keep" \ "delete")                                          // prune but keep empty parent
        .pruneAggressive(__ \ "delete" \ "delete")                                    // prune and delete empty parent
        .copy(__ \ "ctx", __ \ "copied")
        .run(in)
        .toOption
        .get

      out mustBe Json.parse(
        """{ "ctx": { "env" : "dev", "version": 7 }, "keep": {}, "new": "HELLO", "copied": { "env" : "dev", "version": 7 } }""".stripMargin
      )
    }

    "standalone steps in a for-comprehension yield same result as fluent pipeline" in {
      val in = Json.parse("""{ "a": { "name": "Ada" } }""").as[JsObject]

      val viaFor = for {
        j1 <- Shaper.move(__ \ "a" \ "name", __ \ "person" \ "name")(in)
        j2 <- Shaper.mapAt(__ \ "person" \ "name")(v => v.validate[String].map(n => JsString(n.reverse)))(j1)
        j3 <- Shaper.mergeAt(__ \ "meta", Json.obj("ok" -> true))(j2)
      } yield j3

      val viaFluent = Shaper
        .start
        .move(__ \ "a" \ "name", __ \ "person" \ "name")
        .mapAt(__ \ "person" \ "name")(v => v.validate[String].map(n => JsString(n.reverse)))
        .mergeAt(__ \ "meta", Json.obj("ok" -> true))
        .run(in)

      viaFor mustBe viaFluent
      (viaFor.toOption.get \ "person" \ "name").as[String] mustBe "adA"
      (viaFluent.toOption.get \ "meta" \ "ok").as[Boolean] mustBe true
    }

    "pipeline.build equals pipeline(...) composition (observationally via same output)" in {
      val in = Json.obj("x" -> 1)

      val f1 = Shaper.start
        .set(__ \ "x", JsNumber(2))
        .mergeAt(__ \ "ctx", Json.obj("v" -> 3)).build
      val f2 = Shaper(Seq(
          set(__ \ "x", JsNumber(2)),
          mergeAt(__ \ "ctx", Json.obj("v" -> 3))
        ))

      f1(in) mustBe f2(in)
    }

    "andThen concatenates two pipelines left-to-right" in {
      val p1 = Shaper.start.set(__ \ "a", JsNumber(1))
      val p2 = Shaper.start.set(__ \ "b", JsNumber(2))

      val out = p1.andThen(p2).run(Json.obj()).toOption.get
      (out \ "a").as[Int] mustBe 1
      (out \ "b").as[Int] mustBe 2
    }

    "rename is sugar for move(..., Aggressive) — same observable result" in {
      val in        = Json.parse("""{ "first": "val" }""").as[JsObject]
      val viaRename = Shaper.start.rename(__ \ "first", __ \ "second").run(in)
      val viaMove   = Shaper.start.move(__ \ "first", __ \ "second", SourceCleanup.Aggressive).run(in)

      viaRename mustBe viaMove
      (viaRename.toOption.get \ "second").as[String] mustBe "val"
      (viaRename.toOption.get \ "first").toOption mustBe empty
    }

    "mapAt: validator failure surfaces as JsError and leaves input unchanged" in {
      val in = Json.parse("""{ "n": "not-a-number" }""").as[JsObject]

      val res = Shaper.start
        .mapAt(__ \ "n")(v => v.validate[Int].map(i => JsNumber(i + 1)))
        .run(in)

      res.isLeft mustBe true
      res.left.get.errors.head._1 mustBe (__ \ "n")
    }

    "short-circuit: once a step fails, subsequent steps are not applied" in {
      val in = Json.obj()

      val failing: Transformer =
        _ => Left(JsError(__ \ "oops", JsonValidationError("boom")))

      val wouldSet: Transformer =
        set(__ \ "shouldNotExist", JsBoolean(value = true))

      val res = Shaper(Seq(failing, wouldSet))(in)
      res.isLeft mustBe true

      res.left.get.errors.head._1 mustBe (__ \ "oops")
    }

    "mergeAt at root path composes with set in the same pipeline" in {
      val in = Json.obj("k" -> 1)

      val out = Shaper
        .start
        .mergeAt(__, Json.obj("x" -> 10)) // merge at root
        .set(__ \ "k", JsNumber(99))
        .run(in)
        .toOption
        .get

      (out \ "x").as[Int] mustBe 10
      (out \ "k").as[Int] mustBe 99
    }

    "public API propagates helper errors (e.g., unsupported array segment) without re-testing internals" in {
      val in  = Json.parse("""{ "a": { "b": 1 }, "arr": [] }""").as[JsObject]
      val res = Shaper.start.move(__ \ "a" \ "b", __ \ "arr" \ 0).run(in)
      res.isLeft mustBe true
      res.left.get.errors.head._2.head.message.toLowerCase must include("unsupported path")
    }
  }
}
