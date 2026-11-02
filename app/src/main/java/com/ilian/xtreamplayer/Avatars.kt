package com.ilian.xtreamplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Avatars des profils : une trentaine de personnages originaux, dessinés pour l'appli
 * (aucune image : formes vectorielles calculées, nettes à toutes les tailles).
 * Chacun a son propre fond en dégradé ; la couleur du profil sert d'anneau et de halo.
 */
object Avatars {
    class Avatar(val id: String, val name: String, val theme: String, val top: Int, val bottom: Int, val draw: Pen.() -> Unit)

    val THEMES = listOf("Animaux", "Fantastique", "Espace", "Sport", "Héros", "Fun")

    private const val INK = 0xFF1B1E28.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    val ALL: List<Avatar> = listOf(
        // ---------- Animaux ----------
        Avatar("renard", "Renard", "Animaux", 0xFF2A7A92.toInt(), 0xFF0F3644.toInt()) {
            val fur = 0xFFF2892B.toInt()
            mirror {
                poly(fur, 22f, 46f, 26f, 12f, 47f, 30f)
                poly(0xFF8A3B12.toInt(), 28f, 38f, 29f, 19f, 41f, 30f)
            }
            shape(fur) {
                moveTo(20f, 46f); cubicTo(20f, 30f, 34f, 26f, 50f, 26f); cubicTo(66f, 26f, 80f, 30f, 80f, 46f)
                cubicTo(80f, 62f, 64f, 76f, 50f, 84f); cubicTo(36f, 76f, 20f, 62f, 20f, 46f); close()
            }
            shape(0xFFFFF3E3.toInt()) {
                moveTo(30f, 56f); cubicTo(38f, 52f, 44f, 58f, 50f, 58f); cubicTo(56f, 58f, 62f, 52f, 70f, 56f)
                cubicTo(66f, 70f, 58f, 78f, 50f, 84f); cubicTo(42f, 78f, 34f, 70f, 30f, 56f); close()
            }
            eyes(50f, 11f)
            oval(46f, 61.5f, 54f, 67f, INK)
            smile(70f, 4f)
            shine(30f, 30f, 48f, 40f)
        },
        Avatar("chat", "Chat", "Animaux", 0xFF6B4FB5.toInt(), 0xFF2E1F5E.toInt()) {
            val fur = 0xFFA7B0BC.toInt()
            mirror {
                poly(fur, 24f, 46f, 26f, 14f, 46f, 32f)
                poly(0xFFF4A6B8.toInt(), 29f, 39f, 30f, 21f, 41f, 32f)
            }
            circle(50f, 58f, 28f, fur)
            mirror {
                oval(34.5f, 47f, 45.5f, 58f, 0xFFA4E05B.toInt())
                oval(39f, 48.5f, 41f, 56.5f, INK)
                circle(42f, 50.5f, 1.1f, WHITE)
                line(20f, 62f, 35f, 64f, 0xCCFFFFFF.toInt(), 1.3f)
                line(20f, 68f, 35f, 67f, 0xCCFFFFFF.toInt(), 1.3f)
            }
            poly(0xFFF4A6B8.toInt(), 47f, 62f, 53f, 62f, 50f, 65.5f)
            arc(44.5f, 63f, 50.5f, 69f, 20f, 140f, INK, 1.8f)
            arc(49.5f, 63f, 55.5f, 69f, 20f, 140f, INK, 1.8f)
            shine(32f, 36f, 50f, 46f)
        },
        Avatar("ours", "Ours", "Animaux", 0xFF3D8F5A.toInt(), 0xFF184A2B.toInt()) {
            val fur = 0xFF8B5A2B.toInt()
            mirror {
                circle(28f, 34f, 10f, fur)
                circle(28f, 34f, 5.5f, 0xFFC98E5A.toInt())
            }
            circle(50f, 58f, 28f, fur)
            oval(37f, 58f, 63f, 78f, 0xFFE6BC8C.toInt())
            oval(44.5f, 58.5f, 55.5f, 65f, INK)
            smile(70f, 4.2f)
            eyes(50f, 11f)
            shine(32f, 38f, 50f, 48f)
        },
        Avatar("panda", "Panda", "Animaux", 0xFF4A7FC8.toInt(), 0xFF213F72.toInt()) {
            mirror { circle(28f, 33f, 10f, INK) }
            circle(50f, 57f, 28f, 0xFFF7F7F2.toInt())
            mirror {
                rotated(-25f, 39f, 53f) { oval(32f, 46f, 46f, 60f, INK) }
                circle(40.5f, 53f, 2.8f, WHITE)
                circle(41.2f, 53.4f, 1.5f, INK)
            }
            oval(45.5f, 62f, 54.5f, 67.5f, INK)
            smile(71f, 4.2f)
            cheeks(64f, 19f, 3.8f)
        },
        Avatar("lapin", "Lapin", "Animaux", 0xFFC05A96.toInt(), 0xFF6E2A55.toInt()) {
            val fur = 0xFFF4EEF5.toInt()
            mirror {
                rotated(-10f, 37f, 26f) {
                    oval(30f, 4f, 44f, 48f, fur)
                    oval(34f, 10f, 40f, 42f, 0xFFF6A9C2.toInt())
                }
            }
            circle(50f, 61f, 26f, fur)
            eyes(57f, 10f, 3.2f)
            oval(47f, 63f, 53f, 66.5f, 0xFFF6A9C2.toInt())
            oval(46.5f, 67.5f, 53.5f, 73f, INK)
            rrect(47.6f, 67.5f, 52.4f, 70.6f, 1f, WHITE)
            cheeks(65f, 16f)
        },
        Avatar("tigre", "Tigre", "Animaux", 0xFFA33A3A.toInt(), 0xFF541616.toInt()) {
            val fur = 0xFFF39A33.toInt()
            val stripe = 0xFF3B2414.toInt()
            mirror {
                circle(29f, 35f, 9f, fur)
                circle(29f, 35f, 4.6f, 0xFF5A2E12.toInt())
            }
            circle(50f, 58f, 28f, fur)
            poly(stripe, 44f, 31f, 50f, 42f, 56f, 31f)
            mirror {
                poly(stripe, 22f, 50f, 33f, 53f, 22f, 56f)
                poly(stripe, 23f, 61f, 32f, 62f, 24f, 66f)
            }
            oval(37f, 59f, 63f, 78f, 0xFFFFF3E3.toInt())
            eyes(50f, 11f)
            oval(45.5f, 59.5f, 54.5f, 65f, INK)
            smile(70f, 4.2f)
        },
        Avatar("grenouille", "Grenouille", "Animaux", 0xFF2A6FA8.toInt(), 0xFF0F3357.toInt()) {
            val skin = 0xFF5CC85A.toInt()
            mirror { circle(35f, 40f, 11.5f, skin) }
            oval(19f, 40f, 81f, 84f, skin)
            mirror {
                circle(35f, 40f, 8f, WHITE)
                circle(35.8f, 41f, 4f, INK)
                circle(37f, 39.6f, 1.3f, WHITE)
            }
            arc(30f, 54f, 70f, 74f, 25f, 130f, INK, 2.6f)
            cheeks(62f, 21f, 4.5f)
            shine(28f, 46f, 46f, 56f)
        },
        Avatar("hibou", "Hibou", "Animaux", 0xFF34407A.toInt(), 0xFF141A38.toInt()) {
            val feathers = 0xFF8D6E63.toInt()
            mirror { poly(feathers, 24f, 36f, 27f, 13f, 42f, 28f) }
            oval(21f, 24f, 79f, 92f, feathers)
            oval(30f, 64f, 70f, 92f, 0xFFBCAAA4.toInt())
            mirror { circle(38f, 50f, 13f, 0xFFF3E3C3.toInt()) }
            mirror {
                circle(38f, 50f, 8.5f, 0xFFFFB300.toInt())
                circle(38.6f, 50.6f, 4.6f, INK)
                circle(40f, 49f, 1.5f, WHITE)
            }
            poly(0xFFF57C00.toInt(), 46f, 58f, 54f, 58f, 50f, 67f)
            for (x in floatArrayOf(40f, 50f, 60f)) arc(x - 4f, 72f, x + 4f, 80f, 20f, 140f, feathers, 1.6f)
        },
        Avatar("pingouin", "Pingouin", "Animaux", 0xFF3399BE.toInt(), 0xFF15526A.toInt()) {
            oval(23f, 20f, 77f, 96f, 0xFF263238.toInt())
            mirror { circle(42f, 53f, 13f, WHITE) }
            oval(32f, 52f, 68f, 92f, WHITE)
            eyes(52f, 8f, 3.1f)
            poly(0xFFFFA726.toInt(), 44.5f, 59f, 55.5f, 59f, 50f, 66f)
            cheeks(60f, 15f, 3.6f)
            shine(32f, 26f, 48f, 34f)
        },
        Avatar("koala", "Koala", "Animaux", 0xFF6B9A78.toInt(), 0xFF35523E.toInt()) {
            mirror {
                circle(25f, 40f, 14f, 0xFF9EA7B0.toInt())
                circle(25f, 40f, 8f, 0xFFE9D6DE.toInt())
            }
            circle(50f, 58f, 27f, 0xFFAEB7C0.toInt())
            eyes(51f, 13f, 3f)
            oval(43.5f, 52f, 56.5f, 70f, 0xFF2E3440.toInt())
            oval(46f, 54f, 50f, 58f, 0x55FFFFFF)
            smile(74f, 4f)
            cheeks(64f, 18f, 3.8f)
        },
        Avatar("lion", "Lion", "Animaux", 0xFF6B3AA8.toInt(), 0xFF341A57.toInt()) {
            val mane = 0xFFC0712B.toInt()
            for (i in 0 until 14) {
                val a = i * (2 * Math.PI / 14)
                circle(50f + 30f * cos(a).toFloat(), 56f + 30f * sin(a).toFloat(), 10f, mane)
            }
            circle(50f, 56f, 31f, mane)
            mirror {
                circle(33f, 37f, 6f, 0xFFF2B544.toInt())
                circle(33f, 37f, 3f, 0xFF8A4B17.toInt())
            }
            circle(50f, 58f, 24f, 0xFFF2B544.toInt())
            oval(39f, 60f, 61f, 75f, 0xFFFDE7B5.toInt())
            eyes(53f, 9f)
            oval(46f, 60f, 54f, 65f, INK)
            smile(69f, 4f)
        },
        Avatar("chien", "Chien", "Animaux", 0xFF34ABA4.toInt(), 0xFF17605C.toInt()) {
            val brown = 0xFF7B4A2E.toInt()
            circle(50f, 56f, 27f, 0xFFE8C9A0.toInt())
            mirror { rotated(18f, 24f, 52f) { oval(16f, 34f, 32f, 72f, brown) } }
            oval(55f, 42f, 71f, 58f, brown)
            eyes(51f, 10f)
            oval(44.5f, 59f, 55.5f, 65.5f, INK)
            rrect(46.5f, 67f, 53.5f, 77f, 3.5f, 0xFFFF7A90.toInt())
            line(50f, 68.5f, 50f, 74f, 0xFFE0556D.toInt(), 1f)
            arc(44f, 61f, 50f, 68f, 20f, 140f, INK, 1.8f)
            arc(50f, 61f, 56f, 68f, 20f, 140f, INK, 1.8f)
        },

        // ---------- Fantastique ----------
        Avatar("dragon", "Dragon", "Fantastique", 0xFF1B7A88.toInt(), 0xFF0B3840.toInt()) {
            val horn = 0xFFF5E6C8.toInt()
            mirror { poly(horn, 31f, 34f, 25f, 10f, 41f, 28f) }
            poly(horn, 45f, 27f, 50f, 16f, 55f, 27f)
            rrect(22f, 26f, 78f, 86f, 26f, 0xFF3FB27F.toInt())
            oval(31f, 58f, 69f, 86f, 0xFF84DDA8.toInt())
            mirror {
                oval(43f, 66f, 46.5f, 70.5f, 0xFF2F7F5A.toInt())
                oval(33f, 43f, 46f, 53f, 0xFFFFD54F.toInt())
                oval(38.8f, 43.6f, 40.2f, 52.4f, INK)
            }
            cheeks(58f, 21f, 3.8f)
            smile(77f, 6f)
        },
        Avatar("licorne", "Licorne", "Fantastique", 0xFF8068C8.toInt(), 0xFF3F2F78.toInt()) {
            val coat = 0xFFFAF8FF.toInt()
            circle(66f, 36f, 10f, 0xFFFF8FC7.toInt())
            circle(73f, 50f, 9f, 0xFF8FD3FF.toInt())
            circle(70f, 64f, 8f, 0xFFC59BFF.toInt())
            circle(56f, 28f, 8f, 0xFFFFC36B.toInt())
            poly(coat, 32f, 36f, 34f, 18f, 45f, 30f)
            oval(28f, 26f, 72f, 88f, coat)
            poly(0xFFFFD54F.toInt(), 45f, 30f, 55f, 30f, 50f, 4f)
            line(46.8f, 22f, 53.2f, 20.5f, 0xFFE0A800.toInt(), 1.2f)
            line(47.8f, 15f, 52.2f, 14f, 0xFFE0A800.toInt(), 1.2f)
            happyEyes(56f, 9.5f)
            cheeks(64f, 15f)
            mirror { circle(46f, 78f, 1.4f, 0xFFC9B8E0.toInt()) }
            smile(70f, 4f)
        },
        Avatar("fantome", "Fantôme", "Fantastique", 0xFF3D3A80.toInt(), 0xFF17163A.toInt()) {
            stars()
            shape(0xFFF7F7FF.toInt()) {
                moveTo(24f, 48f)
                arcTo(RectF(24f, 22f, 76f, 74f), 180f, 180f, false)
                lineTo(76f, 84f)
                quadTo(70.8f, 93f, 65.6f, 84f); quadTo(60.4f, 76f, 55.2f, 84f); quadTo(50f, 93f, 44.8f, 84f)
                quadTo(39.6f, 76f, 34.4f, 84f); quadTo(29.2f, 93f, 24f, 84f); close()
            }
            mirror {
                oval(37f, 42f, 45f, 54f, INK)
                circle(42.5f, 45f, 1.4f, WHITE)
            }
            oval(46.5f, 58f, 53.5f, 66f, INK)
            cheeks(56f, 15f, 3.6f)
        },
        Avatar("monstre", "Monstre", "Fantastique", 0xFFA13185.toInt(), 0xFF4F1240.toInt()) {
            mirror { poly(0xFFB2F5EA.toInt(), 30f, 32f, 26f, 14f, 40f, 27f) }
            rrect(21f, 24f, 79f, 94f, 28f, 0xFF2EC4B6.toInt())
            circle(50f, 48f, 13f, WHITE)
            circle(50f, 49f, 7f, 0xFF3A86FF.toInt())
            circle(50f, 49f, 3.6f, INK)
            circle(52.5f, 46.5f, 1.6f, WHITE)
            shape(0xFF5C1A33.toInt()) { moveTo(34f, 64f); quadTo(50f, 86f, 66f, 64f); close() }
            poly(WHITE, 39f, 64f, 43f, 64f, 41f, 69f)
            poly(WHITE, 57f, 64f, 61f, 64f, 59f, 69f)
            mirror {
                circle(27f, 60f, 1.6f, 0xFF239E93.toInt())
                circle(30f, 72f, 1.2f, 0xFF239E93.toInt())
            }
        },

        // ---------- Espace ----------
        Avatar("alien", "Extra-terrestre", "Espace", 0xFF214A80.toInt(), 0xFF0B1D3A.toInt()) {
            stars()
            val skin = 0xFF8BE07A.toInt()
            mirror {
                line(41f, 28f, 34f, 12f, skin, 2.2f)
                circle(34f, 11f, 3.4f, 0xFFFFD54F.toInt())
            }
            shape(skin) {
                moveTo(50f, 22f); cubicTo(70f, 22f, 78f, 38f, 76f, 52f); cubicTo(74f, 68f, 60f, 82f, 50f, 84f)
                cubicTo(40f, 82f, 26f, 68f, 24f, 52f); cubicTo(22f, 38f, 30f, 22f, 50f, 22f); close()
            }
            mirror {
                rotated(25f, 39f, 52f) { oval(31f, 46f, 47f, 58f, INK) }
                circle(37f, 49.5f, 1.8f, WHITE)
            }
            smile(68f, 4.5f)
            cheeks(63f, 18f, 3.4f)
        },
        Avatar("robot", "Robot", "Espace", 0xFF3A6AA8.toInt(), 0xFF1A3359.toInt()) {
            val metal = 0xFF90A4AE.toInt()
            line(50f, 24f, 50f, 12f, metal, 2.4f)
            circle(50f, 11f, 4f, 0xFFFF5A5F.toInt())
            mirror { rrect(18f, 40f, 26f, 58f, 3f, metal) }
            rrect(24f, 22f, 76f, 74f, 12f, 0xFFD5DEE3.toInt())
            rrect(31f, 35f, 69f, 55f, 9f, 0xFF22303A.toInt())
            mirror {
                circle(41f, 45f, 4.6f, 0xFF4DE1FF.toInt())
                circle(42.2f, 43.8f, 1.4f, WHITE)
            }
            rrect(39f, 61f, 61f, 67f, 3f, metal)
            for (x in floatArrayOf(44f, 50f, 56f)) line(x, 62.2f, x, 65.8f, 0xFF5F7480.toInt(), 1.2f)
            rrect(44f, 74f, 56f, 80f, 2f, metal)
            rrect(22f, 80f, 78f, 120f, 16f, 0xFFB0BEC5.toInt())
            circle(50f, 90f, 3.2f, 0xFF4DE1FF.toInt())
        },
        Avatar("astronaute", "Astronaute", "Espace", 0xFF24507E.toInt(), 0xFF0D1B2A.toInt()) {
            stars()
            val suit = 0xFFECEFF1.toInt()
            rrect(20f, 78f, 80f, 120f, 18f, suit)
            rrect(42f, 84f, 58f, 92f, 2f, 0xFFCFD8DC.toInt())
            circle(46f, 88f, 1.5f, 0xFFFF5A5F.toInt())
            circle(50f, 88f, 1.5f, 0xFF4DE1FF.toInt())
            circle(54f, 88f, 1.5f, 0xFFFFD54F.toInt())
            mirror { rrect(17f, 42f, 23f, 58f, 2f, 0xFFCFD8DC.toInt()) }
            circle(50f, 50f, 30f, suit)
            oval(29f, 33f, 71f, 69f, 0xFF1C2A3A.toInt())
            oval(34f, 37f, 66f, 69f, 0xFFE0AC69.toInt())
            eyes(50f, 8f, 3f)
            smile(59f, 4.5f)
            cheeks(56f, 12f, 3.2f)
            oval(29f, 33f, 71f, 69f, 0x334FC3F7)
            arc(32f, 36f, 68f, 66f, 200f, 60f, 0x99FFFFFF.toInt(), 2.2f)
        },

        // ---------- Sport ----------
        Avatar("footballeur", "Footballeur", "Sport", 0xFF3A9A40.toInt(), 0xFF1B5E20.toInt()) {
            ring(50f, 104f, 30f, 0x33FFFFFF, 1.5f)
            rrect(20f, 76f, 80f, 120f, 18f, 0xFF1565C0.toInt())
            poly(WHITE, 43f, 76f, 57f, 76f, 50f, 85f)
            val skin = 0xFF8D5524.toInt()
            mirror { circle(29f, 54f, 4.5f, skin) }
            circle(50f, 52f, 22f, skin)
            for ((x, y) in listOf(34f to 38f, 40f to 33f, 47f to 31f, 54f to 31f, 61f to 34f, 66f to 40f)) circle(x, y, 7.5f, 0xFF2B1B12.toInt())
            eyes(54f, 8f, 3f)
            smile(63f, 5f)
            ball(77f, 82f, 10f)
        },
        Avatar("basketteur", "Basketteur", "Sport", 0xFF5A44B0.toInt(), 0xFF2A1B3D.toInt()) {
            val skin = 0xFFC68642.toInt()
            rrect(20f, 76f, 80f, 120f, 18f, skin)
            rrect(30f, 78f, 70f, 120f, 8f, 0xFFE53935.toInt())
            arc(42f, 70f, 58f, 86f, 20f, 140f, WHITE, 2f)
            mirror { circle(29f, 54f, 4.5f, skin) }
            circle(50f, 52f, 22f, skin)
            shape(0xFF2B1B12.toInt()) {
                moveTo(28f, 48f); cubicTo(28f, 30f, 40f, 28f, 50f, 28f); cubicTo(60f, 28f, 72f, 30f, 72f, 48f)
                lineTo(68f, 44f); cubicTo(62f, 38f, 38f, 38f, 32f, 44f); close()
            }
            rrect(27f, 39f, 73f, 45f, 3f, WHITE)
            eyes(55f, 8f, 3f)
            smile(64f, 5f)
            circle(24f, 84f, 10f, 0xFFF57C00.toInt())
            val seam = 0xFF5D2E0A.toInt()
            line(24f, 74f, 24f, 94f, seam, 1.2f)
            line(14f, 84f, 34f, 84f, seam, 1.2f)
            arc(8f, 76f, 24f, 92f, -60f, 120f, seam, 1.2f)
            arc(24f, 76f, 40f, 92f, 120f, 120f, seam, 1.2f)
        },
        Avatar("pilote", "Pilote", "Sport", 0xFF9A2424.toInt(), 0xFF4A0E0E.toInt()) {
            val red = 0xFFFF3B30.toInt()
            rrect(20f, 78f, 80f, 120f, 18f, 0xFF1C1C1E.toInt())
            mirror { rrect(36f, 78f, 40f, 120f, 2f, red) }
            circle(50f, 50f, 29f, red)
            rrect(22f, 58f, 78f, 76f, 12f, red)
            arc(26f, 26f, 74f, 74f, 215f, 110f, 0xDDFFFFFF.toInt(), 4f)
            rrect(28f, 42f, 72f, 60f, 10f, 0xFF1C1C1E.toInt())
            rrect(31f, 45f, 69f, 57f, 7f, 0xFFF1C27D.toInt())
            eyes(51f, 8f, 2.8f)
            rrect(28f, 42f, 72f, 60f, 10f, 0x66000000)
            arc(31f, 44f, 69f, 58f, 200f, 50f, 0x99FFFFFF.toInt(), 2.2f)
        },

        // ---------- Héros ----------
        Avatar("ninja", "Ninja", "Héros", 0xFF3A3A4A.toInt(), 0xFF16161D.toInt()) {
            val cloth = 0xFF263238.toInt()
            rrect(22f, 78f, 78f, 120f, 18f, cloth)
            circle(50f, 52f, 27f, cloth)
            rrect(27f, 43f, 73f, 58f, 7.5f, 0xFFF1C27D.toInt())
            line(36f, 46.5f, 45f, 48.5f, INK, 2f)
            line(64f, 46.5f, 55f, 48.5f, INK, 2f)
            eyes(52.5f, 9f, 2.9f)
            rrect(22f, 35f, 78f, 41f, 3f, 0xFFE53935.toInt())
            shape(0xFFE53935.toInt()) { moveTo(75f, 36f); lineTo(92f, 30f); lineTo(88f, 38f); lineTo(94f, 45f); lineTo(75f, 41f); close() }
        },
        Avatar("heros", "Super-héros", "Héros", 0xFF9A2A38.toInt(), 0xFF3D0C11.toInt()) {
            val suit = 0xFF1E3A8A.toInt()
            mirror { poly(0xFFE53935.toInt(), 18f, 82f, 32f, 76f, 30f, 104f) }
            rrect(22f, 76f, 78f, 120f, 18f, suit)
            star(50f, 91f, 7f, 0xFFFFD54F.toInt())
            val skin = 0xFFD9A066.toInt()
            mirror { circle(29f, 53f, 4.4f, skin) }
            circle(50f, 52f, 22f, skin)
            shape(0xFF1F1B18.toInt()) {
                moveTo(28f, 50f); cubicTo(26f, 30f, 42f, 26f, 52f, 27f); cubicTo(64f, 28f, 74f, 34f, 72f, 50f)
                cubicTo(66f, 40f, 58f, 36f, 50f, 38f); cubicTo(42f, 36f, 34f, 40f, 28f, 50f); close()
            }
            shape(suit) {
                moveTo(27f, 48f); quadTo(50f, 42f, 73f, 48f); lineTo(75f, 53f); quadTo(71f, 60f, 62f, 59f)
                quadTo(56f, 58f, 50f, 55f); quadTo(44f, 58f, 38f, 59f); quadTo(29f, 60f, 25f, 53f); close()
            }
            mirror { oval(36f, 49f, 45f, 54.5f, WHITE) }
            smile(65f, 5f)
        },
        Avatar("chevalier", "Chevalier", "Héros", 0xFF4A5C66.toInt(), 0xFF1F2A30.toInt()) {
            rrect(20f, 80f, 80f, 120f, 18f, 0xFF90A4AE.toInt())
            shape(0xFFE53935.toInt()) { moveTo(50f, 22f); cubicTo(52f, 8f, 66f, 4f, 74f, 10f); cubicTo(66f, 10f, 60f, 16f, 56f, 24f); close() }
            rrect(27f, 20f, 73f, 82f, 20f, 0xFFB0BEC5.toInt())
            oval(32f, 24f, 50f, 34f, 0x33FFFFFF)
            line(50f, 22f, 50f, 40f, 0xFF90A4AE.toInt(), 2f)
            rrect(32f, 44f, 68f, 51f, 3.5f, 0xFF1C252B.toInt())
            mirror { circle(41f, 47.5f, 1.6f, 0xFFFFE082.toInt()) }
            for (x in floatArrayOf(42f, 46f, 50f, 54f, 58f)) circle(x, 62f, 1.2f, 0xFF5F7480.toInt())
            for (x in floatArrayOf(44f, 48f, 52f, 56f)) circle(x, 67f, 1.2f, 0xFF5F7480.toInt())
        },
        Avatar("sorcier", "Sorcier", "Héros", 0xFF4A3FA8.toInt(), 0xFF1F1A4D.toInt()) {
            stars()
            val beard = 0xFFF5F5F5.toInt()
            rrect(22f, 80f, 78f, 120f, 18f, 0xFF5A31A8.toInt())
            circle(50f, 60f, 19f, 0xFFF1C7A1.toInt())
            for ((x, y, r) in listOf(Triple(36f, 70f, 8f), Triple(42f, 78f, 9f), Triple(50f, 82f, 10f), Triple(58f, 78f, 9f), Triple(64f, 70f, 8f), Triple(50f, 73f, 9f))) circle(x, y, r, beard)
            mirror { oval(41f, 64f, 50f, 69f, beard) }
            eyes(57f, 7.5f, 2.6f)
            mirror { line(39f, 52f, 46f, 51f, beard, 2.2f) }
            circle(50f, 62f, 3f, 0xFFE0A984.toInt())
            oval(18f, 40f, 82f, 50f, 0xFF5A31A8.toInt())
            shape(0xFF6C3FC5.toInt()) { moveTo(28f, 45f); lineTo(72f, 45f); quadTo(66f, 28f, 62f, 6f); quadTo(46f, 24f, 28f, 45f); close() }
            star(55f, 30f, 5f, 0xFFFFD54F.toInt())
        },
        Avatar("pirate", "Pirate", "Héros", 0xFF1597A3.toInt(), 0xFF0B3954.toInt()) {
            val skin = 0xFFE0AC69.toInt()
            val red = 0xFFD62828.toInt()
            rrect(22f, 80f, 78f, 120f, 18f, 0xFF3E2723.toInt())
            poly(WHITE, 42f, 80f, 58f, 80f, 50f, 90f)
            mirror { circle(28f, 57f, 4.4f, skin) }
            circle(50f, 57f, 22f, skin)
            shape(red) {
                moveTo(27f, 52f); cubicTo(27f, 32f, 40f, 30f, 50f, 30f); cubicTo(62f, 30f, 73f, 34f, 73f, 50f)
                cubicTo(60f, 44f, 40f, 44f, 27f, 52f); close()
            }
            circle(74f, 46f, 3.6f, red)
            poly(red, 74f, 46f, 84f, 52f, 80f, 55f)
            poly(red, 74f, 46f, 82f, 42f, 84f, 47f)
            for (x in floatArrayOf(40f, 50f, 60f)) circle(x, if (x == 50f) 36f else 38f, 1.6f, WHITE)
            line(30f, 46f, 70f, 60f, INK, 1.4f)
            oval(55f, 51f, 65f, 60f, INK)
            circle(41f, 55f, 3f, INK)
            circle(42f, 54f, 1f, WHITE)
            shape(0xFF3E2723.toInt()) {
                moveTo(50f, 63f); cubicTo(44f, 60f, 38f, 62f, 36f, 66f); cubicTo(42f, 64f, 46f, 66f, 50f, 65f)
                cubicTo(54f, 66f, 58f, 64f, 64f, 66f); cubicTo(62f, 62f, 56f, 60f, 50f, 63f); close()
            }
            smile(70f, 4f)
            ring(72.5f, 63f, 2.6f, 0xFFFFD54F.toInt(), 1.4f)
        },
        Avatar("cowboy", "Cowboy", "Héros", 0xFFA8507A.toInt(), 0xFF5D2E46.toInt()) {
            val skin = 0xFFF1C27D.toInt()
            val hat = 0xFF8D5A2B.toInt()
            rrect(22f, 82f, 78f, 120f, 18f, 0xFF546E7A.toInt())
            poly(0xFFC62828.toInt(), 38f, 78f, 62f, 78f, 50f, 92f)
            mirror { circle(29f, 60f, 4.2f, skin) }
            circle(50f, 60f, 21f, skin)
            eyes(60f, 7.5f, 2.8f)
            smile(68f, 4.5f)
            cheeks(65f, 13f, 3.2f)
            oval(14f, 38f, 86f, 50f, hat)
            rrect(31f, 16f, 69f, 44f, 12f, hat)
            rrect(31f, 35f, 69f, 40f, 1.5f, 0xFF4E342E.toInt())
        },

        // ---------- Fun ----------
        Avatar("cool", "Cool", "Fun", 0xFF14A394.toInt(), 0xFF00564D.toInt()) {
            circle(50f, 52f, 30f, 0xFFFFCA28.toInt())
            shine(28f, 28f, 52f, 40f)
            rrect(27f, 42f, 48f, 55f, 5f, INK)
            rrect(52f, 42f, 73f, 55f, 5f, INK)
            line(48f, 46f, 52f, 46f, INK, 2f)
            mirror {
                line(27f, 45f, 21f, 42f, INK, 2f)
                line(31f, 45.5f, 35f, 45.5f, 0x88FFFFFF.toInt(), 1.6f)
            }
            arc(36f, 52f, 64f, 72f, 25f, 130f, INK, 2.8f)
        },
        Avatar("gamer", "Gamer", "Fun", 0xFF4F2A96.toInt(), 0xFF1A0B2E.toInt()) {
            val skin = 0xFFF5D0B5.toInt()
            val dark = 0xFF212121.toInt()
            rrect(20f, 78f, 80f, 120f, 18f, 0xFF7C4DFF.toInt())
            line(44f, 80f, 43f, 92f, WHITE, 1.4f)
            line(56f, 80f, 57f, 92f, WHITE, 1.4f)
            mirror { circle(28f, 55f, 4.2f, skin) }
            circle(50f, 54f, 21f, skin)
            shape(0xFF6D4C41.toInt()) {
                moveTo(29f, 52f); cubicTo(27f, 32f, 42f, 28f, 52f, 29f); cubicTo(64f, 30f, 74f, 38f, 71f, 52f)
                cubicTo(66f, 44f, 60f, 42f, 56f, 44f); lineTo(52f, 39f); lineTo(48f, 44f); cubicTo(40f, 42f, 33f, 46f, 29f, 52f); close()
            }
            happyEyes(56f, 8f, 3.6f)
            smile(63f, 5f)
            arc(25f, 26f, 75f, 76f, 195f, 150f, dark, 5f)
            mirror {
                rrect(21f, 46f, 31f, 64f, 4f, dark)
                rrect(23f, 49f, 29f, 61f, 2.5f, 0xFF00E5FF.toInt())
            }
            line(27f, 62f, 40f, 70f, dark, 2.2f)
            circle(41f, 70.5f, 2.4f, dark)
        }
    )

    fun byId(id: String?): Avatar? = if (id == null) null else ALL.firstOrNull { it.id == id }

    /** Avatar choisi pour ce profil ; à défaut, un avatar attribué d'office (toujours le même pour ce profil). */
    fun of(p: Profile): Avatar {
        val chosen = try {
            Db.kvGet("avatar:${p.id}")
        } catch (_: Exception) {
            null
        }
        return byId(chosen) ?: ALL[Math.floorMod((p.id * 7 + p.color).toInt(), ALL.size)]
    }

    fun set(profileId: Long, id: String) {
        try {
            Db.kvPut("avatar:$profileId", id)
        } catch (_: Exception) {
        }
    }
}

/** Crayon des avatars : formes simples dans un carré de 100 × 100, limitées au rond de l'avatar. */
class Pen(private val c: Canvas, mode: PorterDuffXfermode) {
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = mode
    }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = mode
    }
    private val r = RectF()
    private val p = Path()

    fun background(top: Int, bottom: Int) {
        f.shader = LinearGradient(20f, 0f, 80f, 100f, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 100f, 100f, f)
        // Lumière douce venue d'en haut à gauche
        f.shader = RadialGradient(30f, 18f, 62f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 100f, 100f, f)
        f.shader = null
    }

    fun circle(x: Float, y: Float, rad: Float, col: Int) {
        f.color = col
        c.drawCircle(x, y, rad, f)
    }

    fun oval(l: Float, t: Float, rr: Float, b: Float, col: Int) {
        f.color = col
        r.set(l, t, rr, b)
        c.drawOval(r, f)
    }

    fun rrect(l: Float, t: Float, rr: Float, b: Float, rad: Float, col: Int) {
        f.color = col
        r.set(l, t, rr, b)
        c.drawRoundRect(r, rad, rad, f)
    }

    fun poly(col: Int, vararg pts: Float) {
        p.reset()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            p.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        p.close()
        f.color = col
        c.drawPath(p, f)
    }

    fun shape(col: Int, build: Path.() -> Unit) {
        p.reset()
        p.build()
        f.color = col
        c.drawPath(p, f)
    }

    fun arc(l: Float, t: Float, rr: Float, b: Float, start: Float, sweep: Float, col: Int, w: Float) {
        s.color = col
        s.strokeWidth = w
        r.set(l, t, rr, b)
        c.drawArc(r, start, sweep, false, s)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, col: Int, w: Float) {
        s.color = col
        s.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, s)
    }

    fun ring(x: Float, y: Float, rad: Float, col: Int, w: Float) {
        s.color = col
        s.strokeWidth = w
        c.drawCircle(x, y, rad, s)
    }

    fun rotated(deg: Float, px: Float, py: Float, block: Pen.() -> Unit) {
        c.save()
        c.rotate(deg, px, py)
        block()
        c.restore()
    }

    /** Dessine [block], puis son reflet symétrique (yeux, oreilles…). */
    fun mirror(block: Pen.() -> Unit) {
        block()
        c.save()
        c.scale(-1f, 1f, 50f, 50f)
        block()
        c.restore()
    }

    fun eyes(y: Float = 52f, dx: Float = 10f, rad: Float = 3.4f) {
        for (x in floatArrayOf(50f - dx, 50f + dx)) {
            circle(x, y, rad, PEN_INK)
            circle(x + rad * 0.35f, y - rad * 0.35f, rad * 0.32f, 0xFFFFFFFF.toInt())
        }
    }

    fun happyEyes(y: Float = 52f, dx: Float = 10f, w: Float = 4.2f) {
        for (x in floatArrayOf(50f - dx, 50f + dx)) arc(x - w, y - w * 0.6f, x + w, y + w * 0.9f, 200f, 140f, PEN_INK, 2.3f)
    }

    fun smile(y: Float = 61f, w: Float = 6.5f) {
        arc(50f - w, y - w * 0.7f, 50f + w, y + w * 0.7f, 25f, 130f, PEN_INK, 2.3f)
    }

    fun cheeks(y: Float = 60f, dx: Float = 17f, rad: Float = 4.2f) {
        circle(50f - dx, y, rad, 0x66FF6F91)
        circle(50f + dx, y, rad, 0x66FF6F91)
    }

    fun shine(l: Float, t: Float, rr: Float, b: Float) = oval(l, t, rr, b, 0x1FFFFFFF)

    fun stars() {
        val pts = floatArrayOf(18f, 22f, 0.9f, 82f, 18f, 1.1f, 12f, 60f, 0.8f, 88f, 64f, 0.9f, 30f, 10f, 0.7f, 70f, 90f, 0.8f, 14f, 84f, 0.7f, 86f, 40f, 0.6f)
        var i = 0
        while (i < pts.size) {
            circle(pts[i], pts[i + 1], pts[i + 2], 0xCCFFFFFF.toInt())
            i += 3
        }
    }

    fun star(cx: Float, cy: Float, rad: Float, col: Int) {
        p.reset()
        for (k in 0 until 10) {
            val a = Math.toRadians(-90.0 + k * 36.0)
            val rr = if (k % 2 == 0) rad else rad * 0.45f
            val x = cx + rr * cos(a).toFloat()
            val y = cy + rr * sin(a).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        f.color = col
        c.drawPath(p, f)
    }

    /** Ballon de foot (pentagone central et coutures). */
    fun ball(cx: Float, cy: Float, rad: Float) {
        circle(cx, cy, rad, 0xFFFFFFFF.toInt())
        p.reset()
        for (k in 0 until 5) {
            val a = Math.toRadians(-90.0 + k * 72.0)
            val x = cx + rad * 0.36f * cos(a).toFloat()
            val y = cy + rad * 0.36f * sin(a).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            line(x, y, cx + rad * 0.85f * cos(a).toFloat(), cy + rad * 0.85f * sin(a).toFloat(), PEN_INK, 1.2f)
        }
        p.close()
        f.color = PEN_INK
        c.drawPath(p, f)
        ring(cx, cy, rad, 0x33000000, 0.8f)
    }

    private companion object {
        const val PEN_INK = 0xFF1B1E28.toInt()
    }
}

/**
 * Avatar rond (dessiné une fois dans une image à la bonne taille, puis simplement affiché).
 * [ring] : anneau de la couleur du profil.
 */
class AvatarDrawable(private val avatar: Avatars.Avatar, private val ring: Int? = null) : Drawable() {
    private var cache: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height())
        if (size <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != size) {
            bmp = render(size)
            cache = bmp
        }
        canvas.drawBitmap(bmp, b.exactCenterX() - size / 2f, b.exactCenterY() - size / 2f, paint)
    }

    private fun render(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = size / 100f
        c.scale(k, k)
        // Masque rond (bords lissés), puis tout le dessin limité à ce rond
        c.drawCircle(50f, 50f, 50f, Paint(Paint.ANTI_ALIAS_FLAG))
        val pen = Pen(c, PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP))
        pen.background(avatar.top, avatar.bottom)
        avatar.draw(pen)
        // Liseré de lumière en haut, comme un verre bombé
        pen.arc(3f, 3f, 97f, 97f, 205f, 130f, 0x26FFFFFF, 1.6f)
        ring?.let { pen.ring(50f, 50f, 48.4f, it, 3.2f) }
        return bmp
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
