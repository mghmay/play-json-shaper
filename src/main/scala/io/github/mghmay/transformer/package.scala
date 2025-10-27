/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay

import play.api.libs.json.{JsError, JsObject}

package object transformer {

  /** A function that transforms a JsObject, returning either a transformed object or a JsError */
  type Transformer = JsObject => Either[JsError, JsObject]
  val identity: Transformer = Right(_)

  type Predicate = JsObject => Boolean
  // Try push to main
}
