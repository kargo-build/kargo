import com.intellij.openapi.roots.ContentEntry

fun test(entry: ContentEntry) {
    entry.addExcludePattern("*.log")
}
