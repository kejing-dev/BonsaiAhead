package io.embodit.bonsai.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.bonsai.domain.MeshData
import io.embodit.bonsai.domain.Q4
import io.embodit.bonsai.domain.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Entity.at(p: V3, q: Q4? = null) {
    transform().setPosition(Vector3(p.x, p.y, p.z))
    q?.let { transform().setQuaternion(Quat(it.x, it.y, it.z, it.w)) }
}

internal fun Entity.scale(x: Float, y: Float, z: Float) { transform().setScaleVector(Vector3(x, y, z)) }
internal fun Entity.scale(s: Float) = scale(s, s, s)

/** 0xRRGGBB -> Color4, [k] scales the colour. */
internal fun rgb4(rgb: Int, k: Float = 1f, a: Float = 1f) = Color4((rgb shr 16 and 255) / 255f * k, (rgb shr 8 and 255) / 255f * k, (rgb and 255) / 255f * k, a)

internal fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 255) * (1 - t) + (b shr s and 255) * t).toInt().coerceIn(0, 255)
    return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal object Kit {
    /** A [MeshData] as an SDK mesh (creation waits on the renderer: call off the main thread). */
    fun mesh(m: MeshData, name: String): MeshResource {
        val pos = ArrayList<Vector3>(m.vertexCount); val nor = ArrayList<Vector3>(m.vertexCount); val uv = ArrayList<Vector2>(m.vertexCount)
        for (i in 0 until m.vertexCount) {
            pos += Vector3(m.pos[3 * i], m.pos[3 * i + 1], m.pos[3 * i + 2])
            nor += Vector3(m.nor[3 * i], m.nor[3 * i + 1], m.nor[3 * i + 2])
            uv += Vector2(m.uv[2 * i], m.uv[2 * i + 1])
        }
        val (lo, hi) = m.bounds()
        val c = (lo + hi) * 0.5f
        val h = (hi - lo) * 0.5f
        return MeshResource.createWithMeshModel(
            model = MeshModel(positions = pos, triangleIndices = m.idx.toList(), normals = nor, uv0 = uv),
            bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 0.001f), maxOf(h.y, 0.001f), maxOf(h.z, 0.001f))),
            name = name,
        )
    }

    fun pbr(rgb: Int, rough: Float, metal: Float = 0f, glow: Float = 0f, tex: TextureResource? = null): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply {
            setBaseColor(rgb4(rgb)); setRoughness(rough); setMetallic(metal)
            tex?.let { setBaseColorTexture(it) }
            if (glow > 0f) setEmissiveColor(rgb4(rgb, glow))
        }

    fun unlit(rgb: Int, opacity: Float = 1f): UnlitMaterial =
        UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(rgb4(rgb))
            if (opacity < 1f) setOpacity(opacity)
            setCullingMode(MaterialCullingMode.NONE)
            setApplyToneMapping(false)
        }

    fun clear(rgb: Int, opacity: Float): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
            setBaseColor(rgb4(rgb)); setOpacity(opacity); setRoughness(0.5f); setEmissiveColor(rgb4(rgb, 0.4f))
            setCullingMode(MaterialCullingMode.NONE)
        }
}

