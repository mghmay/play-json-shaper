/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.transformer

import io.github.mghmay.helpers.SourceCleanup
import io.github.mghmay.transformer.api.JsonTransform
import io.github.mghmay.transformer.syntax._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class SyntaxSpec extends AnyFreeSpec with Matchers {

  "syntax wrappers delegate to TransformOps" in {
    val in0 = Json.parse("""{ "a": { "b": 1 }, "ctx": { "env": "dev" } }""").as[JsObject]

    val outCopy = syntax.copy(__ \ "a" \ "b", __ \ "x")(in0).toOption.getOrElse(fail("Expected successful transformation"))
    (outCopy \ "x").as[Int] mustBe 1
    (outCopy \ "a" \ "b").as[Int] mustBe 1

    val outSet = syntax.set(__ \ "k", JsNumber(42))(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
    (outSet \ "k").as[Int] mustBe 42

    val outMerge = syntax.mergeAt(__ \ "ctx", Json.obj("version" -> 3))(in0).toOption.getOrElse(fail("Expected successful transformation"))
    (outMerge \ "ctx" \ "env").as[String] mustBe "dev"
    (outMerge \ "ctx" \ "version").as[Int] mustBe 3

    val outPruneA = syntax.pruneAggressive(__ \ "a" \ "b")(in0).toOption.getOrElse(fail("Expected successful transformation"))
    (outPruneA \ "a").toOption mustBe None

    val inG   = Json.parse("""{ "p": { "q": { "r": 1 } } }""").as[JsObject]
    val outPG = syntax.pruneGentle(__ \ "p" \ "q" \ "r")(inG).toOption.getOrElse(fail("Expected successful transformation"))
    outPG mustBe Json.parse("""{ "p": { "q": { } } }""")

    val inR  = Json.parse("""{ "old": "v" }""").as[JsObject]
    val outR = syntax.rename(__ \ "old", __ \ "nu")(inR).toOption.getOrElse(fail("Expected successful transformation"))
    outR mustBe Json.obj("nu" -> "v")

    val inM  = Json.parse("""{ "x": 10 }""").as[JsObject]
    val outM = syntax.mapAt(__ \ "x")(v => v.validate[Int].map(i => JsNumber(i + 1)))(inM).toOption.getOrElse(fail("Expected successful transformation"))
    (outM \ "x").as[Int] mustBe 11

    syntax.when(j => (j \ "x").asOpt[Int].contains(1))(set(__ \ "flag", JsBoolean(true)))(Json.obj("x" -> 1))
      .toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.obj("x" -> 1, "flag" -> true)

    syntax.ifExists(__ \ "old")(move(__ \ "old", __ \ "new"))(
      Json.obj("old" -> "x")).toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.obj("new" -> "x")

    syntax.ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))(
      Json.obj("ctx" -> Json.obj("env" -> "dev")))
      .toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.obj("ctx" -> Json.obj("env" -> "dev", "version" -> 1))

  }

  "syntax DSL" - {

    "move overloads: default uses Aggressive cleanup; explicit cleanup matches" in {
      val in = Json.parse("""{ "a": { "b": 1 }, "x": 0 }""").as[JsObject]

      val viaDefault = syntax.move(__ \ "a" \ "b", __ \ "x")(in).toOption.getOrElse(fail("Expected successful transformation"))
      val viaAgg     = syntax.move(__ \ "a" \ "b", __ \ "x", SourceCleanup.Aggressive)(in).toOption.getOrElse(fail("Expected successful transformation"))

      viaDefault mustBe viaAgg
      viaDefault mustBe Json.obj("x" -> 1)
    }

    "PipeJsonOps: json |> transformer applies the transformer" in {
      val in  = Json.obj()
      val out = in |> set(__ \ "k", JsNumber(1))
      out.toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.obj("k" -> 1)
    }

    "PipeEitherOps: chaining with |> flatMaps results; short-circuits on Left" in {
      val in                = Json.obj()
      val step1             = set(__ \ "a", JsNumber(1))
      val step2             = set(__ \ "b", JsNumber(2))
      val boom: Transformer = _ => Left(JsError(__ \ "err", JsonValidationError("boom")))

      val ok = (in |> step1) |> step2
      ok.toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.obj("a" -> 1, "b" -> 2)

      val left = (in |> step1) |> boom |> step2
      left.isLeft mustBe true
      val out = left.left.getOrElse(fail("Expected successful transformation"))
        out.errors.head._1 mustBe (__ \ "err")
    }

    "PipeTransformerOps: extension andThen composes like |> " in {
      val t1: Transformer = set(__ \ "a", JsNumber(1))
      val t2: Transformer = set(__ \ "b", JsNumber(2))

      val viaAndThen = new syntax.PipeTransformerOps(t1).andThen(t2)(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      val viaPipe    = (t1 |> t2)(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))

      viaAndThen mustBe viaPipe
    }

    "PipeTransformerOps: t1 |> t2 composes like explicit flatMap" in {
      val t1: Transformer = set(__ \ "a", JsNumber(1))
      val t2: Transformer = set(__ \ "b", JsNumber(2))

      val viaPipe               = (t1 |> t2)(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      val composed: Transformer = json => t1(json).flatMap(t2)
      val viaManual             = composed(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))

      viaPipe mustBe viaManual
      viaPipe mustBe Json.obj("a" -> 1, "b" -> 2)
    }

    "PipePipelineOps: builder |> transformer and ++ pipeline compose as expected" in {
      val p1 = JsonTransform.start.set(__ \ "a", JsNumber(1))
      val p2 = JsonTransform.start.set(__ \ "b", JsNumber(2))

      val viaPipeT = (p1 |> set(__ \ "c", JsNumber(3))).run(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      viaPipeT mustBe Json.obj("a" -> 1, "c" -> 3)

      val viaPlus = (p1 ++ p2).run(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      viaPlus mustBe Json.obj("a" -> 1, "b" -> 2)

      val viaPipeP = (p1 |> p2).run(Json.obj()).toOption.getOrElse(fail("Expected successful transformation"))
      viaPipeP mustBe viaPlus
    }

    "PredicateOps: &&, ||, unary_!, and/or/not behave equivalently" in {
      val hasA: Predicate = j => (j \ "a").asOpt[Int].isDefined
      val hasB: Predicate = j => (j \ "b").asOpt[Int].isDefined

      val objAB = Json.obj("a" -> 1, "b" -> 2).as[JsObject]
      val objA  = Json.obj("a" -> 1).as[JsObject]
      val obj0  = Json.obj().as[JsObject]

      hasA.&&(hasB)(objAB) mustBe true
      hasA.and(hasB)(objAB) mustBe true
      hasA.&&(hasB)(objA) mustBe false
      hasA.and(hasB)(objA) mustBe false

      hasA.||(hasB)(objA) mustBe true
      hasA.or(hasB)(objA) mustBe true
      hasA.||(hasB)(obj0) mustBe false
      hasA.or(hasB)(obj0) mustBe false

      (!hasA)(objA) mustBe false
      hasA.not(objA) mustBe false
      (!hasA)(obj0) mustBe true
      hasA.not(obj0) mustBe true
    }

    "mapAt, ifExists, ifMissing wrappers from syntax interop with ops" in {
      val in = Json.parse("""{ "old": "x", "ctx": { "env": "dev" } }""").as[JsObject]

      val mapped =
        syntax.mapAt(__ \ "old")(v => v.validate[String].map(s => JsString(s.toUpperCase)))(in).toOption.getOrElse(fail("Expected successful transformation"))
      (mapped \ "old").as[String] mustBe "X"

      val existsOut = syntax.ifExists(__ \ "old")(move(__ \ "old", __ \ "new"))(in).toOption.getOrElse(fail("Expected successful transformation"))
      existsOut mustBe Json.obj("new" -> "x", "ctx" -> Json.obj("env" -> "dev"))

      val missingOut =
        syntax.ifMissing(__ \ "ctx" \ "version")(mergeAt(__ \ "ctx", Json.obj("version" -> 1)))(
          in).toOption.getOrElse(fail("Expected successful transformation"))
      missingOut mustBe Json.obj("old" -> "x", "ctx" -> Json.obj("env" -> "dev", "version" -> 1))
    }
  }
}
