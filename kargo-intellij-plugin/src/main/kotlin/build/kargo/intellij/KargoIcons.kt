package build.kargo.intellij

import com.intellij.openapi.util.IconLoader

/** Centralized icon registry for the Kargo IntelliJ plugin. */
object KargoIcons {
    /** The main Kargo icon used in tool windows, project view and run configurations. */
    @JvmField
    val Kargo = IconLoader.getIcon("/icons/kargo.svg", KargoIcons::class.java)

    /** The 32x32 Kargo icon used in the Open Project wizard and plugin landing page. */
    @JvmField
    val Kargo32 = IconLoader.getIcon("/icons/kargo32.svg", KargoIcons::class.java)
}
