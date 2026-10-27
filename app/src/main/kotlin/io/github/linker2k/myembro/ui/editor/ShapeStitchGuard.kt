// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Display-only safety net for ShapeObject stitches on the editor canvas, thumbnails and bounds. When this
 * device cannot generate a shape (a LinkageError such as ART rejecting a library class, or an Exception),
 * the object is drawn with its last successful stitches, else its default-settings stitches, else nothing,
 * and is listed in [failedIds] for a warning. OutOfMemoryError is not caught here.
 *
 * Never used for saving or sending: those paths generate for real and are refused when generation fails, so
 * nothing unchecked reaches a file or the machine.
 */
object ShapeStitchGuard {
    /** What the canvas shows for one object; [failure] = the error class and message when it is a fallback. */
    class Shown(val pattern: EmbPattern, val failure: String?)

    private val lastGood = ConcurrentHashMap<String, EmbPattern>()
    private val failed = ConcurrentHashMap<String, String>()

    /** Objects currently drawn from a fallback. */
    fun failedIds(): Set<String> = failed.keys.toSet()

    fun local(obj: DesignObject, generate: (DesignObject) -> EmbPattern = { it.localPattern() }): Shown {
        if (obj !is ShapeObject) return Shown(generate(obj), null)
        val error = try {
            return Shown(generate(obj).also { lastGood[obj.id] = it; failed.remove(obj.id) }, null)
        } catch (e: LinkageError) { e } catch (e: StackOverflowError) { e } catch (e: Exception) { e }
        val reason = "${error.javaClass.name}: ${error.message}"
        failed[obj.id] = reason
        val fallback = lastGood[obj.id] ?: defaults(obj, generate) ?: EmbPattern(emptyList(), emptyList())
        return Shown(fallback, reason)
    }

    /** [local] placed with the object's pose (display only: no lock or finishing pass is applied to a fallback). */
    fun placed(obj: DesignObject, generate: (DesignObject) -> EmbPattern = { it.localPattern() }): Shown {
        val shown = local(obj, generate)
        if (shown.failure == null) return Shown(obj.placedPattern(), null)
        val t = obj.transform
        val placed = shown.pattern.stitches.map { s ->
            val (x, y) = t.apply(s.x.toDouble(), s.y.toDouble())
            Stitch(x.roundToInt(), y.roundToInt(), s.command)
        }
        return Shown(EmbPattern(placed, shown.pattern.threads, shown.pattern.metadata, shown.pattern.warnings), shown.failure)
    }

    /** Default-settings stitches of the same source art (no overrides or order), when that still generates. */
    private fun defaults(obj: ShapeObject, generate: (DesignObject) -> EmbPattern): EmbPattern? = try {
        if (obj.overrides.isEmpty() && obj.regionOrder == obj.regions.map { it.id }) null
        else generate(ShapeObject.fromArt(obj.id, obj.name, obj.sourceArt, obj.widthMm))
    } catch (e: LinkageError) { null } catch (e: StackOverflowError) { null } catch (e: Exception) { null }

    /**
     * The whole project for display (stitch counts, lettering background): the real flatten, or when a shape
     * cannot be generated on this device, the visible objects' guarded placed stitches. Never for export.
     */
    fun displayFlatten(project: Project, flatten: (Project) -> EmbPattern): EmbPattern = try { flatten(project) }
        catch (e: LinkageError) { fallbackFlatten(project) } catch (e: StackOverflowError) { fallbackFlatten(project) }

    private fun fallbackFlatten(project: Project): EmbPattern {
        val parts = project.objects.filter { it.visible }.map { placed(it).pattern }
        return EmbPattern(parts.flatMap { it.stitches }, parts.flatMap { it.threads })
    }

    /** For tests and when a project is replaced. */
    fun clear() { lastGood.clear(); failed.clear() }
}
