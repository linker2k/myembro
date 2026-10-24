# Portable agent tool contract (draft)

Base: main `7fad04a9606acff016a37169714b2e4c5b5f9539`. Inputs reviewed: feature inventory `634594a` on `agent/inventory`, and command-layer proposal `f671e1e` on `agent/command-layer` (read through git; not merged here). [tools.json](tools.json) is a catalog, not a JSON Schema for this entire document. Each `inputSchema` is a standalone Draft 2020-12 schema. This is a proposal: none of the named transport/service endpoints exists yet. `mapsTo` identifies the proposed command/service boundary; the table below identifies today's implementation to extract or wrap. Existing app behavior and gates are unchanged.

## Wire format and provider projection

The catalog uses `tools: [{name, description, inputSchema, annotations}]`. Every object, including nested objects and argument-free inputs, has `additionalProperties: false` and lists every property in `required`. Call argument-free tools with `{}`. There are no defaults, null unions, `$ref`, definitions, combinators, arbitrary maps, executable expressions, class names, or serialized project replacement inputs. Arrays have one fixed item schema. The allowed schema vocabulary is `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `minimum`, `maximum`, `pattern`. Runtime validation remains authoritative for cross-field dependencies, finite numbers, IDs, nonblank names, positive physical sizes, dynamic ranges, and output safety.

Adapters change only the envelope; the input schema is copied unchanged. Host metadata must remain available to the dispatcher even when a provider does not carry it:

| Transport | Mechanical projection |
|---|---|
| MCP tools/list | Keep `name`, `description`, `inputSchema`; map local `annotations.readOnly` to MCP `annotations.readOnlyHint`. Keep `needsUserConfirmation` and `mapsTo` in host registry or namespaced `_meta`; these are not standard MCP enforcement fields. |
| OpenAI Responses function | `{type: "function", name, description, parameters: inputSchema, strict: true}`. Chat Completions uses the corresponding nested `function` envelope. No custom host annotations inside function parameters. |
| Gemini function declaration | `{name, description, parametersJsonSchema: inputSchema}` inside `functionDeclarations`. Use the JSON Schema field, not the legacy OpenAPI `parameters` field; never send both. |

These mappings follow the official [OpenAI function-calling guide](https://developers.openai.com/api/docs/guides/function-calling), [OpenAI supported schema vocabulary](https://developers.openai.com/api/docs/guides/structured-outputs), [Gemini FunctionDeclaration reference](https://ai.google.dev/api/generate-content#FunctionDeclaration), and [MCP tool schema](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-06-18/schema.mdx). No live provider calls were made. Meta-schema validity does not certify a particular account/model/SDK accepts every declaration; keep server validation and run adapter integration tests before deployment. Do not silently strip constraints to make a provider accept them.

## Identity, units, and explicit values

All geometric distances in tool inputs have names ending in `Mm`: centered project x/y, translation deltas, picture width/height and camera pan are millimeters. `rotationDeg` is degrees, `scaleXPercent`/`scaleYPercent` are positive percentages, `scaleFactor` is dimensionless, and camera `zoom`/`previewFraction`/crop pan are dimensionless. The shared decoder converts mm to native 0.1mm units once. Mirror signs are retained from the existing object for explicit positive-percentage transforms; use `mirror_objects` for a sign change. Group transform pivot follows current UI behavior. View pan is resolved from physical mm to display pixels by PresentationService; it is not a document transform.

IDs are opaque host-resolved strings, not indices, paths or URLs. Resolve assets only from user-approved files or catalog entries, and return content hashes/revisions. The adapter must provide an OS picker/access-grant workflow for asset/destination IDs; an LLM string cannot grant filesystem/USB permission. Do not let the model label an imported design as generated or select the oracle writer profile.

| Field convention | Exact handling |
|---|---|
| `expectedRevision`, `projectRevision` | Opaque current session/snapshot token from get_project; reject stale values. Undo changes revision; revision is not undo depth. |
| `transactionId` | Client-selected stable idempotency key within one session. begin/commit retries return the same receipt; reuse with different input rejects. Each staged call also needs transport request-ID deduplication so a retry cannot duplicate a relative transform. |
| `objectId`, `newObjectId` | Explicit unique nonblank IDs on add/copy; missing/wrong-kind targets reject. Tools never rely on UI selection. |
| `fontId`, `fontRevision`, `frameId`, `machineId` | Resolve against current snapshots/catalog. Fonts must match revision and character coverage. `machineId` accepts a listed profile ID or an explicit custom model name, using the existing unknown-profile policy. |
| `frameId` for save_custom_frame | Empty string creates a frame with a host-generated ID; nonempty targets an existing frame. `overwrite` must agree with target existence. Other frame fields require a configured ID. |
| `sewout_check.projectRevision` / `.pesAssetId` | Exactly one is nonempty; other is `""`. Both supplied or both empty rejects. Source/provenance is derived by service. |
| `list_emoji.groupId` | `""` means bundled set. Nonempty query searches the whole catalog; otherwise use the group. |
| `focus_sewout_finding.findingId` | `""` clears focus; otherwise a finding from the specified current report. |
| `photo` COLOR-only choices | All fields remain explicit. In non-COLOR modes, colorCount/grayscale are inactive; preserve them as requested draft values without applying a color pipeline. Crop keeps frame aspect. Width means stitched ink width, not source bitmap width. |
| `set_view` | All desired-state fields explicit; `fit=true` resets camera and ignores supplied camera offsets/zoom. `previewFraction` in the editor resolves to progress; viewer uses seek_playback instead. Reject unsupported screen controls, such as viewer jump toggling, rather than silently adding UI capabilities. |
| `overwrite`, `discardUnsaved` | Requested behavior only; never treated as user authorization or a consent token. |

State queries return actual object IDs/order/types/transforms/text/font revisions/hoop, active revision, undo/redo eligibility, allowed font heights, configured frames, blockers, artifact hashes and operation IDs as appropriate. Exact output JSON schemas are a follow-up; input contracts must not imply an implemented dispatcher. All write jobs return a stable operation handle/result and typed failure, never success for incomplete work. No arbitrary numeric latency or size bounds are introduced here.

## Editing and authorization semantics

Read a snapshot, begin a turn at its revision, stage edit tools on a private candidate, then commit once. Preparation shares the proposed engine pipeline: full font/text regeneration (never just swapping fontId while retaining old contours), frozen resolved font assets, current geometry policy and existing P1–P8 checks. Preparation/validation failure, cancellation or stale revision publishes nothing and leaves history unchanged. Commit creates one History step only when changed; no-op preserves redo. Align/center/distribute/mirror/group transform resolve to explicit SetTransforms; add_design/add_text/add_generated_art resolve to Add. Text replacement, remove, duplicate, order, visibility, recolor and hoop map to their proposed commands. No arbitrary batch union or raw ProjectCommand escape hatch is exposed.

`needsUserConfirmation: true` applies to deletion, replacement/overwrite, restore, transfer and external sharing. It also conservatively marks mixed-mode tools that can overwrite even when a particular request asks for a new file. Consent is enforced by a trusted host UI, bound to normalized arguments, snapshot/asset hashes, destination and exact affected IDs. It is not a model-supplied boolean, annotation hint, or untrusted tool result. The remove_objects staging call obtains consent; commit_edit_turn rechecks its bound host receipt and all revision assumptions before publication. Confirmation cannot be bypassed through commit, undo/redo receipts, navigation, cancel_operation, or another service alias. Navigation that would discard a dirty document pauses for the existing Save/Discard/Cancel flow. A modified candidate invalidates a previously confirmed destructive scope.

External writes/import publication/downloads/backups/USB are not ProjectCommands and are never smuggled into an edit turn or its undo history. Only prepare_* staging can precede commit. No active edit turn while replacing/restoring documents or doing undo/redo. Destructive job retries need a host idempotency record, not re-execution. Cancellation after irreversible I/O begins may be unavailable and must report that honestly. USB tools transfer files only; no tool starts a sewing machine. Blocking output checks cannot be waived by confirmation.

The tool list inventories capabilities, not the set to advertise on every turn. A host may expose a relevant subset by context while keeping this one contract. UI-only navigation, selection, camera, playback and report focus use presentation adapters. Advanced arc layout, manual stitch-density/underlay/lock-type controls, SVG import, clone and cross-stitch are absent because the current app route does not expose them. Multi-position output remains unavailable until the confirmed-position host callback is integrated; a coverage frame must not be treated as a larger single-PES sewing area.

## Code authorities and mapping table

Paths in this table are relative to `app/src/main/kotlin/io/github/linker2k/myembro/`, except `Project.kt`, `History.kt`, `EditOperations.kt`, `SewoutLimits.kt` and engine classes, which are under `engine/src/main/kotlin/io/github/linker2k/myembro/engine/`. UI source basenames are resolved by the inventory's full links at `agent/inventory:634594a`; current service names below remain proposed. Numeric schema bounds have additional exact source links after this table. Business constraints stay in the shared service even when the schema cannot express them.

| Tool | Proposed command/service | Existing code authority / constraints |
|---|---|---|
| `get_project` | `ProjectSession.getSnapshot` | Proposed snapshot service; Project.kt:26; EditorSession.kt:12; command-layer state.json contract. |
| `list_fonts` | `FontService.list` | FontLibraryScreen.kt:105 and 211; LetteringHeightLimits.kt:18 and 54. Filters and font-specific ranges are returned, never inferred by the model. |
| `list_builtin_designs` | `DesignCatalog.listBuiltins` | DesignPickerViewModel.kt:47; BundledDesigns.files; HomeViewModel.kt:58. No caller-supplied filesystem paths. |
| `list_designs` | `DesignLibraryService.list` | LibraryViewModel.kt:68–99; RECENT uses existing seven-day cutoff. No invented pagination limits. |
| `get_settings` | `SettingsService.get` | SettingsViewModel.kt:31–54; MachineProfile.kt:106; RenderData.kt:103. Return valid profile/frame IDs and units. |
| `list_usb_drives` | `UsbService.listDrives` | LibraryViewModel.kt:193; UsbExporter / DirectUsbAccess. Discovery may access hardware but does not write designs. |
| `get_usb_history` | `UsbService.history` | UsbTransferDialogs.kt:27; LibraryViewModel.kt:136. Existing store retains twenty records; no new schema limit. |
| `get_operation` | `OperationService.get` | Proposed handle wrapper around existing ViewModel progress/jobs; no current public operation-ID API. |
| `sewout_check` | `SewoutService.check` | SewoutCheck.checkPes; ViewerViewModel.kt:123; SewoutLimits.kt:9–31. Exactly one of projectRevision/pesAssetId is nonempty, checked by service; no oneOf needed. |
| `begin_edit_turn` | `ProjectSession.beginTurn` | Proposed command-layer transaction/controller contract: explicit IDs, revision guard, retry receipt. No numeric bounds. |
| `commit_edit_turn` | `ProjectSession.commitTurn` | History.kt:20; proposed serialized compare-and-commit. Requires bound host consent for every staged destructive action; never bypasses consent at commit. |
| `cancel_edit_turn` | `ProjectSession.cancelTurn` | Proposed private-candidate transaction contract; not a published edit. |
| `add_design` | `ProjectPreparation.addDesign` | EditorViewModel.kt:467; DesignObjectImport.kt; Project.kt:28. Asset resolver owns bytes; explicit unique objectId, no raw paths. |
| `add_text` | `ProjectPreparation.addText` | LetteringScreen.kt:179–201; LetteringHeightLimits.kt:18; EditorAppearance.kt:33. Height finite positive, global 60mm cap, intersect font/hoop ranges; RGB uses existing six-digit parser. |
| `replace_text` | `ProjectPreparation.replaceText` | EditorActions.kt:9; LetteringRenderer.kt:206; same height/color authorities as add_text. Full re-preparation mandatory; stale/missing target rejected. |
| `set_object_transforms` | `ProjectCommand.SetTransforms` | EditorSelection.kt:102–111; Project.kt:139–149. UI x/y ±10000mm, scale 5–2000%; degrees finite and normalized by existing policy; mirror signs retained. |
| `transform_objects` | `ProjectPreparation.transformGroup` | EditorSelection.kt:17–44; EditorGeometry.kt:29–34. Shared existing clamp/validation resolves final transforms; no new factor range invented in schema. Reject nonpositive scaleFactor. |
| `mirror_objects` | `ProjectPreparation.mirror` | EditorViewModel.kt:438; Project.mirrorX. No new geometry limits. |
| `center_objects` | `ProjectPreparation.center` | EditorViewModel.kt:440–446; actual placed bounds, not preview rectangles. |
| `align_objects` | `ProjectPreparation.align` | EditOperations.kt:35–59; EditorWorkspace.kt:188. Existing multi-selection path; no new bounds. |
| `distribute_objects` | `ProjectPreparation.distribute` | EditOperations.kt:62–86; fewer than three resolved objects leaves existing helper unchanged; command service must report no-op, not false success. |
| `duplicate_object` | `ProjectCommand.Duplicate` | Project.kt:42; EditorSelection.kt:102. Explicit final transform and ID prevent random replay; no implicit five-mm offset added by this low-level command. |
| `reorder_object` | `ProjectCommand.Reorder` | Project.kt:35; command-layer proposal rejects index outside current list instead of compatibility-helper clamping. Dynamic list bounds, no fixed schema range. |
| `set_object_visibility` | `ProjectCommand.SetVisible` | Project.withVisible / EditorViewModel.kt:462. Missing IDs rejected by proposed reducer. |
| `recolor_object` | `ProjectCommand.Recolor` | EditorAppearance.kt:22–37; TextObject accepts base thread at index zero; stitch-object indices validated against actual threads. Clears stale source bytes as today. |
| `remove_objects` | `ProjectCommand.Remove` | EditorViewModel.kt:447; proposed reducer rejects missing IDs. Agent confirmation required even though active-session undo can restore. |
| `set_project_frame` | `ProjectPreparation.setHoop` | SettingsViewModel.kt:52; EditorViewModel.kt:340; HoopSpec/SewoutLimits. Resolve configured dimensions; unfinished compositions may retain export blockers. |
| `add_generated_art` | `ProjectPreparation.addGeneratedArt` | MainActivity.kt:155–184; generated result handle freezes inputs/provenance. Result validation/placement remains shared. |
| `undo` | `ProjectSession.undo` | History.kt:35; no open turn/gesture; session revision guard. Shares UI history, never a separate agent stack. |
| `redo` | `ProjectSession.redo` | History.kt:43; no open turn/gesture; equal-state no-op preserves redo according to shared controller proposal. |
| `new_project` | `DocumentService.newProject` | EditorViewModel.kt:289; dirty replacement may discard work. Always confirm this tool conservatively; discardUnsaved is intent, not proof of consent. |
| `open_project` | `DocumentService.openProject` | EditorViewModel.kt:530; EditorDocuments.kt:30. Read succeeds before session replacement; history reset; confirm restore/replacement. |
| `recover_project` | `DocumentService.restoreDraft` | EditorViewModel.kt:246; editor recovery replaces active document session. |
| `discard_recovery` | `DocumentService.discardDraft` | EditorViewModel.kt:252. No draft-deletion undo. |
| `prepare_import` | `ImportService.prepare` | DesignImportLimits in DesignFileImporter.kt:15; BatchDesignImporter.prepare. Stages files only; does not publish library replacements. Existing 16/64MiB, 4096-entry, 1000-design limits remain server-side. |
| `commit_import` | `ImportService.commit` | BatchDesignImporter.kt:114; ImportDecisions.kt:23. REPLACE overwrites; conservative confirmation for this mixed-mode write tool. Bind operation to source hashes. |
| `set_design_favorite` | `DesignLibraryService.setFavorite` | LibraryViewModel.kt:286; desired-state adapter around current toggle, avoiding retry inversion. |
| `delete_designs` | `DesignLibraryService.delete` | LibraryViewModel.kt:298; DesignLibrary.delete. No trash/undo; selected UI state must not select hidden targets implicitly. |
| `download_font` | `FontService.download` | FontRepository.kt:164 and 408; catalog-only HTTPS/hash validation, existing download-size limits; no arbitrary URL input. |
| `import_font` | `FontService.importUserFont` | FontLibraryScreen.kt:491; FontRepository.kt:258 and 465. Existing personal-font size/type checks and explicit license acknowledgment. |
| `delete_font` | `FontService.delete` | FontRepository.kt:207 and 317; bundled fonts protected; personal originals needed to reinstall. |
| `set_font_favorite` | `FontService.setFavorite` | SettingsViewModel.kt:76; desired-state adapter around toggleFavoriteFont. |
| `get_font_license` | `FontService.license` | FontRepository.kt:373; license read does not silently download. |
| `prepare_photo` | `PhotoService.prepare` | Approved app-picked attachmentId only; NOT_IMPLEMENTED in the dispatcher. PhotoViewModel.kt:57,101,139; PhotoCrop.kt:12–54; PhotoScreen.kt:129,136. Actual ink-width interval is dynamic; return allowed width and reject unsupported requests rather than inventing a static minimum. Crop uses existing frame aspect. |
| `list_emoji` | `EmojiService.list` | EmojiViewModel.kt:82–93; empty groupId means bundled set; nonempty query searches catalog. Catalog group ID must resolve. |
| `prepare_emoji` | `EmojiService.prepare` | EmojiViewModel.kt:109–141; EmbroiderySizeSlider.kt:40. Dynamic pictureWidthRange and native hoop endpoint bound; no new global width interval. Catalog-only pinned data. |
| `prepare_pes` | `ExportService.preparePes` | Project.flatten → existing preflight → normal PesWriter → PesReader/SewoutCheck; command-layer export service proposal. No arbitrary filesystem destination or oracle profile; artifacts created only after checks. |
| `save_project` | `DocumentService.saveProject` | EditorViewModel.kt:547; EditorSaveNames.kt:10. Sanitized stem nonblank, up to existing 80-character limit; actual destination conflict rechecked. Confirmation conservatively required even with overwrite=false. |
| `save_pes` | `ExportService.savePes` | ViewerViewModel.kt:198; EditorSaveNames.kt:writeNamedDesign; destination may overwrite. Existing imported PES preserved; edited/generated data uses normal checked writer. |
| `share_pes` | `ExportService.sharePes` | ViewerViewModel.kt:225; MainActivity.kt:94. External disclosure cannot be undone; OS chooser remains user-controlled. |
| `send_usb` | `UsbService.export` | LibraryViewModel.kt:318; UsbExporter/DirectUsbExporter; runtime access grants, machine/hoop/output checks. No physical-sewing command. |
| `resume_usb_after_delete` | `UsbService.resumeAfterDelete` | LibraryViewModel.kt:437; DirectUsbExporter.SpacePlan.canProceed/resume. Bind exact destination and candidate names/hashes to host confirmation; no broad delete-all flag. |
| `share_diagnostics` | `UsbService.shareDiagnostics` | UsbTransferDialogs.kt:127; file names/device diagnostics may be disclosed. |
| `create_backup` | `BackupService.write` | BackupViewModel.kt:32; BackupArchive.kt:15–17 and 36. Existing file/size limits stay in service; external backup may disclose private content or overwrite. |
| `prepare_restore` | `BackupService.prepareRestore` | BackupViewModel.kt:47; BackupArchive.read; stages and validates before touching live settings/files. |
| `restore_backup` | `BackupService.restore` | BackupSection.kt:39–45; BackupViewModel.kt:57. Preferences are replaced in both modes; installed font bundles are skipped on collisions even in COPY mode. |
| `cancel_operation` | `OperationService.cancel` | ImportDecisions.cancel, PhotoViewModel.cancel, BackupViewModel.cancelRestore. Service reports NOT_CANCELLABLE after irreversible write begins; never promise rollback of USB writes. |
| `set_settings` | `SettingsService.set` | SettingsViewModel.kt:25–54 and 147–174; ThemeMode.kt. All values explicit; resolve existing profile/custom-machine handles. Does not change active project hoop or expose sewing parameters. |
| `save_custom_frame` | `SettingsService.saveCustomFrame` | CustomFrame.kt:16–29; SettingsViewModel.kt:117–119. Name nonblank at service, native dimensions 1..100000 units, HALF_UP rounding; existing max 100 frames. Explicit replacement needs consent. |
| `delete_custom_frame` | `SettingsService.deleteCustomFrame` | SettingsViewModel.kt:123; CustomFramesSection.kt:64. Default-frame fallback retained. |
| `navigate` | `PresentationService.navigate` | MainActivity.kt:126–245; HomeTabs; font overlay. Must use document replacement guard; no implicit discard. |
| `set_selection` | `PresentationService.setSelection` | EditorViewModel.kt:320–338. Not used as mutation targeting; edit tools always carry explicit IDs. |
| `set_view` | `PresentationService.setView` | EditorCanvas.kt:140; StitchCanvas.kt:118; EditorWorkspace.kt:306. Adapter converts camera mm to display coordinates. Validate screen capabilities: VIEWER has no exposed jump toggle; reject unsupported changes instead of advertising a new UI control. Existing zoom/preview ranges enforced by shared adapter. |
| `seek_playback` | `PresentationService.seekPlayback` | ViewerViewModel.kt:360; range −1..endIndex resolved from current timeline; no fixed maximum. |
| `set_playback` | `PresentationService.setPlayback` | ViewerViewModel.kt:358–372. Desired-state adapter, not toggle retry; source stitches unchanged; no exposed speed field. |
| `focus_sewout_finding` | `PresentationService.focusFinding` | ViewerViewModel.kt:111–120; empty findingId clears focus; finding IDs resolved by report revision, not model-selected stitch coordinates. |
| `list_free_design_sources` | `SourceDirectory.list` | FreeDesignSourcesScreen.kt:36 and FreeDesignSource bundled validated directory; no network fetch in this query. |
| `open_free_design_source` | `SourceDirectory.open` | FreeDesignSourcesScreen.kt:112–136; only listed source or terms URLs, external browser disclosure needs confirmation. |
| `copy_diagnostics` | `UsbService.copyDiagnostics` | UsbTransferDialogs.kt:122–125; exact selected record, clipboard disclosure requires confirmation. |

### Numeric constraints emitted in the JSON

| Fields | Existing source (pinned to base) | Interpretation |
|---|---|---|
| text `heightMm` maximum 60 | [LetteringHeightLimits.kt:18](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/lettering/LetteringHeightLimits.kt#L18), [LetteringScreen.kt:179](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/lettering/LetteringScreen.kt#L179) | Global cap only; positive finite size, exact font ranges, and selected-hoop height are additional service checks. |
| transform `xMm`/`yMm` −10000..10000, scale percentages 5..2000 | [EditorSelection.kt:102](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/editor/EditorSelection.kt#L102) | Existing inspector parser, not an output sewing-area guarantee. Absolute and copy transforms use the same policy. |
| crop zoom 1..4, pan −1..1 | [PhotoCrop.kt:12](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoCrop.kt#L12), [PhotoViewModel.kt:139](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoViewModel.kt#L139) | Existing photo crop coordinates; distinct from camera pan in mm. |
| detail enum 0/1/2, colorCount 2..6 | [PhotoScreen.kt:129](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoScreen.kt#L129), [PhotoScreen.kt:136](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoScreen.kt#L136), [PhotoViewModel.kt:57](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/photo/PhotoViewModel.kt#L57) | Current user choices, not direct stitch-engine parameter overrides. |
| custom-frame width/height 0.1..10000mm | [CustomFrame.kt:18](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/machines/CustomFrame.kt#L18), [CustomFrame.kt:23](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/machines/CustomFrame.kt#L23) | Native 1..100000 units; round HALF_UP once at shared decoder; current schema requires requested dimensions in the representable interval. |
| color pattern six hex digits with leading # | [EditorAppearance.kt:33](../../app/src/main/kotlin/io/github/linker2k/myembro/ui/editor/EditorAppearance.kt#L33) | Canonical wire representation of the existing RGB parser. Name lengths and file/backup limits remain service-enforced as above. |

## Validation and limits of this draft

Run `python3 tools/check_agent_tools.py` with the already available `jsonschema` package; no dependency installation. It validates each inputSchema against the Draft 2020-12 meta-schema, enforces recursive required/additionalProperties rules and the simple keyword subset, checks names/annotations, checks the four required state queries, tests unknown/missing-field rejection and checks confirmation metadata on destructive tools. It also verifies lossless schema projection into MCP/OpenAI/Gemini envelopes locally. This is not a network/provider acceptance test or implementation test of the proposed services.

`git diff --check` checks formatting. The command-layer proposal's required reducer/controller/scenario tests still need implementation; meta-schema PASS does not claim a functional agent, text parity, permission enforcement, or sewout approval.
