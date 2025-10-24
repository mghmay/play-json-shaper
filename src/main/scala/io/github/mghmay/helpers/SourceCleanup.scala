/*
 * Copyright 2025 Mathew May
 *
 * SPDX-License-Identifier: MIT
 */

package io.github.mghmay.helpers

/** ADT for source-side cleanup policy when moving a node. */
sealed trait SourceCleanup
object SourceCleanup {
  case object Aggressive extends SourceCleanup
  case object Tombstone  extends SourceCleanup
}
