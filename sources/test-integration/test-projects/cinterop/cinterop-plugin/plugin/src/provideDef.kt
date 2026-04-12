import org.jetbrains.amper.plugins.*
import java.nio.file.Path
import kotlin.io.path.*

@Configurable
interface Settings {
    val packageName: String
    val code: String
    val targetFragment: String get() = ""
}

@TaskAction
fun provideDef(
    settings: Settings,
    @Output defFile: Path,
) {
    defFile.createParentDirectories()
    defFile.writeText("""
        package = ${settings.packageName}
        ---

    """.trimIndent() + settings.code)
}
