package com.secrethero.neurocode.data

import java.io.File

object PathGuard {

    fun resolveWithin(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, relativePath.trimStart('/')).canonicalFile
        require(
            file == canonicalRoot ||
                file.path.startsWith(canonicalRoot.path + File.separator),
        ) {
            "Путь выходит за пределы проекта"
        }
        return file
    }
}
