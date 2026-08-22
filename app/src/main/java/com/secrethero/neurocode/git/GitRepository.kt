package com.secrethero.neurocode.git

import com.secrethero.neurocode.data.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File

data class GitStatus(
    val branch: String,
    val clean: Boolean,
    val added: Set<String>,
    val changed: Set<String>,
    val modified: Set<String>,
    val missing: Set<String>,
    val removed: Set<String>,
    val untracked: Set<String>,
    val conflicting: Set<String>,
)

data class GitCommitInfo(
    val hash: String,
    val shortHash: String,
    val author: String,
    val message: String,
    val timestamp: Long,
)

class GitRepository(private val projects: ProjectRepository) {
    suspend fun init(projectId: String): GitStatus = withContext(Dispatchers.IO) {
        val root = projects.resolve(projectId, "")
        if (!root.resolve(".git").exists()) {
            Git.init().setDirectory(root).call().use { git ->
                val ignore = root.resolve(".gitignore")
                if (!ignore.exists()) {
                    ignore.writeText(".neurocode/\nbuild/\n.gradle/\n.idea/\n*.apk\n")
                }
                git.add().addFilepattern(".gitignore").call()
            }
        }
        statusBlocking(projectId)
    }

    suspend fun status(projectId: String): GitStatus = withContext(Dispatchers.IO) {
        statusBlocking(projectId)
    }

    suspend fun diff(projectId: String, staged: Boolean = false): String =
        withContext(Dispatchers.IO) {
            open(projectId).use { git ->
                val entries = git.diff().setCached(staged).call()
                val buffer = ByteArrayOutputStream()
                DiffFormatter(buffer).use { formatter ->
                    formatter.setRepository(git.repository)
                    entries.forEach(formatter::format)
                }
                buffer.toString(Charsets.UTF_8.name())
            }
        }

    suspend fun addAll(projectId: String) = withContext(Dispatchers.IO) {
        open(projectId).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
        }
    }

    suspend fun commit(
        projectId: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): GitCommitInfo = withContext(Dispatchers.IO) {
        require(message.isNotBlank()) { "Введите текст коммита" }
        open(projectId).use { git ->
            val commit = git.commit()
                .setMessage(message)
                .setAuthor(authorName.ifBlank { "NeuroCode User" }, authorEmail.ifBlank { "user@localhost" })
                .call()
            GitCommitInfo(
                hash = commit.name,
                shortHash = commit.abbreviate(8).name(),
                author = commit.authorIdent.name,
                message = commit.shortMessage,
                timestamp = commit.commitTime.toLong() * 1_000,
            )
        }
    }

    suspend fun log(projectId: String, limit: Int = 50): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            open(projectId).use { git ->
                runCatching { git.log().setMaxCount(limit).call() }
                    .getOrDefault(emptyList())
                    .map { commit ->
                        GitCommitInfo(
                            hash = commit.name,
                            shortHash = commit.abbreviate(8).name(),
                            author = commit.authorIdent.name,
                            message = commit.shortMessage,
                            timestamp = commit.commitTime.toLong() * 1_000,
                        )
                    }
            }
        }

    suspend fun pull(projectId: String, username: String, token: String): String =
        withContext(Dispatchers.IO) {
            open(projectId).use { git ->
                val branch = currentBranch(git)
                val result = git.pull()
                    .setRemote(REMOTE_NAME)
                    .setRemoteBranchName(branch)
                    .setCredentialsProvider(credentials(username, token))
                    .call()
                result.fetchResult.messages().joinToString("\n")
                    .ifBlank { result.mergeResult?.mergeStatus?.toString() ?: "Обновлено" }
            }
        }

    suspend fun push(projectId: String, username: String, token: String): String =
        withContext(Dispatchers.IO) {
            open(projectId).use { git ->
                val branch = currentBranch(git)
                val results = git.push()
                    .setRemote(REMOTE_NAME)
                    .setRefSpecs(RefSpec("HEAD:refs/heads/$branch"))
                    .setCredentialsProvider(credentials(username, token))
                    .call()
                results.joinToString("\n") { result ->
                    result.remoteUpdates.joinToString(", ") { update ->
                        "${update.remoteName}: ${update.message ?: "отправлено"}"
                    }
                }.ifBlank { "Отправлено" }
            }
        }

    suspend fun clone(url: String, directory: File, username: String, token: String) {
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Поддерживаются только HTTPS-адреса remote" }
            val command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(directory)
            if (token.isNotBlank()) {
                command.setCredentialsProvider(credentials(username, token))
            }
            command.call().use { }
        }
    }

    suspend fun setRemoteUrl(projectId: String, url: String) = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Поддерживаются только HTTPS-адреса remote" }
        open(projectId).use { git ->
            val uri = URIish(url.trim())
            if (git.remoteList().call().any { it.name == REMOTE_NAME }) {
                git.remoteSetUrl().setRemoteName(REMOTE_NAME).setUri(uri).call()
            } else {
                git.remoteAdd().setRemoteName(REMOTE_NAME).setUri(uri).call()
            }
        }
    }

    suspend fun remoteUrl(projectId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            open(projectId).use { git ->
                git.repository.config.getString("remote", REMOTE_NAME, "url")
            }
        }.getOrNull()
    }

    private fun currentBranch(git: Git): String =
        runCatching { git.repository.branch }.getOrDefault(Constants.MASTER)

    private fun statusBlocking(projectId: String): GitStatus {
        val root = projects.resolve(projectId, "")
        require(root.resolve(".git").isDirectory) { "Git ещё не инициализирован" }
        return Git.open(root).use { git ->
            val status = git.status().call()
            GitStatus(
                branch = runCatching { git.repository.branch }.getOrDefault(Constants.HEAD),
                clean = status.isClean,
                added = status.added,
                changed = status.changed,
                modified = status.modified,
                missing = status.missing,
                removed = status.removed,
                untracked = status.untracked,
                conflicting = status.conflicting,
            )
        }
    }

    private fun open(projectId: String): Git =
        Git.open(projects.resolve(projectId, ""))

    private fun credentials(username: String, token: String) =
        UsernamePasswordCredentialsProvider(username.ifBlank { "git" }, token)

    companion object {
        private const val REMOTE_NAME = "origin"
    }
}
