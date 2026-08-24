package com.secrethero.neurocode.util

data class LanguageSpec(
    val id: String,
    val displayName: String,
    val mode: Mode,
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val stringDelims: List<String> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val useEscape: Boolean = true,
) {
    enum class Mode {
        CODE,
        XML,
        MARKDOWN,
    }
}

object CodeLanguages {

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue",
        "crossinline", "data", "do", "dynamic", "else", "enum", "expect", "external", "false",
        "final", "finally", "for", "fun", "get", "if", "import", "in", "infix", "init", "inline",
        "inner", "interface", "internal", "is", "lateinit", "noinline", "null", "object",
        "operator", "out", "override", "package", "private", "protected", "public", "reified",
        "return", "sealed", "set", "super", "suspend", "tailrec", "this", "throw", "true", "try",
        "typealias", "val", "var", "vararg", "when", "where", "while",
    )

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "true", "false", "try", "void", "volatile",
        "while", "var", "record", "sealed", "yield",
    )

    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True",
        "try", "while", "with", "yield",
    )

    private val JS_KEYWORDS = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "if", "import", "in", "instanceof", "let", "new", "null", "of", "return",
        "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined",
        "var", "void", "while", "with", "yield",
    )

    private val C_KEYWORDS = setOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
        "enum", "extern", "false", "float", "for", "goto", "if", "inline", "int", "long",
        "nullptr", "register", "restrict", "return", "short", "signed", "sizeof", "static",
        "struct", "switch", "typedef", "true", "union", "unsigned", "void", "volatile", "while",
        "class", "namespace", "template", "typename", "public", "private", "protected", "new",
        "delete", "using", "virtual", "override", "constexpr", "auto", "bool", "uint32_t",
        "int32_t", "size_t",
    )

    private val GO_KEYWORDS = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
        "package", "range", "return", "select", "struct", "switch", "type", "var", "nil", "true",
        "false",
    )

    private val RUST_KEYWORDS = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while",
    )

    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "function", "in", "return", "exit", "local", "export", "echo", "cd", "source",
        "set", "unset", "read", "shift", "trap", "eval", "exec",
    )

    private val SQL_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AND", "OR", "NOT", "NULL",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "AS", "DISTINCT", "UNION", "ALL", "EXISTS",
    )

    private fun cLike(id: String, name: String, keywords: Set<String>) = LanguageSpec(
        id = id,
        displayName = name,
        mode = LanguageSpec.Mode.CODE,
        lineComments = listOf("//"),
        blockComments = listOf("/*" to "*/"),
        stringDelims = listOf("\"", "'"),
        keywords = keywords,
    )

    private val specs: Map<String, LanguageSpec> = buildMap {
        fun register(exts: List<String>, spec: LanguageSpec) {
            exts.forEach { put(it, spec) }
        }
        val kotlin = LanguageSpec(
            id = "kotlin",
            displayName = "Kotlin",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("//"),
            blockComments = listOf("/*" to "*/"),
            stringDelims = listOf("\"\"\"", "\"", "'"),
            keywords = KOTLIN_KEYWORDS,
        )
        val python = LanguageSpec(
            id = "python",
            displayName = "Python",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("#"),
            blockComments = listOf("\"\"\"" to "\"\"\"", "'''" to "'''"),
            stringDelims = listOf("\"", "'"),
            keywords = PYTHON_KEYWORDS,
        )
        val markdown = LanguageSpec(
            id = "markdown",
            displayName = "Markdown",
            mode = LanguageSpec.Mode.MARKDOWN,
        )
        val xml = LanguageSpec(
            id = "xml",
            displayName = "XML",
            mode = LanguageSpec.Mode.XML,
            blockComments = listOf("<!--" to "-->"),
        )
        register(listOf("kt", "kts"), kotlin)
        register(listOf("java"), cLike("java", "Java", JAVA_KEYWORDS))
        register(listOf("py", "pyw"), python)
        register(listOf("js", "jsx", "mjs", "cjs"), cLike("js", "JavaScript", JS_KEYWORDS))
        register(listOf("ts", "tsx"), cLike("ts", "TypeScript", JS_KEYWORDS))
        register(listOf("c", "h", "cpp", "cc", "cxx", "hpp", "hh"), cLike("c", "C/C++", C_KEYWORDS))
        register(listOf("go"), cLike("go", "Go", GO_KEYWORDS))
        register(listOf("rs"), cLike("rust", "Rust", RUST_KEYWORDS))
        register(listOf("sh", "bash", "zsh"), LanguageSpec(
            id = "shell",
            displayName = "Shell",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("#"),
            stringDelims = listOf("\"", "'"),
            keywords = SHELL_KEYWORDS,
        ))
        register(listOf("sql"), LanguageSpec(
            id = "sql",
            displayName = "SQL",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("--"),
            blockComments = listOf("/*" to "*/"),
            stringDelims = listOf("'", "\""),
            keywords = SQL_KEYWORDS,
        ))
        register(listOf("yaml", "yml"), LanguageSpec(
            id = "yaml",
            displayName = "YAML",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("#"),
            stringDelims = listOf("\"", "'"),
        ))
        register(listOf("toml", "ini", "properties", "conf", "cfg"), LanguageSpec(
            id = "ini",
            displayName = "Config",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("#", ";"),
            stringDelims = listOf("\"", "'"),
        ))
        register(listOf("json"), LanguageSpec(
            id = "json",
            displayName = "JSON",
            mode = LanguageSpec.Mode.CODE,
            stringDelims = listOf("\""),
            keywords = setOf("true", "false", "null"),
        ))
        register(listOf("xml", "html", "htm", "svg", "plist"), xml)
        register(listOf("gradle", "pro", "cmake"), cLike("script", "Build script", JAVA_KEYWORDS))
        register(listOf("md", "markdown"), markdown)
        register(listOf("css", "scss", "less"), LanguageSpec(
            id = "css",
            displayName = "CSS",
            mode = LanguageSpec.Mode.CODE,
            lineComments = listOf("//"),
            blockComments = listOf("/*" to "*/"),
            stringDelims = listOf("\"", "'"),
        ))
    }

    fun byFileName(fileName: String): LanguageSpec? {
        val lower = fileName.lowercase()
        if (lower == "dockerfile" || lower == "makefile" || lower == "gradlew") {
            return specs["shell"]
        }
        val ext = lower.substringAfterLast('.', "")
        return specs[ext]
    }

    fun displayName(fileName: String): String =
        byFileName(fileName)?.displayName ?: fileName.substringAfterLast('.', "").uppercase()
}
