// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.backup

import android.content.Context
import kotlinx.serialization.json.*

/** Typed values, rather than copying cached SharedPreferences XML across installations. */
object BackupPreferences {
    internal val names=setOf("myembro_settings","myembro_library","lettering","generated_pes_origins")
    fun snapshot(context: Context): JsonObject = buildJsonObject {
        for(name in names) put(name,buildJsonObject {
            context.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach { (key,value) -> put(key,buildJsonObject {
                when(value) {
                    is String -> {put("type","string");put("value",value)}
                    is Boolean -> {put("type","boolean");put("value",value)}
                    is Int -> {put("type","int");put("value",value)}
                    is Long -> {put("type","long");put("value",value)}
                    is Float -> {put("type","float");put("value",value)}
                    is Set<*> -> {put("type","set");put("value",JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive)))}
                    else -> error("Unsupported preference value")
                }
            }) }
        })
    }
    fun validate(snapshot: JsonObject) {
        require(snapshot.keys.all { it in names })
        for(prefs in snapshot.values)for(value in prefs.jsonObject.values) {
            val e=value.jsonObject;val v=e.getValue("value")
            when(e.getValue("type").jsonPrimitive.content) {
                "string" -> require(v.jsonPrimitive.isString)
                "boolean" -> v.jsonPrimitive.boolean
                "int" -> v.jsonPrimitive.int
                "long" -> v.jsonPrimitive.long
                "float" -> require(v.jsonPrimitive.float.isFinite())
                "set" -> v.jsonArray.forEach { require(it.jsonPrimitive.isString) }
                else -> error("Unsupported preference type")
            }
        }
    }
    fun restore(context: Context, snapshot: JsonObject) {
        validate(snapshot)
        val before=this.snapshot(context)
        try { apply(context,snapshot) } catch(e:Exception) { apply(context,before);throw e }
    }
    private fun apply(context: Context,snapshot: JsonObject) {
        for((name,prefs) in snapshot) {
            val edit=context.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear()
            for((key,value) in prefs.jsonObject) {
                val e=value.jsonObject;val v=e.getValue("value")
                when(e.getValue("type").jsonPrimitive.content) {
                    "string" -> edit.putString(key,v.jsonPrimitive.content)
                    "boolean" -> edit.putBoolean(key,v.jsonPrimitive.boolean)
                    "int" -> edit.putInt(key,v.jsonPrimitive.int)
                    "long" -> edit.putLong(key,v.jsonPrimitive.long)
                    "float" -> edit.putFloat(key,v.jsonPrimitive.float)
                    "set" -> edit.putStringSet(key,v.jsonArray.map { it.jsonPrimitive.content }.toSet())
                }
            }
            check(edit.commit()) { "Could not save restored preferences" }
        }
    }
}
