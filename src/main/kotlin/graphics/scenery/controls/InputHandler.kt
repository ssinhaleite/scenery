package graphics.scenery.controls

import graphics.scenery.Hub
import graphics.scenery.Hubable
import graphics.scenery.Scene
import graphics.scenery.SceneryElement
import graphics.scenery.backends.Renderer
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.controls.behaviours.*
import graphics.scenery.utils.LazyLogger
import net.java.games.input.Component
import org.lwjgl.glfw.GLFW.*
import org.scijava.ui.behaviour.Behaviour
import org.scijava.ui.behaviour.BehaviourMap
import org.scijava.ui.behaviour.InputTriggerMap
import org.scijava.ui.behaviour.io.InputTriggerConfig
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.Reader
import java.io.StringReader

/**
 * Input orchestrator for ClearGL windows
 *
 * @author Ulrik Günther <hello@ulrik.is>
 * @property[scene] The currently displayed scene
 * @property[renderer] The active renderer
 * @property[window] The window the renderer is displaying to
 * @property[hub] [Hub] for handoing communication
 * @constructor Creates a default behaviour list and input map, also reads the configuration from a file.
 */
class InputHandler(scene: Scene, renderer: Renderer, override var hub: Hub?) : Hubable, AutoCloseable {
    /** logger for the InputHandler **/
    internal val logger by LazyLogger()
    /** ui-behaviour input trigger map, stores what actions (key presses, etc) trigger which actions. */
    internal val inputMap = InputTriggerMap()
    /** ui-behaviour behaviour map, stores the available behaviours */
    internal val behaviourMap = BehaviourMap()
    /** JOGL-flavoured ui-behaviour MouseAndKeyHandlerBase */
    internal val handler: MouseAndKeyHandlerBase?

    /** Scene the input handler refers to */
    internal val scene: Scene
    /** Renderer the input handler uses */
    internal val renderer: Renderer
    /** window the input handler receives input events from */
    internal val window: SceneryWindow = renderer.window

    /** configuration of the input triggers */
    internal var config: InputTriggerConfig = InputTriggerConfig()

    init {

        when(window) {
            is SceneryWindow.ClearGLWindow -> {
                // create Mouse & Keyboard Handler
                handler = JOGLMouseAndKeyHandler(hub)
                handler.setInputMap(inputMap)
                handler.setBehaviourMap(behaviourMap)

                window.window.addKeyListener(handler)
                window.window.addMouseListener(handler)
            }

            is SceneryWindow.GLFWWindow -> {
                handler = GLFWMouseAndKeyHandler(hub)

                handler.setInputMap(inputMap)
                handler.setBehaviourMap(behaviourMap)

                glfwSetCursorPosCallback(window.window, handler.cursorCallback)
                glfwSetKeyCallback(window.window, handler.keyCallback)
                glfwSetScrollCallback(window.window, handler.scrollCallback)
                glfwSetMouseButtonCallback(window.window, handler.mouseCallback)
            }

            is SceneryWindow.JavaFXStage -> {
                handler = JavaFXMouseAndKeyHandler(hub, window.panel)

                handler.setInputMap(inputMap)
                handler.setBehaviourMap(behaviourMap)
            }

            is SceneryWindow.UninitializedWindow -> {
                logger.error("Uninitialized windows cannot have input handlers.")
                handler = null
            }
        }

        this.scene = scene
        this.renderer = renderer
        this.hub = hub

        hub?.add(SceneryElement.Input, this)
    }

    /**
     * Adds a behaviour to the map of behaviours, making them available for key bindings
     *
     * @param[behaviourName] The name of the behaviour
     * @param[behaviour] The behaviour to add.
     */
    fun addBehaviour(behaviourName: String, behaviour: Behaviour) {
        behaviourMap.put(behaviourName, behaviour)
    }

    /**
     * Removes a behaviour from the map of behaviours.
     *
     * @param[behaviourName] The name of the behaviour to remove.
     */
    fun removeBehaviour(behaviourName: String) {
        behaviourMap.remove(behaviourName)
    }

    /**
     * Adds a key binding for a given behaviour
     *
     * @param[behaviourName] The behaviour to add a key binding for
     * @param[keys] Which keys should trigger this behaviour?
     */
    fun addKeyBinding(behaviourName: String, keys: String) {
        config.inputTriggerAdder(inputMap, "all").put(behaviourName, keys)
    }

    /**
     * Removes a key binding for a given behaviour
     *
     * @param[behaviourName] The behaviour to remove the key binding for.
     */
    @Suppress("unused")
    fun removeKeyBinding(behaviourName: String) {
        config.inputTriggerAdder(inputMap, "all").put(behaviourName)
    }

    /**
     * Returns the behaviour with the given name, if it exists. Otherwise null is returned.
     *
     * @param[behaviourName] The name of the behaviour
     */
    fun getBehaviour(behaviourName: String): Behaviour? {
        return behaviourMap.get(behaviourName)
    }

    /**
     * Reads a default list of key bindings from a file, and sets sane
     * defaults for those not set by the config
     *
     * @param[bindingConfigFile] The filename to read the configuration from.
     */
    fun useDefaultBindings(bindingConfigFile: String) {
        // Load YAML config
        var reader: Reader

        try {
            reader = FileReader(bindingConfigFile)
        } catch (e: FileNotFoundException) {
            System.err.println("Falling back to default keybindings...")
            reader = StringReader("---\n" +
                    "- !mapping" + "\n" +
                    "  action: mouse_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [button1, G]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: gamepad_movement_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [button1]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: gamepad_camera_control" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [P]" + "\n" +
                    "- !mapping" + "\n" +
                    "  action: scroll1" + "\n" +
                    "  contexts: [all]" + "\n" +
                    "  triggers: [scroll]" + "\n" +
                    "")
        }

        config = InputTriggerConfig(YamlConfigIO.read(reader))

        /*
     * Create behaviours and input mappings.
     */
        behaviourMap.put("mouse_control", FPSCameraControl("mouse_control", { scene.findObserver() }, window.width, window.height))
        behaviourMap.put("gamepad_camera_control", GamepadCameraControl("gamepad_camera_control", listOf(Component.Identifier.Axis.Z, Component.Identifier.Axis.RZ), { scene.findObserver() }, window.width, window.height))
        behaviourMap.put("gamepad_movement_control", GamepadMovementControl("gamepad_movement_control", listOf(Component.Identifier.Axis.X, Component.Identifier.Axis.Y), { scene.findObserver() }))

        behaviourMap.put("select_command", SelectCommand("select_command", renderer, scene, { scene.findObserver() }))

        behaviourMap.put("move_forward", MovementCommand("move_forward", "forward", { scene.findObserver() }))
        behaviourMap.put("move_back", MovementCommand("move_back", "back", { scene.findObserver() }))
        behaviourMap.put("move_left", MovementCommand("move_left", "left", { scene.findObserver() }))
        behaviourMap.put("move_right", MovementCommand("move_right", "right", { scene.findObserver() }))
        behaviourMap.put("move_up", MovementCommand("move_up", "up", { scene.findObserver() }))
        behaviourMap.put("move_down", MovementCommand("move_down", "down", { scene.findObserver() }))

        behaviourMap.put("move_forward_fast", MovementCommand("move_forward", "forward", { scene.findObserver() }, 0.2f))
        behaviourMap.put("move_back_fast", MovementCommand("move_back", "back", { scene.findObserver() }, 0.2f))
        behaviourMap.put("move_left_fast", MovementCommand("move_left", "left", { scene.findObserver() }, 0.2f))
        behaviourMap.put("move_right_fast", MovementCommand("move_right", "right", { scene.findObserver() }, 0.2f))
        behaviourMap.put("move_up_fast", MovementCommand("move_up", "up", { scene.findObserver() }, 0.2f))
        behaviourMap.put("move_down_fast", MovementCommand("move_down", "down", { scene.findObserver() }, 0.2f))

        behaviourMap.put("toggle_debug", ToggleCommand("toggle_debug", renderer, "toggleDebug"))
        behaviourMap.put("toggle_fullscreen", ToggleCommand("toggle_fullscreen", renderer, "toggleFullscreen"))
        behaviourMap.put("toggle_ssao", ToggleCommand("toggle_ssao", renderer, "toggleSSAO"))
        behaviourMap.put("toggle_hdr", ToggleCommand("toggle_hdr", renderer, "toggleHDR"))
        behaviourMap.put("screenshot", ToggleCommand("screenshot", renderer, "screenshot"))


        behaviourMap.put("increase_exposure", ToggleCommand("increase_exposure", renderer, "increaseExposure"))
        behaviourMap.put("decrease_exposure", ToggleCommand("decrease_exposure", renderer, "decreaseExposure"))
        behaviourMap.put("increase_gamma", ToggleCommand("increase_gamma", renderer, "increaseGamma"))
        behaviourMap.put("decrease_gamma", ToggleCommand("decrease_gamma", renderer, "decreaseGamma"))

        behaviourMap.put("toggle_vr", ToggleCommand("toggle_vr", renderer, "toggleVR"))

        val adder = config.inputTriggerAdder(inputMap, "all")
        adder.put("mouse_control") // put input trigger as defined in config
        adder.put("gamepad_movement_control")
        adder.put("gamepad_camera_control")

        adder.put("select_command", "double-click button1")

        adder.put("move_forward", "W")
        adder.put("move_left", "A")
        adder.put("move_back", "S")
        adder.put("move_right", "D")

        adder.put("move_forward_fast", "shift W")
        adder.put("move_left_fast", "shift A")
        adder.put("move_back_fast", "shift S")
        adder.put("move_right_fast", "shift D")

        adder.put("move_up", "SPACE")
        adder.put("move_down", "shift SPACE")

        adder.put("toggle_debug", "Q")
        adder.put("toggle_fullscreen", "F")
        adder.put("toggle_ssao", "O")
        adder.put("toggle_hdr", "H")

        adder.put("increase_exposure", "K")
        adder.put("decrease_exposure", "L")
        adder.put("increase_gamma", "shift K")
        adder.put("decrease_gamma", "shift L")

        adder.put("screenshot", "P")

        adder.put("toggle_vr", "shift V")
    }

    override fun close() {

    }
}
