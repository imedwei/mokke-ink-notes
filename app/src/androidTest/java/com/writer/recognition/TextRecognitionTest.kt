package com.writer.recognition

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.writer.model.InkLine
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected test that exercises the full text recognition pipeline on a Boox device:
 * service binding → initialization → protobuf encoding → SharedMemory IPC → result parsing.
 *
 * Skipped on non-Boox devices where KHwrService is unavailable.
 */
@RunWith(AndroidJUnit4::class)
class TextRecognitionTest {

    private lateinit var recognizer: OnyxHwrTextRecognizer
    private var hwrAvailable = false

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        hwrAvailable = isHwrServiceAvailable(context)
        assumeTrue("KHwrService not available — skipping on non-Boox device", hwrAvailable)
        recognizer = OnyxHwrTextRecognizer(context)
    }

    @After
    fun tearDown() {
        if (hwrAvailable) {
            recognizer.close()
        }
    }

    @Test
    fun initialize_bindsAndActivates() = runBlocking {
        recognizer.initialize("en-US")
    }

    @Test
    fun recognizeLine_emptyStrokes_returnsEmpty() = runBlocking {
        recognizer.initialize("en-US")
        val line = InkLine(emptyList(), RectF())
        val result = recognizer.recognizeLine(line, "")
        assertTrue("Empty strokes should return empty string", result.isEmpty())
    }

    @Test
    fun recognizeLine_capturedHelloTest_recognizesCorrectly() = runBlocking {
        recognizer.initialize("en-US")
        val line = buildCapturedHelloTest()
        val result = recognizer.recognizeLine(line, "")
        assertEquals("hello test", result)
    }

    // --- Captured stroke data: handwritten "hello test" ---

    private fun buildCapturedHelloTest(): InkLine {
        val bb = RectF(51.31427f, 30.943726f, 367.71918f, 104.24103f)

        // Stroke 0: "h" — vertical down-stroke + curve up-right-down
        val s0 = InkStroke(strokeId = "s0", points = listOf(
            StrokePoint(59.635498f, 30.943726f, 1071.0f, 1773472267298L),
            StrokePoint(59.635498f, 30.943726f, 1956.0f, 1773472267307L),
            StrokePoint(59.635498f, 30.943726f, 2368.0f, 1773472267318L),
            StrokePoint(59.635498f, 30.943726f, 2493.0f, 1773472267327L),
            StrokePoint(59.635498f, 30.943726f, 2749.0f, 1773472267339L),
            StrokePoint(59.635498f, 30.943726f, 2838.0f, 1773472267352L),
            StrokePoint(59.635498f, 30.943726f, 2865.0f, 1773472267366L),
            StrokePoint(59.635498f, 30.943726f, 3057.0f, 1773472267384L),
            StrokePoint(59.83362f, 34.90576f, 3167.0f, 1773472267396L),
            StrokePoint(58.843018f, 46.19748f, 3248.0f, 1773472267405L),
            StrokePoint(57.257996f, 56.10254f, 3284.0f, 1773472267416L),
            StrokePoint(55.276794f, 65.80948f, 3289.0f, 1773472267427L),
            StrokePoint(53.295532f, 74.52591f, 3374.0f, 1773472267435L),
            StrokePoint(51.90863f, 81.45941f, 3351.0f, 1773472267447L),
            StrokePoint(51.31427f, 87.798645f, 3345.0f, 1773472267455L),
            StrokePoint(51.31427f, 91.95877f, 3325.0f, 1773472267466L),
            StrokePoint(51.31427f, 93.54358f, 3298.0f, 1773472267484L),
            StrokePoint(51.31427f, 93.54358f, 3260.0f, 1773472267511L),
            StrokePoint(51.31427f, 93.54358f, 3257.0f, 1773472267557L),
            StrokePoint(54.682373f, 90.77017f, 3273.0f, 1773472267566L),
            StrokePoint(58.446777f, 86.41196f, 3279.0f, 1773472267576L),
            StrokePoint(62.40924f, 82.44992f, 3280.0f, 1773472267587L),
            StrokePoint(65.57922f, 78.487915f, 3273.0f, 1773472267596L),
            StrokePoint(68.55115f, 75.51639f, 3262.0f, 1773472267609L),
            StrokePoint(70.53235f, 74.32779f, 3249.0f, 1773472267623L),
            StrokePoint(72.11737f, 73.33728f, 3234.0f, 1773472267639L),
            StrokePoint(73.70233f, 72.74298f, 3221.0f, 1773472267655L),
            StrokePoint(75.28735f, 72.3468f, 3231.0f, 1773472267669L),
            StrokePoint(77.268616f, 73.13919f, 3244.0f, 1773472267678L),
            StrokePoint(79.0517f, 75.51639f, 3446.0f, 1773472267690L),
            StrokePoint(80.4386f, 79.478424f, 3508.0f, 1773472267701L),
            StrokePoint(81.4292f, 84.82715f, 3556.0f, 1773472267710L),
            StrokePoint(81.4292f, 89.18536f, 3568.0f, 1773472267721L),
            StrokePoint(81.4292f, 93.14737f, 3571.0f, 1773472267732L),
            StrokePoint(81.03296f, 96.316986f, 3554.0f, 1773472267744L),
            StrokePoint(81.03296f, 99.09039f, 3288.0f, 1773472267755L),
            StrokePoint(81.23108f, 101.07141f, 3010.0f, 1773472267765L),
            StrokePoint(81.23108f, 101.86377f, 2756.0f, 1773472267774L),
            StrokePoint(81.62732f, 103.05243f, 1398.0f, 1773472267785L),
            StrokePoint(82.61798f, 104.24103f, 1211.0f, 1773472267796L),
            StrokePoint(82.61798f, 104.24103f, 1211.0f, 1773472267798L),
        ))

        // Stroke 1: "e"
        val s1 = InkStroke(strokeId = "s1", points = listOf(
            StrokePoint(94.10919f, 84.82715f, 374.0f, 1773472268057L),
            StrokePoint(94.10919f, 84.82715f, 736.0f, 1773472268065L),
            StrokePoint(94.10919f, 84.82715f, 918.0f, 1773472268075L),
            StrokePoint(94.10919f, 84.82715f, 1342.0f, 1773472268090L),
            StrokePoint(94.10919f, 84.82715f, 1692.0f, 1773472268102L),
            StrokePoint(94.10919f, 84.82715f, 1974.0f, 1773472268111L),
            StrokePoint(94.10919f, 84.82715f, 2286.0f, 1773472268124L),
            StrokePoint(94.10919f, 84.82715f, 2459.0f, 1773472268137L),
            StrokePoint(94.10919f, 84.82715f, 2510.0f, 1773472268149L),
            StrokePoint(94.10919f, 84.82715f, 2659.0f, 1773472268157L),
            StrokePoint(101.63794f, 85.02524f, 2744.0f, 1773472268169L),
            StrokePoint(106.591f, 83.83664f, 2770.0f, 1773472268177L),
            StrokePoint(108.572266f, 83.04422f, 2792.0f, 1773472268191L),
            StrokePoint(110.15729f, 81.85562f, 2851.0f, 1773472268202L),
            StrokePoint(111.14789f, 80.27081f, 2979.0f, 1773472268215L),
            StrokePoint(111.54413f, 78.686005f, 3094.0f, 1773472268223L),
            StrokePoint(111.54413f, 78.686005f, 3256.0f, 1773472268236L),
            StrokePoint(110.35541f, 78.091705f, 3288.0f, 1773472268250L),
            StrokePoint(107.185425f, 77.695496f, 3294.0f, 1773472268261L),
            StrokePoint(104.01538f, 78.884125f, 3279.0f, 1773472268273L),
            StrokePoint(102.62854f, 80.27081f, 3235.0f, 1773472268284L),
            StrokePoint(100.25104f, 84.23285f, 3209.0f, 1773472268295L),
            StrokePoint(99.8548f, 87.798645f, 3185.0f, 1773472268303L),
            StrokePoint(100.8454f, 91.16635f, 3145.0f, 1773472268316L),
            StrokePoint(104.41168f, 94.53406f, 3126.0f, 1773472268332L),
            StrokePoint(108.374146f, 96.515076f, 3117.0f, 1773472268343L),
            StrokePoint(112.33667f, 97.703674f, 3111.0f, 1773472268352L),
            StrokePoint(116.29913f, 97.505585f, 2270.0f, 1773472268363L),
            StrokePoint(120.45978f, 95.92078f, 2153.0f, 1773472268373L),
            StrokePoint(120.45978f, 95.92078f, 2153.0f, 1773472268375L),
        ))

        // Stroke 2: "l" (first)
        val s2 = InkStroke(strokeId = "s2", points = listOf(
            StrokePoint(139.47968f, 38.867767f, 581.0f, 1773472268650L),
            StrokePoint(139.47968f, 38.867767f, 1143.0f, 1773472268658L),
            StrokePoint(139.47968f, 38.867767f, 1404.0f, 1773472268669L),
            StrokePoint(139.47968f, 38.867767f, 1931.0f, 1773472268682L),
            StrokePoint(139.47968f, 38.867767f, 2096.0f, 1773472268691L),
            StrokePoint(139.47968f, 38.867767f, 2480.0f, 1773472268701L),
            StrokePoint(139.47968f, 38.867767f, 2713.0f, 1773472268714L),
            StrokePoint(139.47968f, 38.867767f, 2781.0f, 1773472268726L),
            StrokePoint(139.47968f, 38.867767f, 2850.0f, 1773472268737L),
            StrokePoint(136.50781f, 46.79181f, 2999.0f, 1773472268751L),
            StrokePoint(132.94159f, 57.291138f, 3049.0f, 1773472268760L),
            StrokePoint(130.7622f, 67.79047f, 3088.0f, 1773472268769L),
            StrokePoint(128.97913f, 77.1012f, 3095.0f, 1773472268780L),
            StrokePoint(128.781f, 84.62903f, 3096.0f, 1773472268790L),
            StrokePoint(129.17725f, 90.57205f, 3078.0f, 1773472268799L),
            StrokePoint(129.96973f, 93.54358f, 2807.0f, 1773472268810L),
            StrokePoint(130.7622f, 96.911285f, 2719.0f, 1773472268819L),
            StrokePoint(130.7622f, 96.911285f, 1535.0f, 1773472268831L),
            StrokePoint(132.74347f, 98.29797f, 1501.0f, 1773472268840L),
        ))

        // Stroke 3: "l" (second)
        val s3 = InkStroke(strokeId = "s3", points = listOf(
            StrokePoint(155.72589f, 41.839264f, 705.0f, 1773472269067L),
            StrokePoint(155.72589f, 41.839264f, 1388.0f, 1773472269075L),
            StrokePoint(155.72589f, 41.839264f, 2049.0f, 1773472269086L),
            StrokePoint(155.72589f, 41.839264f, 2307.0f, 1773472269097L),
            StrokePoint(155.72589f, 41.839264f, 2518.0f, 1773472269106L),
            StrokePoint(155.72589f, 41.839264f, 2835.0f, 1773472269119L),
            StrokePoint(155.72589f, 41.839264f, 2923.0f, 1773472269131L),
            StrokePoint(154.73529f, 45.207f, 2990.0f, 1773472269145L),
            StrokePoint(151.96155f, 56.30063f, 3030.0f, 1773472269154L),
            StrokePoint(149.58405f, 65.80948f, 3043.0f, 1773472269165L),
            StrokePoint(147.60278f, 74.724f, 3046.0f, 1773472269175L),
            StrokePoint(145.62158f, 82.05374f, 3043.0f, 1773472269184L),
            StrokePoint(144.82904f, 88.194855f, 3023.0f, 1773472269195L),
            StrokePoint(144.4328f, 92.94928f, 3018.0f, 1773472269204L),
            StrokePoint(144.63092f, 94.13788f, 2258.0f, 1773472269215L),
            StrokePoint(144.82904f, 94.53406f, 1438.0f, 1773472269226L),
            StrokePoint(146.61218f, 95.32648f, 689.0f, 1773472269234L),
            StrokePoint(148.39532f, 95.32648f, 671.0f, 1773472269241L),
        ))

        // Stroke 4: "o"
        val s4 = InkStroke(strokeId = "s4", points = listOf(
            StrokePoint(167.01904f, 82.64804f, 402.0f, 1773472269373L),
            StrokePoint(167.01904f, 82.64804f, 792.0f, 1773472269380L),
            StrokePoint(167.01904f, 82.64804f, 981.0f, 1773472269391L),
            StrokePoint(167.01904f, 82.64804f, 1190.0f, 1773472269404L),
            StrokePoint(167.01904f, 82.64804f, 1254.0f, 1773472269417L),
            StrokePoint(168.20776f, 88.98724f, 1406.0f, 1773472269425L),
            StrokePoint(169.39648f, 93.54358f, 1595.0f, 1773472269436L),
            StrokePoint(170.18903f, 94.73218f, 1656.0f, 1773472269448L),
            StrokePoint(172.56653f, 95.92078f, 1993.0f, 1773472269457L),
            StrokePoint(175.34027f, 95.72269f, 2261.0f, 1773472269468L),
            StrokePoint(177.71771f, 94.93027f, 2345.0f, 1773472269476L),
            StrokePoint(179.30273f, 93.74167f, 2638.0f, 1773472269489L),
            StrokePoint(180.88776f, 91.36447f, 2936.0f, 1773472269500L),
            StrokePoint(181.48212f, 88.591064f, 3029.0f, 1773472269509L),
            StrokePoint(181.284f, 86.21384f, 3240.0f, 1773472269520L),
            StrokePoint(180.68964f, 85.02524f, 3355.0f, 1773472269532L),
            StrokePoint(177.12335f, 83.04422f, 3388.0f, 1773472269541L),
            StrokePoint(174.15149f, 82.25183f, 3395.0f, 1773472269553L),
            StrokePoint(171.77399f, 82.05374f, 3341.0f, 1773472269566L),
            StrokePoint(169.39648f, 82.84613f, 2959.0f, 1773472269579L),
            StrokePoint(169.39648f, 82.84613f, 2594.0f, 1773472269587L),
            StrokePoint(169.39648f, 82.84613f, 2585.0f, 1773472269594L),
        ))

        // Stroke 5: "t" cross-bar
        val s5 = InkStroke(strokeId = "s5", points = listOf(
            StrokePoint(231.01324f, 75.71451f, 48.0f, 1773472271839L),
            StrokePoint(231.01324f, 75.71451f, 339.0f, 1773472271847L),
            StrokePoint(231.01324f, 75.71451f, 599.0f, 1773472271860L),
            StrokePoint(231.01324f, 75.71451f, 842.0f, 1773472271872L),
            StrokePoint(231.01324f, 75.71451f, 1044.0f, 1773472271881L),
            StrokePoint(231.01324f, 75.71451f, 1427.0f, 1773472271893L),
            StrokePoint(231.01324f, 75.71451f, 1759.0f, 1773472271906L),
            StrokePoint(231.01324f, 75.71451f, 1858.0f, 1773472271914L),
            StrokePoint(231.01324f, 75.71451f, 2170.0f, 1773472271927L),
            StrokePoint(231.01324f, 75.71451f, 2396.0f, 1773472271940L),
            StrokePoint(231.01324f, 75.71451f, 2498.0f, 1773472271952L),
            StrokePoint(231.01324f, 75.71451f, 2568.0f, 1773472271963L),
            StrokePoint(231.01324f, 75.71451f, 2874.0f, 1773472271975L),
            StrokePoint(231.01324f, 75.71451f, 3000.0f, 1773472271988L),
            StrokePoint(232.00385f, 75.51639f, 3064.0f, 1773472272003L),
            StrokePoint(239.5326f, 73.13919f, 3080.0f, 1773472272011L),
            StrokePoint(244.68384f, 72.14868f, 3085.0f, 1773472272022L),
            StrokePoint(249.43884f, 71.55438f, 3089.0f, 1773472272032L),
            StrokePoint(253.4013f, 71.15817f, 3092.0f, 1773472272041L),
            StrokePoint(258.1563f, 70.167694f, 3096.0f, 1773472272052L),
            StrokePoint(263.10944f, 69.177185f, 3094.0f, 1773472272063L),
            StrokePoint(268.06256f, 68.186676f, 2333.0f, 1773472272071L),
            StrokePoint(271.03442f, 67.59238f, 2312.0f, 1773472272081L),
        ))

        // Stroke 6: "t" down-stroke + "e" combined
        val s6 = InkStroke(strokeId = "s6", points = listOf(
            StrokePoint(256.7694f, 52.536713f, 903.0f, 1773472272259L),
            StrokePoint(256.7694f, 52.536713f, 1777.0f, 1773472272268L),
            StrokePoint(256.7694f, 52.536713f, 2375.0f, 1773472272278L),
            StrokePoint(256.7694f, 52.536713f, 2647.0f, 1773472272291L),
            StrokePoint(256.7694f, 52.536713f, 2724.0f, 1773472272304L),
            StrokePoint(256.7694f, 52.536713f, 2766.0f, 1773472272316L),
            StrokePoint(254.59009f, 58.083527f, 2860.0f, 1773472272328L),
            StrokePoint(251.61823f, 67.39429f, 2912.0f, 1773472272340L),
            StrokePoint(249.63696f, 75.3183f, 2929.0f, 1773472272348L),
            StrokePoint(248.05194f, 81.85562f, 2927.0f, 1773472272359L),
            StrokePoint(247.85382f, 87.798645f, 2922.0f, 1773472272370L),
            StrokePoint(248.05194f, 92.55307f, 2916.0f, 1773472272378L),
            StrokePoint(248.6463f, 94.93027f, 2539.0f, 1773472272389L),
            StrokePoint(249.24072f, 97.109375f, 1827.0f, 1773472272401L),
            StrokePoint(252.80695f, 99.88281f, 1188.0f, 1773472272410L),
            StrokePoint(254.59009f, 100.67517f, 1173.0f, 1773472272419L),
        ))

        // Stroke 7: "s"
        val s7 = InkStroke(strokeId = "s7", points = listOf(
            StrokePoint(278.16687f, 92.15686f, 601.0f, 1773472272581L),
            StrokePoint(278.16687f, 92.15686f, 1184.0f, 1773472272589L),
            StrokePoint(278.16687f, 92.15686f, 1577.0f, 1773472272599L),
            StrokePoint(278.16687f, 92.15686f, 1949.0f, 1773472272612L),
            StrokePoint(278.16687f, 92.15686f, 2061.0f, 1773472272621L),
            StrokePoint(278.16687f, 92.15686f, 2434.0f, 1773472272633L),
            StrokePoint(278.16687f, 92.15686f, 2618.0f, 1773472272646L),
            StrokePoint(278.16687f, 92.15686f, 2719.0f, 1773472272658L),
            StrokePoint(284.1106f, 87.996735f, 2794.0f, 1773472272667L),
            StrokePoint(288.27124f, 84.62903f, 2888.0f, 1773472272678L),
            StrokePoint(289.65808f, 83.24234f, 2941.0f, 1773472272690L),
            StrokePoint(290.05438f, 82.05374f, 2977.0f, 1773472272700L),
            StrokePoint(290.64874f, 79.478424f, 3037.0f, 1773472272710L),
            StrokePoint(290.45062f, 77.1012f, 3095.0f, 1773472272725L),
            StrokePoint(290.2525f, 76.70502f, 3142.0f, 1773472272739L),
            StrokePoint(288.8656f, 75.9126f, 3171.0f, 1773472272747L),
            StrokePoint(284.5069f, 75.3183f, 3128.0f, 1773472272758L),
            StrokePoint(280.54437f, 77.695496f, 3123.0f, 1773472272769L),
            StrokePoint(277.17627f, 81.65753f, 3101.0f, 1773472272778L),
            StrokePoint(274.79877f, 85.81763f, 3074.0f, 1773472272789L),
            StrokePoint(273.01562f, 90.37396f, 3068.0f, 1773472272797L),
            StrokePoint(273.01562f, 93.74167f, 3019.0f, 1773472272808L),
            StrokePoint(274.40253f, 97.109375f, 2985.0f, 1773472272821L),
            StrokePoint(276.5819f, 99.48657f, 2859.0f, 1773472272836L),
            StrokePoint(280.54437f, 101.66571f, 2746.0f, 1773472272843L),
            StrokePoint(284.5069f, 102.06189f, 1545.0f, 1773472272854L),
            StrokePoint(289.45996f, 100.87329f, 1379.0f, 1773472272866L),
            StrokePoint(289.45996f, 100.87329f, 1379.0f, 1773472272867L),
        ))

        // Stroke 8: second "s" or "t" stem
        val s8 = InkStroke(strokeId = "s8", points = listOf(
            StrokePoint(320.9618f, 74.1297f, 836.0f, 1773472273014L),
            StrokePoint(320.9618f, 74.1297f, 1646.0f, 1773472273022L),
            StrokePoint(320.9618f, 74.1297f, 2183.0f, 1773472273032L),
            StrokePoint(320.9618f, 74.1297f, 2455.0f, 1773472273045L),
            StrokePoint(320.9618f, 74.1297f, 2533.0f, 1773472273054L),
            StrokePoint(320.9618f, 74.1297f, 2821.0f, 1773472273066L),
            StrokePoint(320.9618f, 74.1297f, 2774.0f, 1773472273079L),
            StrokePoint(316.00867f, 75.71451f, 2805.0f, 1773472273111L),
            StrokePoint(311.05554f, 77.893616f, 2811.0f, 1773472273123L),
            StrokePoint(309.6687f, 79.280304f, 2785.0f, 1773472273132L),
            StrokePoint(308.87616f, 81.26132f, 2733.0f, 1773472273145L),
            StrokePoint(309.27246f, 84.03473f, 2694.0f, 1773472273159L),
            StrokePoint(310.46118f, 86.41196f, 2687.0f, 1773472273172L),
            StrokePoint(314.02744f, 89.58154f, 2686.0f, 1773472273186L),
            StrokePoint(317.98993f, 91.95877f, 2866.0f, 1773472273195L),
            StrokePoint(319.97116f, 92.94928f, 2889.0f, 1773472273206L),
            StrokePoint(322.34866f, 94.13788f, 2901.0f, 1773472273220L),
            StrokePoint(322.34866f, 94.13788f, 3126.0f, 1773472273239L),
            StrokePoint(322.34866f, 94.13788f, 3233.0f, 1773472273252L),
            StrokePoint(320.76367f, 96.911285f, 3306.0f, 1773472273260L),
            StrokePoint(315.01804f, 99.88281f, 3308.0f, 1773472273271L),
            StrokePoint(308.08368f, 101.86377f, 3309.0f, 1773472273283L),
            StrokePoint(301.14935f, 103.64673f, 3193.0f, 1773472273291L),
            StrokePoint(295.60187f, 104.04291f, 2909.0f, 1773472273302L),
            StrokePoint(293.6206f, 104.04291f, 2817.0f, 1773472273310L),
            StrokePoint(293.6206f, 104.04291f, 1483.0f, 1773472273323L),
            StrokePoint(293.6206f, 104.04291f, 1446.0f, 1773472273332L),
        ))

        // Stroke 9: "t" cross-bar (second t)
        val s9 = InkStroke(strokeId = "s9", points = listOf(
            StrokePoint(331.26425f, 74.724f, 633.0f, 1773472273559L),
            StrokePoint(331.26425f, 74.724f, 1247.0f, 1773472273567L),
            StrokePoint(331.26425f, 74.724f, 1922.0f, 1773472273579L),
            StrokePoint(331.26425f, 74.724f, 2143.0f, 1773472273589L),
            StrokePoint(331.26425f, 74.724f, 2317.0f, 1773472273597L),
            StrokePoint(331.26425f, 74.724f, 2598.0f, 1773472273611L),
            StrokePoint(331.26425f, 74.724f, 2842.0f, 1773472273623L),
            StrokePoint(331.26425f, 74.724f, 2916.0f, 1773472273635L),
            StrokePoint(331.26425f, 74.724f, 2992.0f, 1773472273646L),
            StrokePoint(339.58548f, 75.9126f, 2999.0f, 1773472273657L),
            StrokePoint(348.10486f, 75.9126f, 3000.0f, 1773472273667L),
            StrokePoint(355.8317f, 75.3183f, 2993.0f, 1773472273676L),
            StrokePoint(361.97357f, 74.32779f, 2072.0f, 1773472273687L),
            StrokePoint(366.92667f, 73.33728f, 1774.0f, 1773472273695L),
            StrokePoint(367.71918f, 73.13919f, 1769.0f, 1773472273701L),
        ))

        // Stroke 10: "t" down-stroke (second t)
        val s10 = InkStroke(strokeId = "s10", points = listOf(
            StrokePoint(354.64294f, 53.72531f, 1142.0f, 1773472273832L),
            StrokePoint(354.64294f, 53.72531f, 2248.0f, 1773472273840L),
            StrokePoint(354.64294f, 53.72531f, 2551.0f, 1773472273851L),
            StrokePoint(354.64294f, 53.72531f, 2898.0f, 1773472273864L),
            StrokePoint(354.64294f, 53.72531f, 3004.0f, 1773472273872L),
            StrokePoint(354.64294f, 53.72531f, 3160.0f, 1773472273885L),
            StrokePoint(351.8692f, 61.649353f, 3318.0f, 1773472273897L),
            StrokePoint(349.2936f, 70.167694f, 3339.0f, 1773472273908L),
            StrokePoint(347.51047f, 78.884125f, 3356.0f, 1773472273917L),
            StrokePoint(346.9161f, 85.02524f, 3335.0f, 1773472273928L),
            StrokePoint(347.11423f, 90.57205f, 3330.0f, 1773472273936L),
            StrokePoint(347.7086f, 94.53406f, 2685.0f, 1773472273947L),
            StrokePoint(348.69922f, 96.118866f, 1491.0f, 1773472273958L),
            StrokePoint(350.8786f, 98.29797f, 374.0f, 1773472273966L),
            StrokePoint(351.27484f, 98.49609f, 347.0f, 1773472273974L),
        ))

        return InkLine(listOf(s0, s1, s2, s3, s4, s5, s6, s7, s8, s9, s10), bb)
    }

    // --- Helpers ---

    private fun isHwrServiceAvailable(context: android.content.Context): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                "com.onyx.android.ksync",
                "com.onyx.android.ksync.service.KHwrService"
            )
        }
        return context.packageManager.resolveService(
            intent, PackageManager.ResolveInfoFlags.of(0)
        ) != null
    }
}
