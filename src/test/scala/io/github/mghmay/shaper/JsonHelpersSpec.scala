/*
 * Copyright 2025 Mathew May
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.shaper

import io.github.mghmay.transformer.DefaultJsonHelpers._
import io.github.mghmay.transformer.JsonHelpers.SourceCleanup
import io.github.mghmay.transformer.JsonTransform
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

class JsonHelpersSpec extends AnyFreeSpec with Matchers {

  "JsonHelpers" - {

    "movePath" - {

      "moves a scalar value; aggressively prunes source by default" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": null }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "x": 1 }""")
      }

      "moves an object; aggressively prunes source by default" in {
        val in  = Json.parse("""{ "a": { "b": { "c": 1 } }, "x": null }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "x": { "c": 1 } }""")
      }

      "gentle mode leaves a tombstone at the exact 'from' path (scalar)" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": null }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject], cleanup = SourceCleanup.Tombstone).toOption.get
        out mustBe Json.parse("""{ "a": { "b": null }, "x": 1 }""")
      }

      "gentle mode leaves a tombstone at the exact 'from' path (deep leaf)" in {
        val in  = Json.parse("""{ "a": { "b": { "c": 1 } }, "x": null }""")
        val out = movePath(__ \ "a" \ "b" \ "c", __ \ "x", in.as[JsObject], cleanup = SourceCleanup.Tombstone).toOption.get
        out mustBe Json.parse("""{ "a": { "b": { "c": null } }, "x": 1 }""")
      }

      "preserves non-empty siblings under source parent (aggressive)" in {
        val in  = Json.parse("""{ "a": { "b": 1, "c": 2 }, "x": null }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "a": { "c": 2 }, "x": 1 }""")
      }

      "creates destination parents as needed" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""")
        val out = movePath(__ \ "a" \ "b", __ \ "dest" \ "inner", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "dest": { "inner": 1 } }""")
      }

      "overwrites an existing destination value" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": 999 }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "x": 1 }""")
      }

      "moving an empty object keeps {} at destination (aggressive by default)" in {
        val in  = Json.parse("""{ "a": { "b": {} } }""")
        val out = movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "x": {} }""")
      }

      "overlap: to is inside from (capture then cleanup then set) is well-defined" in {
        val in  = Json.parse("""{ "a": { "b": { "c": 1 } } }""")
        val out = movePath(__ \ "a", __ \ "a" \ "b" \ "d", in.as[JsObject]).toOption.get
        (out \ "a" \ "b" \ "d" \ "b" \ "c").as[Int] mustBe 1
      }

      "overlap: 'from' is inside 'to' (cleanup first prevents duplication)" in {
        val in  = Json.parse("""{ "x": { "a": { "b": 1 } } }""")
        val out = movePath(__ \ "x" \ "a" \ "b", __ \ "x", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "x": 1 }""")
      }

      "overlap + Tombstone: from is ancestor of to; write succeeds and value ends at descendant" in {
        val in = Json.parse(
          """
            |{
            |  "a": { "k": 1 }
            |}
            |""".stripMargin
        )
        val out = movePath(
          __ \ "a",
          __ \ "a" \ "b" \ "c",
          in.as[JsObject],
          cleanup = SourceCleanup.Tombstone
        ).toOption.get

        out mustBe Json.parse(
          """
            |{
            |  "a": { "b": { "c": { "k": 1 } } }
            |}
            |""".stripMargin
        )
      }

      "no-op when from == to" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""")
        val out = movePath(__ \ "a" \ "b", __ \ "a" \ "b", in.as[JsObject]).toOption.get
        out mustBe in
      }

      "fails with JsError when source path does not exist" in {
        val in   = Json.parse("""{ "a": 1 }""")
        val left = movePath(__ \ "missing", __ \ "x", in.as[JsObject]).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "missing")
        msgs.head.message must include ("movePath")
      }

      "fails with JsError when source path resolves to multiple values (ambiguous)" in {
        val in   = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ], "x": null }""")
        val left = movePath(__ \ "arr" \ "b", __ \ "x", in.as[JsObject]).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "arr" \ "b")
        msgs.head.message must include ("not unique")
      }

      "fails when destination path uses array index (unsupported in setPath)" in {
        val in   = Json.parse("""{ "a": { "b": 1 }, "x": [] }""")
        val left = movePath(__ \ "a" \ "b", __ \ "x" \ 0, in.as[JsObject]).left.get
        left.errors.head._2.head.message.toLowerCase must include ("unsupported path")
      }
    }

    "copyPath" - {

      "copy keeps source intact and writes destination" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
      }

      "copy creates destination parents as needed" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "dest" \ "inner").run(in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "dest": { "inner": 1 } }""")
      }

      "copy overwrites an existing destination value" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": 999 }""").as[JsObject]
        val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "x").run(in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
      }

      "copy fails with JsError when source path does not exist (error anchored at source)" in {
        val in  = Json.parse("""{ "a": 1 }""").as[JsObject]
        val res = JsonTransform.start.copy(__ \ "missing", __ \ "x").run(in)
        res.isLeft mustBe true
        res.left.get.errors.head._1 mustBe (__ \ "missing")
      }

      "copy is a no-op when from == to" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = JsonTransform.start.copy(__ \ "a" \ "b", __ \ "a" \ "b").run(in)
        out mustBe Right(in)
      }
    }


    "aggressivePrunePath" - {

      "removes a nested key and prunes empty parents" in {
        val in  = Json.parse("""{ "a": { "b": "value" } }""")
        val out = aggressivePrunePath(__ \ "a" \ "b", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ }""")
      }

      "removes a key but preserves non-empty parents" in {
        val in  = Json.parse("""{ "a": { "b": "value", "c": "keep" } }""")
        val out = aggressivePrunePath(__ \ "a" \ "b", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "a": { "c": "keep" } }""")
      }

      "fails when path does not exist" in {
        val in   = Json.parse("""{ "a": { "b": "value" } }""")
        val left = aggressivePrunePath(__ \ "a" \ "missing", in.as[JsObject]).left.get
        left.errors.head._2.head.message must include ("path not found")
      }
    }

    "gentlePrunePath" - {

      "removes only the node and keeps now-empty parents" in {
        val in  = Json.parse("""{ "a": { "b": { } } }""")
        val out = gentlePrunePath(__ \ "a" \ "b", in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "a": { } }""")
      }

      "fails when path does not exist or unsupported" in {
        val in   = Json.parse("""{ "a": { } }""")
        val left = gentlePrunePath(__ \ "a" \ "missing", in.as[JsObject]).left.get
        left.errors.head._2.head.message.toLowerCase must include ("path not found")
      }
    }

    "deepMergeAt" - {

      "deep merges into an existing object and creates parents as needed" in {
        val in  = Json.parse("""{ "ctx": { "env": "dev" } }""")
        val out = deepMergeAt(in.as[JsObject], __ \ "ctx", Json.obj("version" -> 3, "env" -> "prod")).toOption.get
        (out \ "ctx" \ "env").as[String] mustBe "prod"
        (out \ "ctx" \ "version").as[Int] mustBe 3
      }

      "creates parents when merging at a missing path" in {
        val in  = Json.parse("""{ }""")
        val out = deepMergeAt(in.as[JsObject], __ \ "meta" \ "build", Json.obj("id" -> 42)).toOption.get
        (out \ "meta" \ "build" \ "id").as[Int] mustBe 42
      }

      "fails with JsError when the path contains array segments (IdxPathNode)" in {
        val in   = Json.parse("""{ "a": [ { "b": 1 } ] }""")
        val left = deepMergeAt(in.as[JsObject], __ \ "a" \ 0 \ "b", Json.obj("x" -> 2)).left.get
        val (badPath, messages) = left.errors.head
        badPath mustBe (__ \ "a" \ 0 \ "b")
        messages.head.message must include("IdxPathNode")
      }
    }

    "setNestedPath" - {

      "sets a value at a nested path, creating parents" in {
        val in  = Json.parse("""{ }""")
        val out = setNestedPath(__ \ "a" \ "b" \ "c", JsString("v"), in.as[JsObject]).toOption.get
        out mustBe Json.parse("""{ "a": { "b": { "c": "v" } } }""")
      }

      "overwrites an existing value" in {
        val in  = Json.parse("""{ "a": { "b": { "c": "old" } } }""")
        val out = setNestedPath(__ \ "a" \ "b" \ "c", JsString("new"), in.as[JsObject]).toOption.get
        (out \ "a" \ "b" \ "c").as[String] mustBe "new"
      }

      "returns the input unchanged for empty JsPath (Nil)" in {
        val in  = Json.parse("""{ "a": 1 }""")
        val out = setNestedPath(JsPath(Nil), JsNumber(2), in.as[JsObject])
        out mustBe Right(in.as[JsObject])
      }
    }
  }
}
