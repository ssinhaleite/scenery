package graphics.scenery.backends.opengl

import cleargl.*
import com.jogamp.common.nio.Buffers
import com.jogamp.opengl.*
import com.jogamp.opengl.util.FPSAnimator
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil
import graphics.scenery.*
import graphics.scenery.backends.*
import graphics.scenery.controls.TrackerInput
import graphics.scenery.fonts.SDFFontAtlas
import graphics.scenery.spirvcrossj.Loader
import graphics.scenery.spirvcrossj.libspirvcrossj
import graphics.scenery.utils.*
import javafx.application.Platform
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.collections.LinkedHashMap
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Deferred Lighting Renderer for scenery
 *
 * This is the main class of scenery's Deferred Lighting Renderer. Currently,
 * a rendering strategy using a 32bit position, 16bit normal, 32bit RGBA diffuse/albedo,
 * and 24bit depth buffer is employed. The renderer supports HDR rendering and does that
 * by default. By deactivating the `hdr.Active` [Settings], HDR can be programmatically
 * deactivated. The renderer also supports drawing to HMDs via OpenVR. If this is intended,
 * make sure the `vr.Active` [Settings] is set to `true`, and that the `Hub` has a HMD
 * instance attached.
 *
 * @param[hub] Hub instance to use and attach to.
 * @param[applicationName] The name of this application.
 * @param[scene] The [Scene] instance to initialize first.
 * @param[windowWidth] Horizontal window size.
 * @param[windowHeight] Vertical window size.
 * @param[embedIn] An optional [SceneryPanel] in which to embed the renderer instance.
 * @param[renderConfigFile] The file to create a [RenderConfigReader.RenderConfig] from.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */

class OpenGLRenderer(hub: Hub,
                     applicationName: String,
                     scene: Scene,
                     width: Int,
                     height: Int,
                     override var embedIn: SceneryPanel? = null,
                     renderConfigFile: String = System.getProperty("scenery.Renderer.Config", "DeferredShading.yml")) : Renderer(), Hubable, ClearGLEventListener {
    /** slf4j logger */
    private val logger by LazyLogger()
    /** [GL4] instance handed over, coming from [ClearGLDefaultEventListener]*/
    lateinit private var gl: GL4
    /** should the window close on next looping? */
    override var shouldClose = false
    /** the scenery window */
    override var window: SceneryWindow = SceneryWindow.UninitializedWindow()
    /** separately stored ClearGLWindow */
    var cglWindow: ClearGLWindow? = null
    /** drawble for offscreen rendering */
    var drawable: GLOffscreenAutoDrawable? = null
    /** Whether the renderer manages its own event loop, which is the case for this one. */
    override var managesRenderLoop = true

    /** The currently active scene */
    var scene: Scene = Scene()

    /** Cache of [Node]s, needed e.g. for fullscreen quad rendering */
    private var nodeStore = ConcurrentHashMap<String, Node>()

    /** Cache for [SDFFontAtlas]es used for font rendering */
    private var fontAtlas = HashMap<String, SDFFontAtlas>()

    /** [Settings] for the renderer */
    override var settings: Settings = Settings()

    /** The hub used for communication between the components */
    override var hub: Hub? = null

    /** Texture cache */
    private var textureCache = HashMap<String, GLTexture>()

    /** Shader Property cache */
    private var shaderPropertyCache = HashMap<Class<*>, List<Field>>()

    /** JOGL Drawable */
    private var joglDrawable: GLAutoDrawable? = null

    /** Flag set when a screenshot is requested */
    private var screenshotRequested = false

    /** Eyes of the stereo render targets */
    var eyes = (0..0)

    /** time since last resizing */
    private var lastResizeTimer = Timer()

    /** Window resizing timeout */
    private var WINDOW_RESIZE_TIMEOUT = 200L

    /** Flag to indicate whether framebuffers have to be recreated */
    @Volatile private var mustRecreateFramebuffers = false

    /** GPU stats object */
    private var gpuStats: GPUStats? = null

    /** heartbeat timer */
    private var heartbeatTimer = Timer()

    var initialized = false
        private set

    @Volatile private var pbos: IntArray = intArrayOf(0, 0)
    private var readIndex = 0
    private var updateIndex = 0

    private var renderConfig: RenderConfigReader.RenderConfig
    override var renderConfigFile = ""
        set(config) {
            field = config
            this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

            mustRecreateFramebuffers = true
        }

    private var renderpasses = LinkedHashMap<String, OpenGLRenderpass>()
    private var flow: List<String>

    /**
     * Extension function of Boolean to use Booleans in GLSL
     *
     * This function converts a Boolean to Int 0, if false, and to 1, if true
     */
    fun Boolean.toInt(): Int {
        if (this) {
            return 1
        } else {
            return 0
        }
    }

    var applicationName = ""

    private val MATERIAL_HAS_DIFFUSE = 0x0001
    private val MATERIAL_HAS_AMBIENT = 0x0002
    private val MATERIAL_HAS_SPECULAR = 0x0004
    private val MATERIAL_HAS_NORMAL = 0x0008
    private val MATERIAL_HAS_ALPHAMASK = 0x0010

    inner class ResizeHandler {
        @Volatile var lastResize = -1L
        var lastWidth = 0
        var lastHeight = 0

        @Synchronized fun queryResize() {
            if (lastWidth <= 0 || lastHeight <= 0) {
                lastWidth = Math.max(1, lastWidth)
                lastHeight = Math.max(1, lastHeight)
                return
            }

            if (lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT < System.nanoTime()) {
                lastResize = System.nanoTime()
                return
            }

            if (lastWidth == window.width && lastHeight == window.height) {
                return
            }

            mustRecreateFramebuffers = true
            gl.glDeleteBuffers(2, pbos, 0)
            pbos[0] = 0
            pbos[1] = 0

            window.width = lastWidth
            window.height = lastHeight

            drawable?.setSurfaceSize(window.width, window.height)

            embedIn?.let { panel ->
                panel.prefWidth = window.width.toDouble()
                panel.prefHeight = window.height.toDouble()
            }

            lastResize = -1L
        }
    }

    class OpenGLBuffer(var gl: GL4, var size: Int) {
        var buffer: ByteBuffer
            private set
        var id = intArrayOf(-1)
            private set
        var alignment = 256L
            private set

        init {
            val tmp = intArrayOf(0, 0)
            gl.glGetIntegerv(GL4.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, tmp, 0)
            alignment = tmp[0].toLong()

            gl.glGenBuffers(1, id, 0)
            buffer = MemoryUtil.memAlloc(maxOf(tmp[0], size))

            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, id[0])
            gl.glBufferData(GL4.GL_UNIFORM_BUFFER, size * 1L, null, GL4.GL_DYNAMIC_DRAW)
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0)
        }

        fun copyFromStagingBuffer() {
            buffer.flip()

            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, id[0])
            gl.glBufferSubData(GL4.GL_UNIFORM_BUFFER, 0, buffer.remaining() * 1L, buffer)
            gl.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0)
        }

        fun reset() {
            buffer.position(0)
            buffer.limit(size)
        }

        fun advance(align: Long = this.alignment): Int {
            val pos = buffer.position()
            val rem = pos.rem(align)

            if (rem != 0L) {
                val newpos = pos + align.toInt() - rem.toInt()
                buffer.position(newpos)
            }

            return buffer.position()
        }
    }

    internal val buffers = HashMap<String, OpenGLBuffer>()
    internal val sceneUBOs = ArrayList<Node>()

    internal val resizeHandler = ResizeHandler()

    /**
     * Constructor for OpenGLRenderer, initialises geometry buffers
     * according to eye configuration. Also initialises different rendering passes.
     *
     */
    init {

        Loader.loadNatives()
        libspirvcrossj.initializeProcess()

        logger.info("Initializing OpenGL Renderer...")
        this.hub = hub
        this.settings = loadDefaultRendererSettings(hub.get(SceneryElement.Settings) as Settings)
        this.window.width = width
        this.window.height = height

        this.renderConfigFile = renderConfigFile
        this.renderConfig = RenderConfigReader().loadFromFile(renderConfigFile)

        this.flow = this.renderConfig.createRenderpassFlow()

        logger.info("Loaded ${renderConfig.name} (${renderConfig.description ?: "no description"}")

        this.scene = scene
        this.applicationName = applicationName

        val hmd = hub.getWorkingHMDDisplay()
        if (settings.get("vr.Active") && hmd != null) {
            this.window.width = hmd.getRenderTargetSize().x().toInt() * 2
            this.window.height = hmd.getRenderTargetSize().y().toInt()
        }

        if (embedIn != null) {
            val profile = GLProfile.getMaxProgrammableCore(true)
            val caps = GLCapabilities(profile)
            caps.hardwareAccelerated = true
            caps.doubleBuffered = true
            caps.isOnscreen = false
            caps.isPBuffer = true
            caps.redBits = 8
            caps.greenBits = 8
            caps.blueBits = 8
            caps.alphaBits = 8

            val factory = GLDrawableFactory.getFactory(profile)
            drawable = factory.createOffscreenAutoDrawable(factory.defaultDevice, caps,
                DefaultGLCapabilitiesChooser(), window.width, window.height)
                .apply {

                    addGLEventListener(this@OpenGLRenderer)

                    animator = FPSAnimator(this, 600)
                    animator.setUpdateFPSFrames(600, null)
                    animator.start()

                    embedIn?.let { panel ->
                        panel.imageView.scaleY = -1.0

                        panel.widthProperty()?.addListener { _, _, newWidth ->
                            resizeHandler.lastWidth = newWidth.toInt()
                        }

                        panel.heightProperty()?.addListener { _, _, newHeight ->
                            resizeHandler.lastHeight = newHeight.toInt()
                        }

                        panel.minWidth = 100.0
                        panel.minHeight = 100.0
                        panel.prefWidth = window.width.toDouble()
                        panel.prefHeight = window.height.toDouble()
                    }

                    window = SceneryWindow.JavaFXStage(embedIn!!)
                    window.width = width
                    window.height = height

                    resizeHandler.lastWidth = window.width
                    resizeHandler.lastHeight = window.height

                }
        } else {
            val w = this.window.width
            val h = this.window.height

            cglWindow = ClearGLWindow("",
                w,
                h, this).apply {

                if (embedIn != null) {

                } else {
                    window = SceneryWindow.ClearGLWindow(this)
                    window.width = w
                    window.height = h

                    this.setFPS(300)
                    this.start()

                    this.isVisible = true
                }
            }
        }
    }

    override fun init(pDrawable: GLAutoDrawable) {
        this.gl = pDrawable.gl.gL4

        val width = this.window.width
        val height = this.window.height

        gl.swapInterval = 0

        val driverString = gl.glGetString(GL4.GL_RENDERER)
        val driverVersion = gl.glGetString(GL4.GL_VERSION)
        logger.info("OpenGLRenderer: $width x $height on $driverString, $driverVersion")

        if (driverVersion.toLowerCase().indexOf("nvidia") != -1 && System.getProperty("os.name").toLowerCase().indexOf("windows") != -1) {
            gpuStats = NvidiaGPUStats()
        }

        val numExtensionsBuffer = IntBuffer.allocate(1)
        gl.glGetIntegerv(GL4.GL_NUM_EXTENSIONS, numExtensionsBuffer)
        val extensions = (0..numExtensionsBuffer[0] - 1).map { gl.glGetStringi(GL4.GL_EXTENSIONS, it) }
        logger.debug("Available OpenGL extensions: ${extensions.joinToString(", ")}")

        settings.set("ssao.FilterRadius", GLVector(5.0f / width, 5.0f / height))

        buffers.put("UBOBuffer", OpenGLBuffer(gl, 10 * 1024 * 1024))
        buffers.put("LightParameters", OpenGLBuffer(gl, 10 * 1024 * 1024))
        buffers.put("VRParameters", OpenGLBuffer(gl, 2 * 1024))
        buffers.put("ShaderPropertyBuffer", OpenGLBuffer(gl, 10 * 1024 * 1024))
        buffers.put("ShaderParametersBuffer", OpenGLBuffer(gl, 128 * 1024))

        settings.set("Renderer.displayWidth", window.width)
        settings.set("Renderer.displayHeight", window.height)

        prepareDefaultTextures()

        renderpasses = prepareRenderpasses(renderConfig, window.width, window.height)

        // enable required features
//        gl.glEnable(GL4.GL_TEXTURE_GATHER)

        initialized = true

        heartbeatTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                gpuStats?.let {
                    it.update(0)

                    hub?.get(SceneryElement.Statistics).let { s ->
                        val stats = s as Statistics

                        stats.add("GPU", it.get("GPU"), isTime = false)
                        stats.add("GPU bus", it.get("Bus"), isTime = false)
                        stats.add("GPU mem", it.get("AvailableDedicatedVideoMemory"), isTime = false)
                    }

                    if (settings.get<Boolean>("Renderer.PrintGPUStats")) {
                        logger.info(it.utilisationToString())
                        logger.info(it.memoryUtilisationToString())
                    }
                }
            }
        }, 0, 1000)

        initializeScene()
    }

    fun prepareRenderpasses(config: RenderConfigReader.RenderConfig, windowWidth: Int, windowHeight: Int): LinkedHashMap<String, OpenGLRenderpass> {
        val framebuffers = ConcurrentHashMap<String, GLFramebuffer>()
        val passes = LinkedHashMap<String, OpenGLRenderpass>()

        val flow = renderConfig.createRenderpassFlow()

        flow.map { passName ->
            val passConfig = config.renderpasses[passName]!!
            val pass = OpenGLRenderpass(passName, passConfig)

            var width = windowWidth
            var height = windowHeight

            config.rendertargets?.filter { it.key == passConfig.output }?.map { rt ->
                logger.info("Creating render framebuffer ${rt.key} for pass $passName")
                width = (settings.get<Float>("Renderer.SupersamplingFactor") * windowWidth).toInt()
                height = (settings.get<Float>("Renderer.SupersamplingFactor") * windowHeight).toInt()

                if (framebuffers.containsKey(rt.key)) {
                    logger.info("Reusing already created framebuffer")
                    pass.output.put(rt.key, framebuffers[rt.key]!!)
                } else {
                    val framebuffer = GLFramebuffer(gl, width, height)

                    rt.value.forEach { att ->
                        logger.info(" + attachment ${att.key}, ${att.value.format.name}")

                        when (att.value.format) {
                            RenderConfigReader.TargetFormat.RGBA_Float32 -> framebuffer.addFloatRGBABuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RGBA_Float16 -> framebuffer.addFloatRGBABuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RGB_Float32 -> framebuffer.addFloatRGBBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RGB_Float16 -> framebuffer.addFloatRGBBuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RG_Float32 -> framebuffer.addFloatRGBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.RG_Float16 -> framebuffer.addFloatRGBuffer(gl, att.key, 16)

                            RenderConfigReader.TargetFormat.RGBA_UInt16 -> framebuffer.addUnsignedByteRGBABuffer(gl, att.key, 16)
                            RenderConfigReader.TargetFormat.RGBA_UInt8 -> framebuffer.addUnsignedByteRGBABuffer(gl, att.key, 8)

                            RenderConfigReader.TargetFormat.Depth32 -> framebuffer.addDepthBuffer(gl, att.key, 32)
                            RenderConfigReader.TargetFormat.Depth24 -> framebuffer.addDepthBuffer(gl, att.key, 24)
                        }
                    }

                    pass.output.put(rt.key, framebuffer)
                    framebuffers.put(rt.key, framebuffer)
                }
            }

            if(passConfig.output == "Viewport") {
                val framebuffer = GLFramebuffer(gl, width, height)
                framebuffer.addUnsignedByteRGBABuffer(gl, "Viewport", 8)

                pass.output.put("Viewport", framebuffer)
                framebuffers.put("Viewport", framebuffer)
            }

            pass.openglMetadata.renderArea = OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt())
            logger.debug("Render area for $passName: ${pass.openglMetadata.renderArea.width}x${pass.openglMetadata.renderArea.height}")

            pass.openglMetadata.viewport = OpenGLRenderpass.Viewport(OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt()),
                0.0f, 1.0f)

            pass.openglMetadata.scissor = OpenGLRenderpass.Rect2D(
                (pass.passConfig.viewportSize.first * width).toInt(),
                (pass.passConfig.viewportSize.second * height).toInt(),
                (pass.passConfig.viewportOffset.first * width).toInt(),
                (pass.passConfig.viewportOffset.second * height).toInt())

            pass.openglMetadata.eye = pass.passConfig.eye
            pass.defaultShader = prepareShaderProgram(Renderer::class.java, pass.passConfig.shaders.toTypedArray())

            pass.initializeShaderParameters(settings, buffers["ShaderParametersBuffer"]!!)

            passes.put(passName, pass)
        }

        // connect inputs
        passes.forEach { pass ->
            val passConfig = config.renderpasses[pass.key]!!

            passConfig.inputs?.forEach { inputTarget ->
                passes.filter {
                    it.value.output.keys.contains(inputTarget)
                }.forEach { pass.value.inputs.put(inputTarget, it.value.output[inputTarget]!!) }
            }

            with(pass.value) {
                // initialize pass if needed
            }
        }

        return passes
    }

    protected fun prepareShaderProgram(baseClass: Class<*>, shaders: Array<String>): OpenGLShaderProgram? {

        val modules = HashMap<GLShaderType, OpenGLShaderModule>()

        shaders.forEach {
            if (baseClass.getResource("shaders/" + it) != null) {
                val m = OpenGLShaderModule(gl, "main", baseClass, "shaders/" + it)
                modules.put(m.shaderType, m)
            } else {
                if(Renderer::class.java.getResource("shaders/" + it) != null && baseClass !is Renderer) {
                    val m = OpenGLShaderModule(gl, "main", Renderer::class.java, "shaders/" + it)
                    modules.put(m.shaderType, m)
                } else {
                    logger.warn("Shader not found: shaders/$it")
                    return null
                }
            }
        }

        val program = OpenGLShaderProgram(gl, modules)
        return if(program.isValid()) {
            program
        } else {
            null
        }
    }

    override fun display(pDrawable: GLAutoDrawable) {
        val fps = pDrawable.animator?.lastFPS ?: 0.0f

        window.setTitle("$applicationName [${this@OpenGLRenderer.javaClass.simpleName}] - ${fps.toInt()} fps")

        this.joglDrawable = pDrawable

        if (mustRecreateFramebuffers) {
            renderpasses = prepareRenderpasses(renderConfig, window.width, window.height)
            flow = renderConfig.createRenderpassFlow()

            mustRecreateFramebuffers = false
        }

        this@OpenGLRenderer.render()
    }

    override fun setClearGLWindow(pClearGLWindow: ClearGLWindow) {
        cglWindow = pClearGLWindow
    }

    override fun getClearGLWindow(): ClearGLDisplayable {
        return cglWindow!!
    }

    override fun reshape(pDrawable: GLAutoDrawable,
                         pX: Int,
                         pY: Int,
                         pWidth: Int,
                         pHeight: Int) {
        var height = pHeight

        if (height == 0)
            height = 1

        this@OpenGLRenderer.reshape(pWidth, height)
    }

    override fun dispose(pDrawable: GLAutoDrawable) {
        pDrawable.animator?.stop()

        this.shouldClose = true
    }

    /**
     * Based on the [GLFramebuffer], devises a texture unit that can be used
     * for object textures.
     *
     * @param[type] texture type
     * @return Int of the texture unit to be used
     */
    fun textureTypeToUnit(target: OpenGLRenderpass, type: String): Int {
        val offset = if (target.inputs.values.isNotEmpty()) {
            target.inputs.values.sumBy { it.boundBufferNum }
        } else {
            0
        }

        return offset + when (type) {
            "ambient" -> 0
            "diffuse" -> 1
            "specular" -> 2
            "normal" -> 3
            "alphamask" -> 4
            "displacement" -> 5
            "3D-volume" -> 6
            else -> {
                logger.warn("Unknown texture type $type"); 10
            }
        }
    }

    private fun textureTypeToArrayName(type: String): String {
        return when (type) {
            "ambient" -> "ObjectTextures[0]"
            "diffuse" -> "ObjectTextures[1]"
            "specular" -> "ObjectTextures[2]"
            "normal" -> "ObjectTextures[3]"
            "alphamask" -> "ObjectTextures[4]"
            "displacement" -> "ObjectTextures[5]"
            "3D-volume" -> "VolumeTextures"
            else -> {
                logger.warn("Unknown texture type $type")
                "ObjectTextures[0]"
            }
        }
    }

    /**
     * Converts a [GeometryType] to an OpenGL geometry type
     *
     * @return Int of the OpenGL geometry type.
     */
    private fun GeometryType.toOpenGLType(): Int {
        return when (this) {
            GeometryType.TRIANGLE_STRIP -> GL4.GL_TRIANGLE_STRIP
            GeometryType.POLYGON -> GL4.GL_TRIANGLES
            GeometryType.TRIANGLES -> GL4.GL_TRIANGLES
            GeometryType.TRIANGLE_FAN -> GL4.GL_TRIANGLE_FAN
            GeometryType.POINTS -> GL4.GL_POINTS
            GeometryType.LINE -> GL4.GL_LINE_STRIP
            GeometryType.LINES_ADJACENCY -> GL4.GL_LINES_ADJACENCY
            GeometryType.LINE_STRIP_ADJACENCY -> GL4.GL_LINE_STRIP_ADJACENCY
        }
    }

    fun Int.toggle(): Int {
        if (this == 0) {
            return 1
        } else if (this == 1) {
            return 0
        }

        logger.warn("Property is not togglable.")
        return this
    }

    /**
     * Toggles deferred shading buffer debug view. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand]
     */
    @Suppress("UNUSED")
    fun toggleDebug() {
        settings.getAllSettings().forEach {
            if (it.toLowerCase().contains("debug")) {
                try {
                    val property = settings.get<Int>(it).toggle()
                    settings.set(it, property)

                } catch(e: Exception) {
                    logger.warn("$it is a property that is not togglable.")
                }
            }
        }
    }

    /**
     * Toggles Screen-space ambient occlusion. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun toggleSSAO() {
        if (!settings.get<Boolean>("ssao.Active")) {
            settings.set("ssao.Active", true)
        } else {
            settings.set("ssao.Active", false)
        }
    }

    /**
     * Toggles HDR rendering. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun toggleHDR() {
        if (!settings.get<Boolean>("hdr.Active")) {
            settings.set("hdr.Active", true)
        } else {
            settings.set("hdr.Active", false)
        }
    }

    /**
     * Increases the HDR exposure value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun increaseExposure() {
        val exp: Float = settings.get<Float>("hdr.Exposure")
        settings.set("hdr.Exposure", exp + 0.05f)
    }

    /**
     * Decreases the HDR exposure value.Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("UNUSED")
    fun decreaseExposure() {
        val exp: Float = settings.get<Float>("hdr.Exposure")
        settings.set("hdr.Exposure", exp - 0.05f)
    }

    /**
     * Increases the HDR gamma value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun increaseGamma() {
        val gamma: Float = settings.get<Float>("hdr.Gamma")
        settings.set("hdr.Gamma", gamma + 0.05f)
    }

    /**
     * Decreases the HDR gamma value. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun decreaseGamma() {
        val gamma: Float = settings.get<Float>("hdr.Gamma")
        if (gamma - 0.05f >= 0) settings.set("hdr.Gamma", gamma - 0.05f)
    }

    /**
     * Toggles fullscreen. Used for e.g.
     * [graphics.scenery.controls.behaviours.ToggleCommand].
     */
    @Suppress("unused")
    fun toggleFullscreen() {
        if (!settings.get<Boolean>("wantsFullscreen")) {
            settings.set("wantsFullscreen", true)
        } else {
            settings.set("wantsFullscreen", false)
        }
    }

    /**
     * Convenience function that extracts the [OpenGLObjectState] from a [Node]'s
     * metadata.
     *
     * @param[node] The node of interest
     * @return The [OpenGLObjectState] of the [Node]
     */
    fun getOpenGLObjectStateFromNode(node: Node): OpenGLObjectState {
        return node.metadata["OpenGLRenderer"] as OpenGLObjectState
    }

    /**
     * Initializes the [Scene] with the [OpenGLRenderer], to be called
     * before [render].
     */
    override fun initializeScene() {
        scene.discover(scene, { it is HasGeometry })
            .forEach { it ->
                it.metadata.put("OpenGLRenderer", OpenGLObjectState())
                initializeNode(it)
            }

        scene.initialized = true
        logger.info("Initialized ${textureCache.size} textures")
    }

    private fun Display.wantsVR(): Display? {
        if (settings.get<Boolean>("vr.Active")) {
            return this@wantsVR
        } else {
            return null
        }
    }

    @Synchronized fun updateDefaultUBOs() {
        // find observer, if none, return
        val cam = scene.findObserver() ?: return

        val hmd = hub?.getWorkingHMDDisplay()?.wantsVR()

        cam.view = cam.getTransformation()

        buffers["VRParameters"]!!.reset()
        val vrUbo = OpenGLUBO(backingBuffer = buffers["VRParameters"]!!)

        vrUbo.add("projection0", {
            (hmd?.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection)
        })
        vrUbo.add("projection1", {
            (hmd?.getEyeProjection(1, cam.nearPlaneDistance, cam.farPlaneDistance)
                ?: cam.projection)
        })
        vrUbo.add("headShift", { hmd?.getHeadToEyeTransform(0) ?: GLMatrix.getIdentity() })
        vrUbo.add("IPD", { hmd?.getIPD() ?: 0.05f })
        vrUbo.add("stereoEnabled", { renderConfig.stereoEnabled.toInt() })

        vrUbo.populate()
        buffers["VRParameters"]!!.copyFromStagingBuffer()

        buffers["UBOBuffer"]!!.reset()
        buffers["ShaderPropertyBuffer"]!!.reset()

        sceneUBOs.forEach { node ->
            node.lock.withLock {
                if (!node.metadata.containsKey(this.javaClass.simpleName)) {
                    return@withLock
                }

                val s = node.metadata[this.javaClass.simpleName] as OpenGLObjectState

                val ubo = s.UBOs["Matrices"]!!

                node.updateWorld(true, false)

                var bufferOffset = ubo.backingBuffer!!.advance()
                ubo.offset = bufferOffset
                node.projection.copyFrom(cam.projection)
                node.view.copyFrom(cam.view)
                ubo.populate(offset = bufferOffset.toLong())

                val materialUbo = (node.metadata["OpenGLRenderer"]!! as OpenGLObjectState).UBOs["MaterialProperties"]!!
                bufferOffset = ubo.backingBuffer.advance()
                materialUbo.offset = bufferOffset

                materialUbo.populate(offset = bufferOffset.toLong())

                if (s.UBOs.containsKey("ShaderProperties")) {
                    val propertyUbo = s.UBOs["ShaderProperties"]!!
                    // TODO: Correct buffer advancement
                    val offset = propertyUbo.backingBuffer!!.advance()
                    propertyUbo.populate(offset = offset.toLong())
                    propertyUbo.offset = offset
                }
            }
        }

        buffers["UBOBuffer"]!!.copyFromStagingBuffer()

        buffers["LightParameters"]!!.reset()

        val lights = scene.discover(scene, { n -> n is PointLight })

        val lightUbo = OpenGLUBO(backingBuffer = buffers["LightParameters"]!!)
        lightUbo.add("ViewMatrix", { cam.view })
        lightUbo.add("CamPosition", { cam.position })
        lightUbo.add("numLights", { lights.size })

        lights.forEachIndexed { i, light ->
            val l = light as PointLight
            l.updateWorld(true, false)

            lightUbo.add("Linear-$i", { l.linear })
            lightUbo.add("Quadratic-$i", { l.quadratic })
            lightUbo.add("Intensity-$i", { l.intensity })
            lightUbo.add("Radius-$i", { -l.linear + Math.sqrt(l.linear * l.linear - 4 * l.quadratic * (1.0 - (256.0f / 5.0) * 100)).toFloat() })
            lightUbo.add("Position-$i", { l.position })
            lightUbo.add("Color-$i", { l.emissionColor })
            lightUbo.add("filler-$i", { 0.0f })
        }

        lightUbo.populate()

        buffers["LightParameters"]!!.copyFromStagingBuffer()
        buffers["ShaderPropertyBuffer"]!!.copyFromStagingBuffer()
    }

    /**
     * Updates a [FontBoard], in case it's fontFamily or contents have changed.
     *
     * If a SDFFontAtlas has already been created for the given fontFamily, this will be used, and
     * cached as well. Else, a new one will be created.
     *
     * @param[board] The [FontBoard] instance.
     */
    private fun updateFontBoard(board: FontBoard) {
        val atlas = fontAtlas.getOrPut(board.fontFamily, { SDFFontAtlas(this.hub!!, board.fontFamily, maxDistance = settings.get<Int>("sdf.MaxDistance")) })
        val m = atlas.createMeshForString(board.text)

        board.vertices = m.vertices
        board.normals = m.normals
        board.indices = m.indices
        board.texcoords = m.texcoords

        board.metadata.remove("OpenGLRenderer")
        board.metadata.put("OpenGLRenderer", OpenGLObjectState())
        initializeNode(board)

        val s = getOpenGLObjectStateFromNode(board)
        val texture = textureCache.getOrPut("sdf-${board.fontFamily}", {
            val t = GLTexture(gl, GLTypeEnum.Float, 1,
                atlas.atlasWidth,
                atlas.atlasHeight,
                1,
                true,
                1)

            t.setClamp(false, false)
            t.copyFrom(atlas.getAtlas(),
                0,
                true)
            t
        })
        s.textures.put("diffuse", texture)
    }

    /**
     * Update a [Node]'s geometry, if needed and run it's preDraw() routine.
     *
     * @param[n] The Node to update and preDraw()
     */
    private fun preDrawAndUpdateGeometryForNode(n: Node) {
        if (n is HasGeometry) {
            if (n.dirty) {
                if (n.lock.tryLock()) {
                    if (n is FontBoard) {
                        updateFontBoard(n)
                    }

                    if (n.vertices.remaining() > 0 && n.normals.remaining() > 0) {
                        updateVertices(n)
                        updateNormals(n)
                    }

                    if (n.texcoords.remaining() > 0) {
                        updateTextureCoords(n)
                    }

                    if (n.indices.remaining() > 0) {
                        updateIndices(n)
                    }

                    n.dirty = false

                    n.lock.unlock()
                }
            }

            n.preDraw()
        }
    }

    /**
     * Set a [GLProgram]'s uniforms according to a [Node]'s [ShaderProperty]s.
     *
     * This functions uses reflection to query for a Node's declared fields and checks
     * whether they are marked up with the [ShaderProperty] annotation. If this is the case,
     * the [GLProgram]'s uniform with the same name as the field is set to its value.
     *
     * Currently limited to GLVector, GLMatrix, Int and Float properties.
     *
     * @param[n] The Node to search for [ShaderProperty]s
     * @param[program] The [GLProgram] used to render the Node
     */
    @Suppress("unused")
    private fun setShaderPropertiesForNode(n: Node, program: GLProgram) {
        shaderPropertyCache
            .getOrPut(n.javaClass, { n.javaClass.declaredFields.filter { it.isAnnotationPresent(ShaderProperty::class.java) } })
            .forEach { property ->
                property.isAccessible = true
                val field = property.get(n)

                when (property.type) {
                    GLVector::class.java -> {
                        program.getUniform(property.name).setFloatVector(field as GLVector)
                    }

                    Int::class.java -> {
                        program.getUniform(property.name).setInt(field as Int)
                    }

                    Float::class.java -> {
                        program.getUniform(property.name).setFloat(field as Float)
                    }

                    GLMatrix::class.java -> {
                        program.getUniform(property.name).setFloatMatrix((field as GLMatrix).floatArray, false)
                    }

                    else -> {
                        logger.warn("Could not derive shader data type for @ShaderProperty ${n.javaClass.canonicalName}.${property.name} of type ${property.type}!")
                    }
                }
            }
    }

    private fun blitFramebuffers(source: GLFramebuffer?, target: GLFramebuffer?,
                                 sourceOffset: OpenGLRenderpass.Rect2D,
                                 targetOffset: OpenGLRenderpass.Rect2D) {
        if (target != null) {
            target.setDrawBuffers(gl)
        } else {
            gl.glBindFramebuffer(GL4.GL_DRAW_FRAMEBUFFER, 0)
        }

        if (source != null) {
            source.setReadBuffers(gl)
        } else {
            gl.glBindFramebuffer(GL4.GL_READ_FRAMEBUFFER, 0)
        }

        if (source?.hasColorAttachment() ?: true) {
            gl.glBlitFramebuffer(
                sourceOffset.offsetX, sourceOffset.offsetY,
                sourceOffset.offsetX + sourceOffset.width, sourceOffset.offsetY + sourceOffset.height,
                targetOffset.offsetX, targetOffset.offsetY,
                targetOffset.offsetX + targetOffset.width, targetOffset.offsetY + targetOffset.height,
                GL4.GL_COLOR_BUFFER_BIT, GL4.GL_LINEAR)
        }

        if (source?.hasDepthAttachment() ?: true && target?.hasDepthAttachment() ?: true) {
            gl.glBlitFramebuffer(
                sourceOffset.offsetX, sourceOffset.offsetY,
                sourceOffset.offsetX + sourceOffset.width, sourceOffset.offsetY + sourceOffset.height,
                targetOffset.offsetX, targetOffset.offsetY,
                targetOffset.offsetX + targetOffset.width, targetOffset.offsetY + targetOffset.height,
                GL4.GL_DEPTH_BUFFER_BIT, GL4.GL_NEAREST)
        } else {
            logger.debug("Either source or target don't have a depth buffer. If blitting to window surface, this is not a problem.")
        }

        gl.glBindFramebuffer(GL4.GL_FRAMEBUFFER, 0)
    }

    /**
     * Renders the [Scene].
     *
     * The general rendering workflow works like this:
     *
     * 1) All visible elements of the Scene are gathered into the renderOrderList, based on their position
     * 2) Nodes that are an instance of another Node, as indicated by their instanceOf property, are gathered
     *    into the instanceGroups map.
     * 3) The eye-dependent geometry buffers are cleared, both color and depth buffers.
     * 4) First for the non-instanced Nodes, then for the instanced Nodes the following steps are executed
     *    for each eye:
     *
     *      i) The world state of the given Node is updated
     *     ii) Model, view, and model-view-projection matrices are calculated for each. If a HMD is present,
     *         the transformation coming from that is taken into account.
     *    iii) The Node's geometry is updated, if necessary.
     *     iv) The eye's geometry buffer is activated and the Node drawn into it.
     *
     * 5) The deferred shading pass is executed, together with eventual post-processing steps, such as SSAO.
     * 6) If HDR is active, Exposure/Gamma tone mapping is performed. Else, this part is skipped.
     * 7) The resulting image is drawn to the screen, or -- if a HMD is present -- submitted to the OpenVR
     *    compositor.
     */
    override fun render() {
        val stats = hub?.get(SceneryElement.Statistics) as? Statistics
        hub?.getWorkingHMD()?.update()

        if (shouldClose) {
            joglDrawable?.animator?.stop()
            return
        }

        val vrActive = settings.get<Boolean>("vr.Active")

        if (scene.children.count() == 0 || renderpasses.isEmpty() || mustRecreateFramebuffers) {
            logger.info("Waiting for initialization")
            Thread.sleep(200)
            return
        }

        val startUboUpdate = System.nanoTime()
        updateDefaultUBOs()
        stats?.add("OpenGLRenderer.updateUBOs", System.nanoTime() - startUboUpdate)

        val renderOrderList = ArrayList<Node>()
        // find observer, or return immediately
        val cam: Camera = scene.findObserver() ?: return

        val hmd: Display? = if (hub!!.has(SceneryElement.HMDInput)
            && (hub!!.get(SceneryElement.HMDInput) as Display).initializedAndWorking()) {
            hub!!.get(SceneryElement.HMDInput) as Display
        } else {
            null
        }

        val tracker: TrackerInput? = if (hub!!.has(SceneryElement.HMDInput)
            && (hub!!.get(SceneryElement.HMDInput) as TrackerInput).initializedAndWorking()) {
            hub!!.get(SceneryElement.HMDInput) as TrackerInput
        } else {
            null
        }

        scene.discover(scene, { n -> n is Renderable && n is HasGeometry && n.visible }).forEach {
            renderOrderList.add(it)
        }

        val instanceGroups = renderOrderList.groupBy { it.instanceOf }

        val headToEye = eyes.map { i ->
            if (hmd == null) {
                GLMatrix.getTranslation(settings.get<Float>("vr.IPD") * -1.0f * Math.pow(-1.0, 1.0 * i).toFloat(), 0.0f, 0.0f).transpose()
            } else {
                hmd.getHeadToEyeTransform(i).clone()
            }
        }

        val pose = tracker?.getPose() ?: GLMatrix.getIdentity()
        cam.view = cam.getTransformation()
        val projection = eyes.map { i ->
            if (vrActive) {
                hmd?.getEyeProjection(i, cam.nearPlaneDistance, cam.farPlaneDistance) ?: cam.projection
            } else {
                cam.projection
            }
        }

        buffers["ShaderParametersBuffer"]?.let { shaderParametersBuffer ->
            shaderParametersBuffer.reset()
            renderpasses.forEach { _, pass -> pass.updateShaderParameters() }
            shaderParametersBuffer.copyFromStagingBuffer()
        }

        flow.forEach { t ->
            val pass = renderpasses[t]!!
            val startPass = System.nanoTime()

            if (pass.passConfig.blitInputs) {
                pass.inputs.forEach { _, input ->
                    blitFramebuffers(input, pass.output.values.firstOrNull(),
                        pass.openglMetadata.viewport.area, pass.openglMetadata.viewport.area)
                }
            }

            if (pass.output.isNotEmpty()) {
                pass.output.values.first().setDrawBuffers(gl)
            } else {
                gl.glBindFramebuffer(GL4.GL_FRAMEBUFFER, 0)
            }

            val inputsBound = pass.inputs.values.fold(0, { acc, fb -> acc + fb.bindTexturesToUnitsWithOffset(gl, acc) })

            gl.glViewport(
                pass.openglMetadata.viewport.area.offsetX,
                pass.openglMetadata.viewport.area.offsetY,
                pass.openglMetadata.viewport.area.width,
                pass.openglMetadata.viewport.area.height)

            gl.glScissor(
                pass.openglMetadata.scissor.offsetX,
                pass.openglMetadata.scissor.offsetY,
                pass.openglMetadata.scissor.width,
                pass.openglMetadata.scissor.height
            )

            gl.glEnable(GL4.GL_SCISSOR_TEST)

            gl.glClearColor(
                pass.openglMetadata.clearValues.clearColor.x(),
                pass.openglMetadata.clearValues.clearColor.y(),
                pass.openglMetadata.clearValues.clearColor.z(),
                pass.openglMetadata.clearValues.clearColor.w())

            if (!pass.passConfig.blitInputs) {
                pass.output.values.forEach {
                    if (it.hasDepthAttachment()) {
                        gl.glClear(GL4.GL_DEPTH_BUFFER_BIT)
                    }
                }
                gl.glClear(GL4.GL_COLOR_BUFFER_BIT)
            }

            gl.glDisable(GL4.GL_SCISSOR_TEST)

            gl.glDepthRange(
                pass.openglMetadata.viewport.minDepth.toDouble(),
                pass.openglMetadata.viewport.maxDepth.toDouble())

            if (pass.passConfig.type == RenderConfigReader.RenderpassType.geometry) {

                gl.glEnable(GL4.GL_DEPTH_TEST)
                gl.glEnable(GL4.GL_CULL_FACE)

                if (pass.passConfig.renderTransparent) {
                    gl.glEnable(GL4.GL_BLEND)
                    gl.glBlendFuncSeparate(
                        pass.passConfig.srcColorBlendFactor.toOpenGL(),
                        pass.passConfig.dstColorBlendFactor.toOpenGL(),
                        pass.passConfig.srcAlphaBlendFactor.toOpenGL(),
                        pass.passConfig.dstAlphaBlendFactor.toOpenGL())

                    gl.glBlendEquationSeparate(
                        pass.passConfig.colorBlendOp.toOpenGL(),
                        pass.passConfig.alphaBlendOp.toOpenGL()
                    )
                } else {
                    gl.glDisable(GL4.GL_BLEND)
                }

                instanceGroups[null]?.forEach nonInstancedDrawing@ { n ->
                    if (n in instanceGroups.keys) {
                        return@nonInstancedDrawing
                    }

                    if (pass.passConfig.renderOpaque && n.material.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                        return@nonInstancedDrawing
                    }

                    if (pass.passConfig.renderTransparent && !n.material.transparent && pass.passConfig.renderOpaque != pass.passConfig.renderTransparent) {
                        return@nonInstancedDrawing
                    }

                    if (n.material.doubleSided) {
                        gl.glDisable(GL4.GL_CULL_FACE)
                    }

                    if (!n.metadata.containsKey("OpenGLRenderer")) {
                        n.metadata.put("OpenGLRenderer", OpenGLObjectState())
                        initializeNode(n)
                        return@nonInstancedDrawing
                    }

                    var s = getOpenGLObjectStateFromNode(n)

                    if (n.material.needsTextureReload) {
                        s = loadTexturesForNode(n, s)
                    }

                    if (n is Skybox) {
                        gl.glCullFace(GL4.GL_FRONT)
                        gl.glDepthFunc(GL4.GL_LEQUAL)
                    }

                    preDrawAndUpdateGeometryForNode(n)

                    val shader = if (s.shader != null) {
                        s.shader!!
                    } else {
                        pass.defaultShader!!
                    }

                    shader.use(gl)

                    if (renderConfig.stereoEnabled) {
                        shader.getUniform("currentEye.eye").setInt(pass.openglMetadata.eye)
                    }

                    var unit = 0
                    pass.passConfig.inputs?.forEach { name ->
                        renderConfig.rendertargets?.get(name)?.forEach {
                            shader.getUniform("Input" + it.key).setInt(unit)
                            unit++
                        }
                    }

                    s.textures.forEach { type, glTexture ->
                        val samplerIndex = textureTypeToUnit(pass, type)

                        @Suppress("SENSELESS_COMPARISON")
                        if (glTexture != null) {
                            gl.glActiveTexture(GL4.GL_TEXTURE0 + samplerIndex)

                            val target = if (glTexture.depth > 1) {
                                GL4.GL_TEXTURE_3D
                            } else {
                                GL4.GL_TEXTURE_2D
                            }

                            gl.glBindTexture(target, glTexture.id)
                            shader.getUniform(textureTypeToArrayName(type)).setInt(samplerIndex)
                        }
                    }

                    var binding = 0
                    s.UBOs.forEach { name, ubo ->
                        if(shader.uboSpecs.containsKey(name)) {
                            val index = shader.getUniformBlockIndex(name)
                            logger.trace("Binding {} for {}, index={}, binding={}, size={}", name, n.name, index, binding, ubo.getSize())

                            if (index == -1) {
                                logger.error("Failed to bind UBO $name for ${n.name} to $binding")
                            } else {
                                gl.glUniformBlockBinding(shader.id, index, binding)
                                gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                    ubo.backingBuffer!!.id[0], 1L * ubo.offset, 1L * ubo.getSize())
                                binding++
                            }
                        }
                    }

                    arrayOf("LightParameters", "VRParameters").forEach { name ->
                        if (shader.uboSpecs.containsKey(name) && shader.isValid()) {
                            val index = shader.getUniformBlockIndex(name)

                            if (index == -1) {
                                logger.error("Failed to bind shader parameter UBO $name for ${pass.passName} to $binding, though it is required by the shader")
                            } else {
                                gl.glUniformBlockBinding(shader.id, index, binding)
                                gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                    buffers[name]!!.id[0],
                                    0L, buffers[name]!!.buffer.remaining().toLong())

                                binding++
                            }
                        }
                    }

                    drawNode(n)
                }

                instanceGroups.keys.filterNotNull().forEach instancedDrawing@ { n ->
                    var start = System.nanoTime()

                    if (!n.metadata.containsKey("OpenGLRenderer")) {
                        n.metadata.put("OpenGLRenderer", OpenGLObjectState())
                        initializeNode(n)
                        return@instancedDrawing
                    }

                    val s = getOpenGLObjectStateFromNode(n)
                    val instances = instanceGroups[n]!!

                    logger.trace("${n.name} has additional instance buffers: ${s.additionalBufferIds.keys}")
                    logger.trace("${n.name} instancing: Instancing group size is ${instances.size}")

                    instances.forEach { node ->
                        if (!node.metadata.containsKey("OpenGLRenderer")) {
                            node.metadata.put("OpenGLRenderer", OpenGLObjectState())
                            initializeNode(node)
                        }

                        node.updateWorld(true, false)
                    }

                    val matrixSize = 4 * 4
                    val models = ArrayList<Float>(matrixSize * instances.size)
                    val modelviews = ArrayList<Float>(matrixSize * instances.size)
                    val modelviewprojs = ArrayList<Float>(matrixSize * instances.size)

                    eyes.forEach {
                        eye ->

                        models.clear()
                        modelviews.clear()
                        modelviewprojs.clear()

                        instances.forEach { node ->
                            node.modelView.copyFrom(headToEye[eye])
                            node.modelView.mult(pose)
                            node.modelView.mult(cam.view)
                            node.modelView.mult(node.world)

                            node.mvp.copyFrom(projection[eye])
                            node.mvp.mult(node.modelView)

                            node.projection.copyFrom(projection[eye])
                            node.view.copyFrom(cam.view)

                            models.addAll(node.world.floatArray.asSequence())
                            modelviews.addAll(node.modelView.floatArray.asSequence())
                            modelviewprojs.addAll(node.mvp.floatArray.asSequence())
                        }

                        logger.trace("${n.name} instancing: Collected ${modelviewprojs.size / matrixSize} MVPs in ${(System.nanoTime() - start) / 10e6}ms")

                        // bind instance buffers
                        start = System.nanoTime()
                        val matrixSizeBytes: Long = 1L * Buffers.SIZEOF_FLOAT * matrixSize * instances.size

                        gl.glBindVertexArray(s.mVertexArrayObject[0])

                        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.additionalBufferIds["Model"]!!)
                        gl.glBufferData(GL4.GL_ARRAY_BUFFER, matrixSizeBytes,
                            FloatBuffer.wrap(models.toFloatArray()), GL4.GL_DYNAMIC_DRAW)

                        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.additionalBufferIds["ModelView"]!!)
                        gl.glBufferData(GL4.GL_ARRAY_BUFFER, matrixSizeBytes,
                            FloatBuffer.wrap(modelviews.toFloatArray()), GL4.GL_DYNAMIC_DRAW)

                        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.additionalBufferIds["MVP"]!!)
                        gl.glBufferData(GL4.GL_ARRAY_BUFFER, matrixSizeBytes,
                            FloatBuffer.wrap(modelviewprojs.toFloatArray()), GL4.GL_DYNAMIC_DRAW)

                        logger.trace("${n.name} instancing: Updated matrix buffers in ${(System.nanoTime() - start) / 10e6}ms")

                        preDrawAndUpdateGeometryForNode(n)

                        pass.output.values.first().setDrawBuffers(gl)
                        drawNodeInstanced(n, count = instances.size)
                    }
                }
            } else {
                gl.glDisable(GL4.GL_CULL_FACE)

                if (pass.output.filter { it.value.hasDepthAttachment() }.isNotEmpty()) {
                    gl.glEnable(GL4.GL_DEPTH_TEST)
                } else {
                    gl.glDisable(GL4.GL_DEPTH_TEST)
                }

                if (pass.passConfig.renderTransparent) {
                    gl.glEnable(GL4.GL_BLEND)

                    gl.glBlendFuncSeparate(
                        pass.passConfig.srcColorBlendFactor.toOpenGL(),
                        pass.passConfig.dstColorBlendFactor.toOpenGL(),
                        pass.passConfig.srcAlphaBlendFactor.toOpenGL(),
                        pass.passConfig.dstAlphaBlendFactor.toOpenGL())

                    gl.glBlendEquationSeparate(
                        pass.passConfig.colorBlendOp.toOpenGL(),
                        pass.passConfig.alphaBlendOp.toOpenGL()
                    )
                } else {
                    gl.glDisable(GL4.GL_BLEND)
                }


                pass.defaultShader?.let { shader ->
                    shader.use(gl)

                    var unit = 0
                    pass.passConfig.inputs?.forEach { name ->
                        renderConfig.rendertargets?.get(name)?.forEach {
                            shader.getUniform("Input" + it.key).setInt(unit)
                            unit++
                        }
                    }

                    var binding = 0
                    pass.UBOs.forEach { name, ubo ->
                        val actualName = if (name.contains("ShaderParameters")) {
                            "ShaderParameters"
                        } else {
                            name
                        }

                        val index = shader.getUniformBlockIndex(actualName)
                        gl.glUniformBlockBinding(shader.id, index, binding)
                        gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                            ubo.backingBuffer!!.id[0],
                            1L * ubo.offset, 1L * ubo.getSize())

                        if (index == -1) {
                            logger.error("Failed to bind shader parameter UBO $actualName for ${pass.passName} to $binding")
                        }
                        binding++
                    }

                    arrayOf("LightParameters", "VRParameters").forEach { name ->
                        if (shader.uboSpecs.containsKey(name)) {
                            val index = shader.getUniformBlockIndex(name)
                            gl.glUniformBlockBinding(shader.id, index, binding)
                            gl.glBindBufferRange(GL4.GL_UNIFORM_BUFFER, binding,
                                buffers[name]!!.id[0],
                                0L, buffers[name]!!.buffer.remaining().toLong())

                            if (index == -1) {
                                logger.error("Failed to bind shader parameter UBO $name for ${pass.passName} to $binding, though it is required by the shader")
                            }

                            binding++
                        }
                    }

                    renderFullscreenQuad(shader)
                }
            }

            stats?.add("Renderer.$t.renderTiming", System.nanoTime() - startPass)
        }

        val viewportPass = renderpasses.get(flow.last())!!
        gl.glBindFramebuffer(GL4.GL_DRAW_FRAMEBUFFER, 0)

        blitFramebuffers(viewportPass.output.values.first(), null,
            OpenGLRenderpass.Rect2D(window.width, window.height, 0, 0),
            OpenGLRenderpass.Rect2D(window.width, window.height, 0, 0))

        // submit to OpenVR if attached
        if(hub?.getWorkingHMDDisplay()?.hasCompositor() == true && !mustRecreateFramebuffers) {
            hub?.getWorkingHMDDisplay()?.wantsVR()?.submitToCompositor(
                viewportPass.output.values.first().getTextureId("Viewport"))
        }

        embedIn?.let { embedPanel ->
            if (shouldClose || mustRecreateFramebuffers) {
                return
            }

            readIndex = (readIndex + 1) % 2
            updateIndex = (readIndex + 1) % 2

            if (pbos[0] == 0 || pbos[1] == 0 || mustRecreateFramebuffers) {
                gl.glGenBuffers(2, pbos, 0)

                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[0])
                gl.glBufferData(GL4.GL_PIXEL_PACK_BUFFER, window.width * window.height * 4L, null, GL4.GL_STREAM_READ)

                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[1])
                gl.glBufferData(GL4.GL_PIXEL_PACK_BUFFER, window.width * window.height * 4L, null, GL4.GL_STREAM_READ)

                gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0)
            }

            gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[readIndex])

            gl.glReadBuffer(GL4.GL_FRONT)
            gl.glReadPixels(0, 0, window.width, window.height, GL4.GL_BGRA, GL4.GL_UNSIGNED_BYTE, 0)

            gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, pbos[updateIndex])

            val buffer = gl.glMapBuffer(GL4.GL_PIXEL_PACK_BUFFER, GL4.GL_READ_ONLY)
            if (buffer != null) {
                Platform.runLater {
                    if (!mustRecreateFramebuffers) embedPanel.update(buffer)
                }
                gl.glUnmapBuffer(GL4.GL_PIXEL_PACK_BUFFER)
            }

            gl.glBindBuffer(GL4.GL_PIXEL_PACK_BUFFER, 0)

            resizeHandler.queryResize()
        }

        if (screenshotRequested && joglDrawable != null) {
            try {
                val readBufferUtil = AWTGLReadBufferUtil(joglDrawable!!.glProfile, false)
                val image = readBufferUtil.readPixelsToBufferedImage(gl, true)
                val file = File(System.getProperty("user.home"), "Desktop" + File.separator + "$applicationName - ${SimpleDateFormat("yyyy-MM-dd HH.mm.ss").format(Date())}.png")

                ImageIO.write(image, "png", file)
                logger.info("Screenshot saved to ${file.absolutePath}")
            } catch (e: Exception) {
                System.err.println("Unable to take screenshot: ")
                e.printStackTrace()
            }

            screenshotRequested = false
        }
    }

    /**
     * Renders a fullscreen quad, using from an on-the-fly generated
     * Node that is saved in [nodeStore], with the [GLProgram]'s ID.
     *
     * @param[program] The [GLProgram] to draw into the fullscreen quad.
     */
    fun renderFullscreenQuad(program: OpenGLShaderProgram) {
        val quad: Node
        val quadName = "fullscreenQuad-${program.id}"

        if (!nodeStore.containsKey(quadName)) {
            quad = Plane(GLVector(1.0f, 1.0f, 0.0f))

            quad.metadata.put("OpenGLRenderer", OpenGLObjectState())
            initializeNode(quad)

            nodeStore.put(quadName, quad)
        } else {
            quad = nodeStore[quadName]!!
        }

        drawNode(quad)
        program.gl.glBindTexture(GL4.GL_TEXTURE_2D, 0)
    }

    /**
     * Initializes a given [Node].
     *
     * This function initializes a Node, equipping its metadata with an [OpenGLObjectState],
     * generating VAOs and VBOs. If the Node has a [Material] assigned, a [GLProgram] fitting
     * this Material will be used. Else, a default GLProgram will be used.
     *
     * For the assigned Material case, the GLProgram is derived either from the class name of the
     * Node (if useClassDerivedShader is set), or from a set [ShaderMaterial] which may define
     * the whole shader pipeline for the Node.
     *
     * If the [Node] implements [HasGeometry], it's geometry is also initialized by this function.
     *
     * @param[node]: The [Node] to initialise.
     * @return True if the initialisation went alright, False if it failed.
     */
    fun initializeNode(node: Node): Boolean {
        val s: OpenGLObjectState

        if (node.instanceOf == null) {
            s = node.metadata["OpenGLRenderer"] as OpenGLObjectState
        } else {
            s = node.instanceOf!!.metadata["OpenGLRenderer"] as OpenGLObjectState
            node.metadata["OpenGLRenderer"] = s

            if (!s.initialized) {
                logger.trace("Instance not yet initialized, doing now...")
                initializeNode(node.instanceOf!!)
            }

            if (!s.additionalBufferIds.containsKey("Model") || !s.additionalBufferIds.containsKey("ModelView") || !s.additionalBufferIds.containsKey("MVP")) {
                logger.trace("${node.name} triggered instance buffer creation")
                createInstanceBuffer(node.instanceOf!!)
                logger.trace("---")
            }
            return true
        }

        if (s.initialized) {
            return true
        }

        // generate VAO for attachment of VBO and indices
        gl.glGenVertexArrays(1, s.mVertexArrayObject, 0)

        // generate three VBOs for coords, normals, texcoords
        gl.glGenBuffers(3, s.mVertexBuffers, 0)
        gl.glGenBuffers(1, s.mIndexBuffer, 0)

        if (node.useClassDerivedShader) {
            val javaClass = node.javaClass.simpleName
            val className = javaClass.substring(javaClass.indexOf(".") + 1)

            val shaders = arrayOf(".vert", ".geom", ".tese", ".tesc", ".frag", ".comp")
                .map { "$className$it" }
                .filter {
                    Renderer::class.java.getResource("shaders/$it") != null
                }

            s.shader = prepareShaderProgram(Renderer::class.java, shaders.toTypedArray())
        } else if (node.material is ShaderMaterial) {
            s.shader = prepareShaderProgram(node.javaClass, (node.material as ShaderMaterial).shaders.toTypedArray())
        } else {
            s.shader = null
        }

        if (node is HasGeometry) {
            node.lock.tryLock(100, TimeUnit.MILLISECONDS)
            if (node.lock.tryLock()) {
                setVerticesAndCreateBufferForNode(node)
                setNormalsAndCreateBufferForNode(node)

                if (node.texcoords.limit() > 0) {
                    setTextureCoordsAndCreateBufferForNode(node)
                }

                if (node.indices.limit() > 0) {
                    setIndicesAndCreateBufferForNode(node)
                }

                node.lock.unlock()
            }
        }

        val matricesUbo = OpenGLUBO(backingBuffer = buffers["UBOBuffer"])
        with(matricesUbo) {
            name = "Matrices"
            add("ModelMatrix", { node.world })
            add("NormalMatrix", { node.world.inverse.transpose() })
            add("ProjectionMatrix", { node.projection })
            add("isBillboard", { node.isBillboard.toInt() })

            sceneUBOs.add(node)

            s.UBOs.put("Matrices", this)
        }


        loadTexturesForNode(node, s)

        val materialUbo = OpenGLUBO(backingBuffer = buffers["UBOBuffer"])
        var materialType = 0

        if (node.material.textures.containsKey("ambient") && !s.defaultTexturesFor.contains("ambient")) {
            materialType = materialType or MATERIAL_HAS_AMBIENT
        }

        if (node.material.textures.containsKey("diffuse") && !s.defaultTexturesFor.contains("diffuse")) {
            materialType = materialType or MATERIAL_HAS_DIFFUSE
        }

        if (node.material.textures.containsKey("specular") && !s.defaultTexturesFor.contains("specular")) {
            materialType = materialType or MATERIAL_HAS_SPECULAR
        }

        if (node.material.textures.containsKey("normal") && !s.defaultTexturesFor.contains("normal")) {
            materialType = materialType or MATERIAL_HAS_NORMAL
        }

        if (node.material.textures.containsKey("alphamask") && !s.defaultTexturesFor.contains("alphamask")) {
            materialType = materialType or MATERIAL_HAS_ALPHAMASK
        }

        with(materialUbo) {
            name = "MaterialProperties"
            add("materialType", { materialType })
            add("Ka", { node.material.ambient })
            add("Kd", { node.material.diffuse })
            add("Ks", { node.material.specular })
            add("Shininess", { node.material.specularExponent })
            add("Opacity", { node.material.opacity })

            s.UBOs.put("MaterialProperties", this)
        }

        if (node.javaClass.declaredFields.filter { it.isAnnotationPresent(ShaderProperty::class.java) }.count() > 0) {
            val shaderPropertyUBO = OpenGLUBO(backingBuffer = buffers["ShaderPropertyBuffer"])
            with(shaderPropertyUBO) {
                name = "ShaderProperties"

                if (node.useClassDerivedShader || node.material is ShaderMaterial) {
                    logger.info("Shader properties are: ${s.shader?.getShaderPropertyOrder()}")
                    s.shader?.getShaderPropertyOrder()?.forEach { name, offset ->
                        add(name, { node.getShaderProperty(name)!! }, offset)
                    }
                }
            }

            s.UBOs.put("ShaderProperties", shaderPropertyUBO)
        }

        s.initialized = true
        node.initialized = true
        node.metadata[this.javaClass.simpleName] = s

        s.initialized = true
        return true
    }

    /**
     * Parallel forEach implementation for HashMaps.
     *
     * @param[maxThreads] Maximum number of parallel threads
     * @param[action] Lambda containing the action to be executed for each key, value pair.
     */
    @Suppress("unused")
    fun <K, V> HashMap<K, V>.forEachParallel(maxThreads: Int = 5, action: ((K, V) -> Unit)) {
        val iterator = this.asSequence().iterator()
        var threadCount = 0

        while (iterator.hasNext()) {
            val current = iterator.next()

            thread {
                threadCount++
                while (threadCount > maxThreads) {
                    Thread.sleep(50)
                }

                action.invoke(current.key, current.value)
                threadCount--
            }
        }
    }

    /**
     * Initializes a default set of textures that the renderer can fall back to and provide a non-intrusive
     * hint to the user that a texture could not be loaded.
     */
    private fun prepareDefaultTextures() {
        val t = GLTexture.loadFromFile(gl, Renderer::class.java.getResourceAsStream("DefaultTexture.png"), "png", false, true, 8)
        if(t == null) {
            logger.error("Could not load default texture! This indicates a serious issue.")
        } else {
            textureCache.put("DefaultTexture", t)
        }
    }

    /**
     * Loads textures for a [Node]. The textures either come from a [Material.transferTextures] buffer,
     * or from a file. This is indicated by stating fromBuffer:bufferName in the textures hash map.
     *
     * @param[node] The [Node] to load textures for.
     * @param[s] The [Node]'s [OpenGLObjectState]
     */
    @Suppress("USELESS_ELVIS")
    private fun loadTexturesForNode(node: Node, s: OpenGLObjectState): OpenGLObjectState {
        if (node.lock.tryLock()) {
            node.material.textures.forEach {
                type, texture ->
                if (!textureCache.containsKey(texture) || node.material.needsTextureReload) {
                    logger.debug("Loading texture $texture for ${node.name}")

                    val generateMipmaps = (type == "ambient" || type == "diffuse" || type == "specular")
                    if (texture.startsWith("fromBuffer:")) {
                        node.material.transferTextures[texture.substringAfter("fromBuffer:")]?.let {
                            (_, dimensions, channels, type1, contents, repeatS, repeatT) ->

                            logger.debug("Dims of $texture: $dimensions, mipmaps=$generateMipmaps")

                            val miplevels = if (generateMipmaps && dimensions.z().toInt() == 1) {
                                1 + Math.floor(Math.log(Math.max(dimensions.x() * 1.0, dimensions.y() * 1.0)) / Math.log(2.0)).toInt()
                            } else {
                                1
                            }

                            // TODO: Add support for more than one channel for volumes
                            val channelCount = if (dimensions.z().toInt() == 1) {
                                channels
                            } else {
                                1
                            }

                            val t = GLTexture(gl, type1, channelCount,
                                dimensions.x().toInt(),
                                dimensions.y().toInt(),
                                dimensions.z().toInt() ?: 1,
                                dimensions.z().toInt() == 1,
                                miplevels)

                            if (generateMipmaps) {
                                t.updateMipMaps()
                            }

                            t.setClamp(!repeatS, !repeatT)
                            logger.debug("Copying data from buffer: ${contents.remaining()}")
                            t.copyFrom(contents)

                            s.textures.put(type, t)
                            textureCache.put(texture, t)
                        }
                    } else {
                        val glTexture = if(texture.contains("jar!")) {
                            val f = texture.substringAfterLast("!")
                            val stream = node.javaClass.getResourceAsStream(f)

                            if(stream == null) {
                                logger.error("Texture not found for $node: $f (from JAR)")
                                textureCache["DefaultTexture"]!!
                            } else {
                                GLTexture.loadFromFile(gl, stream, texture.substringAfterLast("."), true, generateMipmaps, 8)
                            }
                        } else {
                            try {
                                GLTexture.loadFromFile(gl, texture, true, generateMipmaps, 8)
                            } catch(e: FileNotFoundException) {
                                logger.error("Texture not found for $node: $texture")
                                textureCache["DefaultTexture"]!!
                            }
                        }

                        s.textures.put(type, glTexture)
                        textureCache.put(texture, glTexture)
                    }
                } else {
                    s.textures.put(type, textureCache[texture]!!)
                }
            }

            arrayOf("ambient", "diffuse", "specular", "normal", "alphamask", "displacement").forEach {
                if (!s.textures.containsKey(it)) {
//                    s.textures.putIfAbsent(it, textureCache["DefaultTexture"]!!)
                    s.defaultTexturesFor.add(it)
                }
            }

            node.material.needsTextureReload = false
            s.initialized = true
            node.lock.unlock()
            return s
        } else {
            return s
        }
    }

    /**
     * Reshape the renderer's viewports
     *
     * This routine is called when a change in window size is detected, e.g. when resizing
     * it manually or toggling fullscreen. This function updates the sizes of all used
     * geometry buffers and will also create new buffers in case vr.Active is changed.
     *
     * This function also clears color and depth buffer bits.
     *
     * @param[newWidth] The resized window's width
     * @param[newHeight] The resized window's height
     */
    override fun reshape(newWidth: Int, newHeight: Int) {
        if (!initialized) {
            return
        }

        lastResizeTimer.cancel()

        lastResizeTimer = Timer()
        lastResizeTimer.schedule(object : TimerTask() {
            override fun run() {
                window.width = newWidth
                window.height = newHeight

                mustRecreateFramebuffers = true
            }
        }, WINDOW_RESIZE_TIMEOUT)
    }

    /**
     * Creates an instance buffer for a [Node]'s model, view and mvp matrices.
     *
     * @param[node] The [Node] to create the instance buffer for.
     */
    private fun createInstanceBuffer(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)

        val matrixSize = 4 * 4
        val vectorSize = 4
        val locationBase = 3
        val matrices = arrayOf("Model", "ModelView", "MVP")
        val i = IntArray(matrices.size)

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glGenBuffers(matrices.size, i, 0)

        i.forEachIndexed { locationOffset, bufferId ->
            s.additionalBufferIds.put(matrices[locationOffset], bufferId)

            gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, bufferId)

            for (offset in 0..3) {
                val l = locationBase + locationOffset * vectorSize + offset

                val pointerOffsetBytes: Long = 1L * Buffers.SIZEOF_FLOAT * offset * vectorSize
                val matrixSizeBytes = matrixSize * Buffers.SIZEOF_FLOAT

                gl.glEnableVertexAttribArray(l)
                gl.glVertexAttribPointer(l, vectorSize, GL4.GL_FLOAT, false,
                    matrixSizeBytes, pointerOffsetBytes)
                gl.glVertexAttribDivisor(l, 1)
            }
        }

        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
        gl.glBindVertexArray(0)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s vertices.
     *
     * @param[node] The [Node] to create the VAO/VBO for.
     */
    fun setVerticesAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pVertexBuffer: FloatBuffer = (node as HasGeometry).vertices

        s.mStoredPrimitiveCount = pVertexBuffer.remaining() / node.vertexSize

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[0])

        gl.glEnableVertexAttribArray(0)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pVertexBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pVertexBuffer,
            if (s.isDynamic)
                GL4.GL_DYNAMIC_DRAW
            else
                GL4.GL_STATIC_DRAW)

        gl.glVertexAttribPointer(0,
            node.vertexSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a [Node]'s vertices.
     *
     * @param[node] The [Node] to update the vertices for.
     */
    fun updateVertices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pVertexBuffer: FloatBuffer = (node as HasGeometry).vertices

        s.mStoredPrimitiveCount = pVertexBuffer.remaining() / node.vertexSize

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[0])

        gl.glEnableVertexAttribArray(0)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pVertexBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pVertexBuffer,
            GL4.GL_DYNAMIC_DRAW)

        gl.glVertexAttribPointer(0,
            node.vertexSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s normals.
     *
     * @param[node] The [Node] to create the normals VBO for.
     */
    fun setNormalsAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pNormalBuffer: FloatBuffer = (node as HasGeometry).normals

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        if (pNormalBuffer.limit() > 0) {
            gl.glEnableVertexAttribArray(1)
            gl.glBufferData(GL4.GL_ARRAY_BUFFER,
                (pNormalBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
                pNormalBuffer,
                if (s.isDynamic)
                    GL4.GL_DYNAMIC_DRAW
                else
                    GL4.GL_STATIC_DRAW)

            gl.glVertexAttribPointer(1,
                node.vertexSize,
                GL4.GL_FLOAT,
                false,
                0,
                0)

        }
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a given [Node]'s normals.
     *
     * @param[node] The [Node] whose normals need updating.
     */
    fun updateNormals(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pNormalBuffer: FloatBuffer = (node as HasGeometry).normals

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[1])

        gl.glEnableVertexAttribArray(1)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pNormalBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pNormalBuffer,
            if (s.isDynamic)
                GL4.GL_DYNAMIC_DRAW
            else
                GL4.GL_STATIC_DRAW)

        gl.glVertexAttribPointer(1,
            node.vertexSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates VAOs and VBO for a given [Node]'s texcoords.
     *
     * @param[node] The [Node] to create the texcoord VBO for.
     */
    fun setTextureCoordsAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pTextureCoordsBuffer: FloatBuffer = (node as HasGeometry).texcoords

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, s.mVertexBuffers[2])

        gl.glEnableVertexAttribArray(2)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pTextureCoordsBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pTextureCoordsBuffer,
            if (s.isDynamic)
                GL4.GL_DYNAMIC_DRAW
            else
                GL4.GL_STATIC_DRAW)

        gl.glVertexAttribPointer(2,
            node.texcoordSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a given [Node]'s texcoords.
     *
     * @param[node] The [Node] whose texcoords need updating.
     */
    fun updateTextureCoords(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pTextureCoordsBuffer: FloatBuffer = (node as HasGeometry).texcoords

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER,
            s.mVertexBuffers[2])

        gl.glEnableVertexAttribArray(2)
        gl.glBufferData(GL4.GL_ARRAY_BUFFER,
            (pTextureCoordsBuffer.remaining() * (java.lang.Float.SIZE / java.lang.Byte.SIZE)).toLong(),
            pTextureCoordsBuffer,
            if (s.isDynamic)
                GL4.GL_DYNAMIC_DRAW
            else
                GL4.GL_STATIC_DRAW)

        gl.glVertexAttribPointer(2,
            node.texcoordSize,
            GL4.GL_FLOAT,
            false,
            0,
            0)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Creates a index buffer for a given [Node]'s indices.
     *
     * @param[node] The [Node] to create the index buffer for.
     */
    fun setIndicesAndCreateBufferForNode(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pIndexBuffer: IntBuffer = (node as HasGeometry).indices

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferData(GL4.GL_ELEMENT_ARRAY_BUFFER,
            (pIndexBuffer.remaining() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
            pIndexBuffer,
            if (s.isDynamic)
                GL4.GL_DYNAMIC_DRAW
            else
                GL4.GL_STATIC_DRAW)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /**
     * Updates a given [Node]'s indices.
     *
     * @param[node] The [Node] whose indices need updating.
     */
    fun updateIndices(node: Node) {
        val s = getOpenGLObjectStateFromNode(node)
        val pIndexBuffer: IntBuffer = (node as HasGeometry).indices

        s.mStoredIndexCount = pIndexBuffer.remaining()

        gl.glBindVertexArray(s.mVertexArrayObject[0])
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, s.mIndexBuffer[0])

        gl.glBufferSubData(GL4.GL_ELEMENT_ARRAY_BUFFER,
            0,
            (pIndexBuffer.remaining() * (Integer.SIZE / java.lang.Byte.SIZE)).toLong(),
            pIndexBuffer)

        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /**
     * Draws a given [Node], either in element or in index draw mode.
     *
     * @param[node] The node to be drawn.
     * @param[offset] offset in the array or index buffer.
     */
    fun drawNode(node: Node, offset: Int = 0) {
        val s = getOpenGLObjectStateFromNode(node)

        if (s.mStoredIndexCount == 0 && s.mStoredPrimitiveCount == 0 || node.material.needsTextureReload) {
            return
        }
        logger.trace("Drawing {} with {}", node.name, s.shader?.modules?.entries?.joinToString(", "))
        gl.glBindVertexArray(s.mVertexArrayObject[0])

        if (s.mStoredIndexCount > 0) {
            gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER,
                s.mIndexBuffer[0])
            gl.glDrawElements((node as HasGeometry).geometryType.toOpenGLType(),
                s.mStoredIndexCount,
                GL4.GL_UNSIGNED_INT,
                offset.toLong())

            gl.glBindBuffer(GL4.GL_ELEMENT_ARRAY_BUFFER, 0)
        } else {
            gl.glDrawArrays((node as HasGeometry).geometryType.toOpenGLType(), offset, s.mStoredPrimitiveCount)
        }

        gl.glUseProgram(0)
        gl.glBindVertexArray(0)
    }

    /**
     * Draws a given instanced [Node] either in element or in index draw mode.
     *
     * @param[node] The node to be drawn.
     * @param[count] The number of instances to be drawn.
     * @param[offset] offset in the array or index buffer.
     */
    fun drawNodeInstanced(node: Node, count: Int, offset: Long = 0) {
        val s = getOpenGLObjectStateFromNode(node)

//        s.program?.use(gl)

        gl.glBindVertexArray(s.mVertexArrayObject[0])

        if (s.mStoredIndexCount > 0) {
            gl.glDrawElementsInstanced(
                (node as HasGeometry).geometryType.toOpenGLType(),
                s.mStoredIndexCount,
                GL4.GL_UNSIGNED_INT,
                offset,
                count)
        } else {
            gl.glDrawArraysInstanced(
                (node as HasGeometry).geometryType.toOpenGLType(),
                0, s.mStoredPrimitiveCount, count)

        }

        gl.glUseProgram(0)
        gl.glBindVertexArray(0)
    }

    override fun screenshot() {
        screenshotRequested = true
    }

    private fun RenderConfigReader.BlendFactor.toOpenGL() = when (this) {
        RenderConfigReader.BlendFactor.Zero -> GL4.GL_ZERO
        RenderConfigReader.BlendFactor.One -> GL4.GL_ONE
        RenderConfigReader.BlendFactor.OneMinusSrcAlpha -> GL4.GL_ONE_MINUS_SRC_ALPHA
        RenderConfigReader.BlendFactor.SrcAlpha -> GL4.GL_SRC_ALPHA
    }

    private fun RenderConfigReader.BlendOp.toOpenGL() = when (this) {
        RenderConfigReader.BlendOp.add -> GL4.GL_FUNC_ADD
        RenderConfigReader.BlendOp.subtract -> GL4.GL_FUNC_SUBTRACT
        RenderConfigReader.BlendOp.min -> GL4.GL_MIN
        RenderConfigReader.BlendOp.max -> GL4.GL_MAX
        RenderConfigReader.BlendOp.reverse_subtract -> GL4.GL_FUNC_REVERSE_SUBTRACT
    }

    override fun close() {
        libspirvcrossj.finalizeProcess()
    }
}
