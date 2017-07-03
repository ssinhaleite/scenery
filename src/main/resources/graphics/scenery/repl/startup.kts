import org.scijava.`object`.ObjectService
import javax.script.Bindings

// hello this is a comment

val o = (bindings as Bindings)["object"] as ObjectService

//System.err.println(o.getClass())
//System.err.println(bindings.getClass())
val objects = o.getIndex().toArray()

var scene = objects.find { it.toString().contains("scene") }
var renderer = objects.find { it.toString().contains("Renderer") }
var stats = objects.find { it.toString().contains("Statistics") }
var hub = objects.find { it.toString().contains("Hub") }
var settings = objects.find { it.toString().contains("Settings") }
