# play-json-shaper
A lightweight toolkit for composable JSON transformations on top of Play JSON.

## Behavior Clarifications

### Self / Overlapping Moves (`movePath`)

When the **source** (`from`) and **destination** (`to`) paths overlap, the behavior is **well-defined** due to
the consistent operation order:

1. **Capture** the value at `from`.
2. **Clean up** the source, according to the selected cleanup mode:
    - **`SourceCleanup.Aggressive`** – remove the moved key and recursively prune empty parents.
    - **`SourceCleanup.Tombstone`** – replace the source node with a `null` tombstone (parents are left unchanged).
3. **Set** the captured value at `to`, creating destination parents as needed.

#### Scenarios

- **`to` is nested inside `from`**  
  The move still succeeds. The value is captured before cleanup, so it remains available for writing.  
  _Example:_ moving `a` → `a.b.c` with `Tombstone` results in `{ "a": { "b": { "c": { ...original a... } } } }`.

- **`from` is nested inside `to`**  
  The cleanup step runs first, preventing duplication. The source is cleared before the destination is written.

**Summary:** Overlapping moves are deterministic and safe.  
They guarantee capture → cleanup → write ordering, avoiding undefined or partial JSON states.

---

### Parent Replacement Behavior (`setNestedPath` and `deepMergeAt`)

Both `setNestedPath` and `deepMergeAt` automatically **create or replace** intermediate parents as needed.

- If a parent path does not exist, an empty object `{}` is created.
- If a parent exists but **is not an object** (e.g. a number, string, array, or `null`),  
  it is **replaced** with an empty object `{}` so the write can proceed.

This design favors **completeness and resilience** over strictness — writes will never silently no-op.  
However, this means that pre-existing scalar values may be replaced if they conflict with object paths.

_Example:_
```json
{ "a": 1 }
```
Setting `__ \ "a" \ "b"` to `2` produces:
```json
{ "a": { "b": 2 } }
```

---

### Array Segments (`deepMergeAt`, `setNestedPath`, and `movePath`)

Paths that include **array indices** (e.g. `__ \ "a" \ 0 \ "b"`) are **unsupported** across all core helpers.

- Any path containing an `IdxPathNode` will cause the operation to **fail fast** with:
  ```scala
  Left(JsError("...array segments (IdxPathNode) not supported..."))
  ```
- This behavior is intentional to avoid partial writes or undefined merges into array structures.

Future versions may introduce **array-aware** variants for these operations.

---

### Pruning Behavior (`aggressivePrunePath` and `gentlePrunePath`)

- **`aggressivePrunePath`** removes the specified key and **recursively deletes any now-empty parent objects**.  
  It fails if any segment is missing or unsupported.

- **`gentlePrunePath`** removes only the node itself and **retains empty parents**.  
  It also fails if the path does not exist or includes array segments.

Both methods return `Either[JsError, JsObject]` for predictable failure handling.

---

### To-dos

- Filter out non-object intermediary values (enforce stricter parent types)
- Add support for array paths (`IdxPathNode`)
- Add transformation combinators (e.g. `copyPath`, `renamePath`, batch moves)
- Add a way to deal with enums and perhaps map them
- For very large pipelines, the current foldLeft approach creates intermediate functions. For extreme performance, it could be compiled to a single function
