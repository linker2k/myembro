// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.BlockTools
import kotlinx.serialization.json.*

/** App-owned turns; the engine validates and runs the existing region override command. */
class BlockRelayTools : RelayToolGroup {
    override val definitions = LOCAL
    override suspend fun call(tool: String, args: JsonObject, document: RelayDocument?): RelayResult {
        document ?: return RelayResult.error(ProjectRelayTools.NO_PROJECT)
        val validation = if (tool != "list_blocks") JsonObject(args + ("transactionId" to JsonPrimitive("validate"))) else args
        if ("transactionId" in args) return RelayResult.error("transactionId is set by myembro")
        BlockTools.validate(tool, validation)?.let { return RelayResult.error("Invalid arguments: $it") }
        val output = if (tool == "list_blocks") {
            val open = document.read() ?: return RelayResult.error(ProjectRelayTools.NO_PROJECT)
            BlockTools.list(open.project, open.revision.toString(), args.getValue("objectId").jsonPrimitive.content)
        } else {
            val editor = document.editor ?: return RelayResult.error(ProjectRelayTools.OPEN_EDITOR)
            editor.edit(tool, args)
        }
        return RelayResult.text(output.toString(), output["ok"]?.jsonPrimitive?.booleanOrNull != true)
    }
    companion object {
        val LOCAL = Json.parseToJsonElement("""[
  {
    "name": "list_blocks",
    "description": "List editable regions: ids, colors, block settings, local area mm², and SATIN/RUNNING/FILL prerequisites (not final sewout). Meander tile names only.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "objectId": {
          "type": "string"
        }
      },
      "required": [
        "objectId"
      ],
      "additionalProperties": false
    },
    "annotations": {
      "readOnlyHint": true,
      "idempotentHint": true,
      "openWorldHint": false
    }
  },
  {
    "name": "set_block_stitch",
    "description": "Change selected shape regions in one undo step. Omitted settings stay unchanged; automatic_angle uses the app default. Returns revision and sewing check; invalid or unsafe generation changes nothing.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "objectId": {
          "type": "string"
        },
        "regionIds": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "minItems": 1,
          "uniqueItems": true
        },
        "change": {
          "type": "object",
          "properties": {
            "type": {
              "type": "string",
              "enum": [
                "FILL",
                "SATIN",
                "RUNNING"
              ]
            },
            "fill_method": {
              "type": "string",
              "enum": [
                "tatami_fill",
                "meander_fill",
                "contour_fill",
                "circular_fill",
                "cross_stitch",
                "guided_fill",
                "tartan_fill",
                "legacy_fill"
              ]
            },
            "automatic_angle": {
              "type": "boolean"
            },
            "angle": {
              "type": "number"
            },
            "row_spacing_mm": {
              "type": "number",
              "minimum": 0.1
            },
            "meander_pattern": {
              "type": "string"
            },
            "meander_scale_percent": {
              "type": "array",
              "items": {
                "type": "number",
                "minimum": 1
              },
              "minItems": 1,
              "maxItems": 2
            },
            "zigzag_spacing_mm": {
              "type": "number",
              "minimum": 0.002645833333333333
            }
          },
          "required": [],
          "additionalProperties": false
        }
      },
      "required": [
        "objectId",
        "regionIds",
        "change"
      ],
      "additionalProperties": false
    },
    "annotations": {
      "readOnlyHint": false,
      "destructiveHint": false,
      "idempotentHint": false,
      "openWorldHint": false
    }
  },
  {
    "name": "set_block_order",
    "description": "Move selected shape regions FORWARD (one earlier), BACKWARD (one later), FIRST or LAST in sewing order. Relative selection order and region needles stay unchanged. One undo step; returns revision and sewing check.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "objectId": {
          "type": "string"
        },
        "regionIds": {
          "type": "array",
          "items": {
            "type": "string"
          },
          "minItems": 1,
          "uniqueItems": true
        },
        "move": {
          "type": "string",
          "enum": [
            "FORWARD",
            "BACKWARD",
            "FIRST",
            "LAST"
          ]
        }
      },
      "required": [
        "objectId",
        "regionIds",
        "move"
      ],
      "additionalProperties": false
    },
    "annotations": {
      "readOnlyHint": false,
      "destructiveHint": false,
      "idempotentHint": false,
      "openWorldHint": false
    }
  }
]""").jsonArray.map { it.jsonObject }
    }
}
