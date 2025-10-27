package io.github.mghmay.transformer.ops

import io.github.mghmay.transformer.Transformer
import io.github.mghmay.transformer.syntax._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

final class ConditionalOpsSpec extends AnyFreeSpec with Matchers {

  "ConditionalOps" - {

    "when: applies step only when predicate is true" in {
      val in  = Json.obj("n" -> 2)
      val step: Transformer = set(__ \ "flag", JsBoolean(true))

      val out1 = ConditionalOps.when(j => (j \ "n").asOpt[Int].exists(_ > 1))(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      out1 mustBe Json.obj("n" -> 2, "flag" -> true)

      val out2 = ConditionalOps.when(j => (j \ "n").asOpt[Int].exists(_ > 10))(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      out2 mustBe in
    }

    "when: does not execute step when false (even if step would fail)" in {
      val in = Json.obj("a" -> 1)
      val failing: Transformer = _ => Left(JsError(__ \ "shouldNot", JsonValidationError("should not run")))
      val res = ConditionalOps.when(_ => false)(failing)(in)
      res mustBe Right(in)
    }

    "ifExists: runs step only when path resolves to a single value" in {
      val in1  = Json.parse("""{ "old": "x", "other": 1 }""").as[JsObject]
      val step = TransformOps.move(__ \ "old", __ \ "new")

      val out1 = ConditionalOps.ifExists(__ \ "old")(step)(in1).toOption.getOrElse(fail("Expected successful transformation"))
      out1 mustBe Json.parse("""{ "new": "x", "other": 1 }""")

      val in2  = Json.parse("""{ "other": 1 }""").as[JsObject]
      val out2 = ConditionalOps.ifExists(__ \ "old")(step)(in2).toOption.getOrElse(fail("Expected successful transformation"))
      out2 mustBe in2
    }

    "ifExists: path ambiguous (multiple matches) is treated as not existing → no-op" in {
      val in   = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]
      val step = TransformOps.set(__ \ "touched", JsBoolean(true))

      val out = ConditionalOps.ifExists(__ \ "arr" \ "b")(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe in
    }

    "ifMissing: runs step when path is absent" in {
      val in   = Json.parse("""{ "ctx": { "env": "dev" } }""").as[JsObject]
      val step = TransformOps.mergeAt(__ \ "ctx", Json.obj("version" -> 1))

      val out = ConditionalOps.ifMissing(__ \ "ctx" \ "version")(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe Json.parse("""{ "ctx": { "env": "dev", "version": 1 } }""")
    }

    "ifMissing: path exists → no-op" in {
      val in   = Json.parse("""{ "ctx": { "env": "dev", "version": 2 } }""").as[JsObject]
      val step = TransformOps.set(__ \ "shouldNot", JsBoolean(true))

      val out = ConditionalOps.ifMissing(__ \ "ctx" \ "version")(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      out mustBe in
    }

    "ifMissing: ambiguous (multiple matches) counts as missing → step runs" in {
      val in   = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]
      val step = TransformOps.set(__ \ "added", JsString("ran"))

      val out = ConditionalOps.ifMissing(__ \ "arr" \ "b")(step)(in).toOption.getOrElse(fail("Expected successful transformation"))
      (out \ "added").as[String] mustBe "ran"
    }
  }
}
