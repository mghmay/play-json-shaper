/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.helpers

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json._

class JsonHelpersSpec extends AnyFreeSpec with JsonHelpers with Matchers {

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
        val out =
          movePath(__ \ "a" \ "b", __ \ "x", in.as[JsObject], cleanup = SourceCleanup.Tombstone).toOption.get
        out mustBe Json.parse("""{ "a": { "b": null }, "x": 1 }""")
      }

      "gentle mode leaves a tombstone at the exact 'from' path (deep leaf)" in {
        val in  = Json.parse("""{ "a": { "b": { "c": 1 } }, "x": null }""")
        val out = movePath(__ \ "a" \ "b" \ "c", __ \ "x", in.as[JsObject],
          cleanup = SourceCleanup.Tombstone).toOption.get
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

      "maintains keys at destination after write" in {
        val in  = Json.parse("""{ "a": { "b": { "c": 1 } }, "x": { "keep": true } }""").as[JsObject]
        val out = movePath(__ \ "a" \ "b", __ \ "x", in).toOption.get
        out mustBe Json.parse("""{ "x": { "keep": true, "c": 1 } }""")

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
        val in  = Json.parse(
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
        val in              = Json.parse("""{ "a": 1 }""")
        val left            = movePath(__ \ "missing", __ \ "x", in.as[JsObject]).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "missing")
        msgs.head.message must include("movePath")
      }

      "fails with JsError when source path resolves to multiple values (ambiguous)" in {
        val in              = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ], "x": null }""")
        val left            = movePath(__ \ "arr" \ "b", __ \ "x", in.as[JsObject]).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "arr" \ "b")
        msgs.head.message must include("not unique")
      }

      "fails when destination path uses array index (unsupported in setPath)" in {
        val in   = Json.parse("""{ "a": { "b": 1 }, "x": [] }""")
        val left = movePath(__ \ "a" \ "b", __ \ "x" \ 0, in.as[JsObject]).left.get
        left.errors.head._2.head.message.toLowerCase must include("unsupported path")
      }
    }

    "copyPath" - {

      "copy keeps source intact and writes destination" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = copyPath(__ \ "a" \ "b", __ \ "x", in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
      }

      "copy creates destination parents as needed" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = copyPath(__ \ "a" \ "b", __ \ "dest" \ "inner", in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "dest": { "inner": 1 } }""")
      }

      "copy overwrites an existing destination value" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": 999 }""").as[JsObject]
        val out = copyPath(__ \ "a" \ "b", __ \ "x", in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1 }, "x": 1 }""")
      }

      "copy fails with JsError when source path does not exist (error anchored at source)" in {
        val in  = Json.parse("""{ "a": 1 }""").as[JsObject]
        val res = copyPath(__ \ "missing", __ \ "x", in)
        res.isLeft mustBe true
        res.left.get.errors.head._1 mustBe (__ \ "missing")
      }

      "copy is a no-op when from == to" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = copyPath(__ \ "a" \ "b", __ \ "a" \ "b", in)
        out mustBe Right(in)
      }

      "copy works when to is a descendant of from" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = copyPath(__ \ "a", __ \ "a" \ "copy", in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": 1, "copy": { "b": 1 } } }""")
      }

      "copy preserves source even when destination overlaps with source structure" in {
        val in  = Json.parse("""{ "data": { "value": 42 } }""").as[JsObject]
        val out = copyPath(__ \ "data", __ \ "data" \ "backup", in).toOption.get
        (out \ "data" \ "value").as[Int] mustBe 42
        (out \ "data" \ "backup" \ "value").as[Int] mustBe 42
      }
    }

    "mapAt" - {

      "applies a successful mapping function (increment int)" in {
        val in  = Json.parse("""{ "a": { "b": 1 } }""").as[JsObject]
        val out = mapAt(__ \ "a" \ "b", in) { v =>
          v.validate[Int].map(n => JsNumber(n + 1))
        }.toOption.get

        (out \ "a" \ "b").as[Int] mustBe 2
      }

      "can change type (string -> object) via validator" in {
        val in = Json.parse("""{ "user": { "name": "Jane Doe" } }""").as[JsObject]

        def splitName(v: JsValue): JsResult[JsValue] =
          v.validate[String].flatMap {
            case s if s.trim.split("\\s+").length == 2 =>
              val Array(first, last) = s.trim.split("\\s+")
              JsSuccess(Json.obj("first" -> first, "last" -> last))
            case _ =>
              JsError(Seq(JsPath() -> Seq(JsonValidationError("expected exactly two parts"))))
          }

        val out = mapAt(__ \ "user" \ "name", in)(splitName).toOption.get

        (out \ "user" \ "name" \ "first").as[String] mustBe "Jane"
        (out \ "user" \ "name" \ "last").as[String]  mustBe "Doe"
      }

      "propagates mapping failure and prefixes error paths with the target path" in {
        val in = Json.parse("""{ "a": { "name": "Jane" } }""").as[JsObject]

        val vf: JsValue => JsResult[JsValue] =
          _ => JsError(Seq((__ \ "inner") -> Seq(JsonValidationError("boom"))))

        val left = mapAt(__ \ "a" \ "name", in)(vf).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "a" \ "name" \ "inner")
        msgs.head.message must include("boom")
      }

      "fails with JsError when path does not exist (or not unique)" in {
        val in = Json.parse("""{ "a": 1 }""").as[JsObject]

        val left = mapAt(__ \ "missing", in)(v => JsSuccess(v)).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "missing")
        msgs.head.message.toLowerCase must include("not found")
      }

      "fails with JsError when path resolves to multiple values (ambiguous)" in {
        val in = Json.parse("""{ "arr": [ { "b": 1 }, { "b": 2 } ] }""").as[JsObject]

        val left = mapAt(__ \ "arr" \ "b", in)(v => JsSuccess(v)).left.get
        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "arr" \ "b")
        msgs.head.message.toLowerCase must (include("not unique") or include("not found"))
      }

      "supports mapping to JsNull (keeps the key with a null value)" in {
        val in  = Json.parse("""{ "a": { "b": 42 } }""").as[JsObject]
        val out = mapAt(__ \ "a" \ "b", in)(_ => JsSuccess(JsNull)).toOption.get

        (out \ "a" \ "b").toOption mustBe Some(JsNull)
      }

      "fails when attempting to set into an array segment (unsupported in setNestedPath)" in {
        val in = Json.parse("""{ "a": [ 1, 2, 3 ] }""").as[JsObject]

        val left = mapAt(__ \ "a" \ 0, in) { v =>
          v.validate[Int].map(n => JsNumber(n + 10))
        }.left.get

        left.errors.head._2.head.message.toLowerCase must include("unsupported path")
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
        left.errors.head._2.head.message must include("path not found")
      }

      "fails when a parent exists but is not an object" in {
        val in   = Json.parse("""{ "a": 1 }""")
        val left = aggressivePrunePath(__ \ "a" \ "b", in.as[JsObject]).left.get

        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "a" \ "b")
        msgs.head.message must include("expected object at 'a'")
      }

      "fails when a parent key is missing" in {
        val in   = Json.parse("""{ }""")
        val left = aggressivePrunePath(__ \ "a" \ "b", in.as[JsObject]).left.get

        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "a" \ "b")
        msgs.head.message.toLowerCase must include("path not found")
      }

      "fails when path starts with an array segment" in {
        val in   = Json.parse("""{ "a": [ { "b": 1 } ] }""")
        val left = aggressivePrunePath(__ \ 0, in.as[JsObject]).left.get

        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ 0)
        msgs.head.message.toLowerCase must include("arrays not supported")
      }

      "fails with JsError when given an empty JsPath" in {
        val in   = Json.parse("""{ "a": { "b": 1 } }""")
        val left = aggressivePrunePath(JsPath(Nil), in.as[JsObject]).left.get

        val (badPath, msgs) = left.errors.head
        badPath mustBe JsPath(Nil)
        msgs.head.message must include ("prune: empty path")
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
        left.errors.head._2.head.message.toLowerCase must include("path not found")
      }

      "fails when pruning an array index" in {
        val in   = Json.parse("""{ "a": [1, 2, 3] }""").as[JsObject]
        val left = gentlePrunePath(__ \ "a" \ 0, in).left.get

        val (badPath, msgs) = left.errors.head
        badPath mustBe (__ \ "a" \ 0)
        msgs.head.message.toLowerCase must include("arrays not supported")
      }

    }

    "deepMergeAt" - {

      "deep merges into an existing object and creates parents as needed" in {
        val in  = Json.parse("""{ "ctx": { "env": "dev" } }""")
        val out =
          deepMergeAt(in.as[JsObject], __ \ "ctx", Json.obj("version" -> 3, "env" -> "prod")).toOption.get
        (out \ "ctx" \ "env").as[String] mustBe "prod"
        (out \ "ctx" \ "version").as[Int] mustBe 3
      }

      "creates parents when merging at a missing path" in {
        val in  = Json.parse("""{ }""")
        val out = deepMergeAt(in.as[JsObject], __ \ "meta" \ "build", Json.obj("id" -> 42)).toOption.get
        (out \ "meta" \ "build" \ "id").as[Int] mustBe 42
      }

      "fails with JsError when the path contains array segments (IdxPathNode)" in {
        val in                  = Json.parse("""{ "a": [ { "b": 1 } ] }""")
        val left                = deepMergeAt(in.as[JsObject], __ \ "a" \ 0 \ "b", Json.obj("x" -> 2)).left.get
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

      "sets an empty object at a leaf (does not remove the key)" in {
        val in  = Json.parse("""{ "a": { "b": 1 }, "x": 9 }""").as[JsObject]
        val out = setNestedPath(__ \ "a", Json.obj(), in).toOption.get
        out mustBe Json.parse("""{ "a": { }, "x": 9 }""")
      }

      "sets an empty object at a deep path (keeps the leaf key)" in {
        val in  = Json.parse("""{ "a": { "b": { "c": "x" } } }""").as[JsObject]
        val out = setNestedPath(__ \ "a" \ "b" \ "c", Json.obj(), in).toOption.get
        out mustBe Json.parse("""{ "a": { "b": { "c": { } } } }""")
      }
    }
  }
}
