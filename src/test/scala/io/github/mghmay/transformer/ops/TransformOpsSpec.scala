package io.github.mghmay.transformer.ops

import io.github.mghmay.transformer.api.JsonTransform
import io.github.mghmay.transformer.syntax._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class TransformOpsSpec extends AnyFreeSpec with Matchers {

  "TransformOps" - {

    "move: moves scalar, and maintains destination values, aggressively prunes source" in {
      val in  = Json.parse("""{ "a": { "b": { "c": 1 } }, "x": { "keep": true } }""").as[JsObject]
      val out = TransformOps.move(__ \ "a" \ "b", __ \ "x").apply(in).toOption.getOrElse(
        fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "x": { "keep": true, "c": 1 } }""")
    }

    "move: moving an object deep-merges at destination and preserves unrelated fields" in {
      val in = Json.parse(
        """
          |{
          |  "src": { "obj": { "moved": 1 } },
          |  "x":   { "keep": true, "moved": 999 }
          |}
        """.stripMargin
      ).as[JsObject]

      val out = TransformOps.move(__ \ "src" \ "obj", __ \ "x").apply(in).toOption.getOrElse(
        fail("Expected successful transformation"))

      (out \ "x" \ "keep").as[Boolean] mustBe true
      (out \ "x" \ "moved").as[Int] mustBe 1
      (out \ "src").toOption mustBe None
    }

    "move: moving an empty object keeps {} at destination (no leaf removal on move)" in {
      val in  = Json.parse("""{ "a": { "b": {} } }""").as[JsObject]
      val out = TransformOps.move(__ \ "a" \ "b", __ \ "x").apply(in).toOption.getOrElse(
        fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "x": {} }""")
    }

    "move: fails when source missing or not unique" in {
      val inMissing = Json.parse("""{ "a": 1 }""").as[JsObject]
      val left1     = TransformOps.move(__ \ "missing", __ \ "x").apply(inMissing).swap.getOrElse(
        fail("Expected successful transformation"))
      left1.errors.head._1 mustBe (__ \ "missing")

      val inAmbig = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]
      val left2   = TransformOps.move(__ \ "arr" \ "b", __ \ "x").apply(inAmbig).swap.getOrElse(
        fail("Expected successful transformation"))
      left2.errors.head._1 mustBe (__ \ "arr" \ "b")
    }

    "copy: keeps source intact and writes destination; descendant destination allowed" in {
      val in1  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out1 = TransformOps.copy(__ \ "a" \ "b", __ \ "x")(in1).toOption.getOrElse(
        fail("Expected successful transformation"))
      out1 mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")

      val in2  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val out2 = TransformOps.copy(__ \ "a", __ \ "a" \ "copy")(in2).toOption.getOrElse(
        fail("Expected successful transformation"))
      out2 mustBe Json.parse("""{ "a": { "b": 1, "copy": { "b": 1 } } }""")
    }

    "set: sets a value; setting empty object at leaf keeps the key" in {
      val in  = Json.parse("""{ "a": { "b": 1 }, "z": 0 }""").as[JsObject]
      val out = TransformOps.set(__ \ "a", Json.obj())(in).toOption.getOrElse(
        fail("Expected successful transformation"))

      out mustBe Json.parse("""{ "a": { }, "z": 0 }""")
    }

    "mergeAt: deep merges and creates parents; fails on array path segments" in {
      val in  = Json.parse("""{ "ctx": { "env": "dev" } }""").as[JsObject]
      val out = TransformOps.mergeAt(__ \ "ctx", Json.obj("version" -> 3))(in).toOption.getOrElse(
        fail("Expected successful transformation"))

      (out \ "ctx" \ "env").as[String] mustBe "dev"
      (out \ "ctx" \ "version").as[Int] mustBe 3

      val in2  = Json.parse("""{ "a": [ { "b": 1 } ] }""").as[JsObject]
      val left = TransformOps.mergeAt(__ \ "a" \ 0 \ "b", Json.obj("x" -> 2))(in2).swap.getOrElse(
        fail("Expected successful transformation"))
      left.errors.head._2.head.message.toLowerCase must include("arrays not supported")
    }

    "pruneAggressive: removes target and empties parents; errors bubble out" in {
      val inGood = Json.parse("""{ "a": { "b": { "c": 1 } } }""").as[JsObject]
      val out    = TransformOps.pruneAggressive(__ \ "a" \ "b" \ "c")(inGood).toOption.getOrElse(
        fail("Expected successful transformation"))
      out mustBe Json.obj()
    }

    "pruneAggressive: fails when parent is not an object" in {
      val inBad = Json.parse("""{ "a": { "b": 1 }, "arr": [] }""").as[JsObject]
      val left  = TransformOps.pruneAggressive(__ \ "arr" \ 0)(inBad).swap.getOrElse(
        fail("Expected successful transformation"))
      left.errors.head._2.head.message must include("prune: expected object at 'arr'")
    }

    "pruneAggressive: fails when path starts with an array segment (unsupported)" in {
      val inBadRoot = Json.parse("""{ "a": { "b": 1 }, "arr": [] }""").as[JsObject]
      val left2     = TransformOps.pruneAggressive(__ \ 0)(inBadRoot).swap.getOrElse(
        fail("Expected successful transformation"))
      left2.errors.head._2.head.message.toLowerCase must include("arrays not supported")
    }

    "pruneGentle: removes only target; keeps parents; errors bubble out" in {
      val inGood = Json.parse("""{ "a": { "b": { "c": 1 } } }""").as[JsObject]
      val out    = TransformOps.pruneGentle(__ \ "a" \ "b" \ "c")(inGood).toOption.getOrElse(
        fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "a": { "b": { } } }""")

      val inBad = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
      val left  = TransformOps.pruneGentle(__ \ "a" \ "missing")(inBad).swap.getOrElse(
        fail("Expected successful transformation"))
      left.errors.head._2.head.message.toLowerCase must include("path not found")
    }

    "rename: sugar for aggressive move — same observable output" in {
      val in   = Json.parse("""{ "first": "v" }""").as[JsObject]
      val viaR = TransformOps.rename(__ \ "first", __ \ "second")(in)
      val viaM = TransformOps.move(__ \ "first", __ \ "second")(in)

      viaR mustBe viaM
      viaR.toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.parse(
        """{ "second": "v" }""")
    }

    "mapAt: modifies scalar via validator; propagates vf error anchored at absolute path" in {
      val inOk  = Json.parse("""{ "x": 10 }""").as[JsObject]
      val outOk =
        TransformOps.mapAt(__ \ "x")(v => v.validate[Int].map(i => JsNumber(i + 5)))(inOk).toOption.getOrElse(
          fail("Expected successful transformation"))
      (outOk \ "x").as[Int] mustBe 15

      val inBad = Json.parse("""{ "x": "not-int" }""").as[JsObject]
      val left  = TransformOps.mapAt(__ \ "x")(v => v.validate[Int].map(i => JsNumber(i + 1)))(
        inBad).swap.getOrElse(fail("Expected successful transformation"))
      left.errors.head._1 mustBe (__ \ "x")
    }

    "ops compose in a for-comprehension" in {
      val in = Json.parse("""{ "a": { "name": "Ada" } }""").as[JsObject]

      val res = for {
        j1 <- TransformOps.move(__ \ "a" \ "name", __ \ "person" \ "name")(in)
        j2 <- TransformOps.mapAt(__ \ "person" \ "name")(v =>
                v.validate[String].map(s => JsString(s.reverse)))(j1)
        j3 <- TransformOps.mergeAt(__ \ "meta", Json.obj("ok" -> true))(j2)
      } yield j3

      res.toOption.getOrElse(fail("Expected successful transformation")) mustBe Json.parse(
        """{ "person": { "name": "adA" }, "meta": { "ok": true } }"""
      )
    }

    "for-comprehension equals fluent pipeline" in {
      val in = Json.parse("""{ "a": { "name": "Ada" } }""").as[JsObject]

      val viaFor = for {
        j1 <- move(__ \ "a" \ "name", __ \ "person" \ "name")(in)
        j2 <- mapAt(__ \ "person" \ "name")(v =>
                v.validate[String].map(n => JsString(n.reverse)))(j1)
        j3 <- mergeAt(__ \ "meta", Json.obj("ok" -> true))(j2)
      } yield j3

      val viaFluent = JsonTransform
        .move(__ \ "a" \ "name", __ \ "person" \ "name")
        .mapAt(__ \ "person" \ "name")(v => v.validate[String].map(n => JsString(n.reverse)))
        .mergeAt(__ \ "meta", Json.obj("ok" -> true))
        .run(in)

      viaFor mustBe viaFluent
      val viaForOut    = viaFor.toOption.getOrElse(fail("Expected successful transformation"))
      val viaFluentOut = viaFluent.toOption.getOrElse(fail("Expected successful transformation"))

      (viaForOut \ "person" \ "name").as[String] mustBe "adA"
      (viaFluentOut \ "meta" \ "ok").as[Boolean] mustBe true
    }
  }
}
