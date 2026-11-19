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

/** Canvas textures (painted at start: bark, foliage, moss, the button face with its carved arrow, a leaf card). */
internal object Tex {
    fun bark(): Bitmap {
        val w = 256; val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF6A5546.toInt())
        val rnd = Random(4)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        repeat(160) {
            val dark = rnd.nextFloat() < 0.65f
            p.color = if (dark) android.graphics.Color.argb(70 + rnd.nextInt(80), 42, 30, 22) else android.graphics.Color.argb(50 + rnd.nextInt(50), 150, 128, 104)
            p.strokeWidth = 1f + rnd.nextFloat() * 4f
            val x = rnd.nextFloat() * w; val y0 = rnd.nextFloat() * h; val len = 20f + rnd.nextFloat() * 120f
            val path = Path(); path.moveTo(x, y0)
            var y = y0
            while (y < y0 + len) { y += 8f; path.lineTo(x + 3f * sin(y / 17f + x), y) }
            c.drawPath(path, p)
        }
        return bmp
    }

    /** Foliage: many small leaves in neutral light greys (the material colour tints them per form / season). */
    fun foliage(seed: Int, needles: Boolean): Bitmap {
        val n = 512
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFB8B8B8.toInt())
        val rnd = Random(seed)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(if (needles) 2600 else 1400) {
            val v = 150 + rnd.nextInt(105)
            p.color = android.graphics.Color.rgb(v, v, v)
            val x = rnd.nextFloat() * n; val y = rnd.nextFloat() * n
            val a = rnd.nextFloat() * 360f
            c.save(); c.rotate(a, x, y)
            if (needles) { p.strokeWidth = 2.2f; c.drawLine(x - 9f, y, x + 9f, y, p) }
            else c.drawOval(x - 10f, y - 5f, x + 10f, y + 5f, p)
            c.restore()
        }
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(260) {
            shade.color = android.graphics.Color.argb(40 + rnd.nextInt(50), 30, 30, 30)
            c.drawCircle(rnd.nextFloat() * n, rnd.nextFloat() * n, 3f + rnd.nextFloat() * 5f, shade)
        }
        return bmp
    }

    fun moss(): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF4F6A2E.toInt())
        val rnd = Random(8)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(1800) {
            p.color = if (rnd.nextBoolean()) android.graphics.Color.rgb(98 + rnd.nextInt(40), 128 + rnd.nextInt(40), 54) else android.graphics.Color.rgb(52, 70 + rnd.nextInt(30), 30)
            c.drawCircle(rnd.nextFloat() * n, rnd.nextFloat() * n, 1f + rnd.nextFloat() * 2.5f, p)
        }
        return bmp
    }

    /** The turn button face: light oak with a carved chevron ([d] < 0 points left), image up = +Y. */
    fun buttonFace(d: Int, testF: Boolean = false): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFC9A57A.toInt())
        val rnd = Random(12)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        repeat(40) { grain.color = android.graphics.Color.argb(40 + rnd.nextInt(40), 120, 84, 50); val y = rnd.nextFloat() * n; c.drawLine(0f, y, n.toFloat(), y + rnd.nextFloat() * 10f - 5f, grain) }
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A2F18.toInt(); style = Paint.Style.STROKE; strokeWidth = 30f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val cx = n / 2f + if (d < 0) -10f else 10f; val cy = n / 2f; val a = 62f
        val path = Path()
        if (d < 0) { path.moveTo(cx + a * 0.6f, cy - a); path.lineTo(cx - a * 0.6f, cy); path.lineTo(cx + a * 0.6f, cy + a) }
        else { path.moveTo(cx - a * 0.6f, cy - a); path.lineTo(cx + a * 0.6f, cy); path.lineTo(cx - a * 0.6f, cy + a) }
        c.drawPath(path, groove)
        if (testF) { val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); textSize = 50f }; c.drawText("F↑", 20f, 60f, t) }
        return bmp
    }

    /** A leaf card for the autumn fall (alpha cut-out, MASKED). */
    fun leafCard(): Bitmap {
        val n = 128
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val path = Path()
        for (k in 0 until 3) {
            val ang = Math.toRadians(-90.0 + (k - 1) * 50.0)
            val x = n / 2f + 44f * cos(ang).toFloat(); val y = n * 0.62f + 44f * sin(ang).toFloat()
            path.moveTo(n / 2f, n * 0.62f); path.quadTo(x - 18f, y + 10f, x, y); path.quadTo(x + 18f, y + 10f, n / 2f, n * 0.62f)
        }
        c.drawPath(path, p)
        return bmp
    }

    fun cardMat(tex: TextureResource, rgb: Int): UnlitMaterial = UnlitMaterial.create(BlendingMode.MASKED).apply {
        setBaseColor(rgb4(rgb)); setBaseColorTexture(tex); setOpacityThreshold(0.5f)
        setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false)
    }
}
