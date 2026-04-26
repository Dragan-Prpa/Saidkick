package com.example

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.util.Locale

class ProjectReadOnlyContextProvider(
    private val project: Project,
) {
    private val psiManager = PsiManager.getInstance(project)
    private val projectFileIndex = ProjectFileIndex.getInstance(project)

    fun buildContextFor(userInput: String): String {
        val rootPath = project.basePath ?: return "Project context is unavailable."
        if (!project.isInitialized || project.isDisposed) return "Project context is unavailable."

        val normalizedQuery = userInput.lowercase(Locale.getDefault())
        val queryTerms = extractQueryTerms(normalizedQuery)
        val files = discoverProjectFiles()
        val wantsFileListing = asksForFileListing(normalizedQuery)
        val fileSearchQuery = extractFileSearchQuery(normalizedQuery)
        val textSearchQuery = extractTextSearchQuery(userInput)

        val ranked = files
            .map { entry -> entry to scoreFile(entry, queryTerms) }
            .sortedWith(
                compareByDescending<Pair<ProjectFileEntry, Int>> { it.second }
                    .thenBy { it.first.relativePath },
            )

        val relevant = selectTopRelevant(ranked)

        val context = StringBuilder("Read-only project context:\n")
        context.append("Project root: ").append(rootPath.substringAfterLast('/')).append("\n\n")

        if (wantsFileListing || relevant.isEmpty()) {
            appendWithinBudget(context, buildFileListSection(files))
        }

        if (!fileSearchQuery.isNullOrBlank()) {
            appendWithinBudget(context, buildFileSearchSection(files, fileSearchQuery))
        }

        if (!textSearchQuery.isNullOrBlank()) {
            appendWithinBudget(context, buildTextSearchSection(files, textSearchQuery))
        }

        if (relevant.isNotEmpty()) {
            appendWithinBudget(context, buildRelevantSnippetsSection(relevant, queryTerms))
        }

        return context.toString().trimEnd()
    }

    private fun discoverProjectFiles(): List<ProjectFileEntry> {
        val now = System.currentTimeMillis()
        val rootModCount = ProjectRootManager.getInstance(project).modificationCount
        val psiModCount = PsiModificationTracker.getInstance(project).modificationCount
        val cached = fileCache
        if (cached != null && cached.rootModCount == rootModCount && cached.psiModCount == psiModCount && now - cached.createdAt < CACHE_TTL_MS) {
            return cached.files
        }

        val scope = GlobalSearchScope.projectScope(project)
        val collected = linkedSetOf<VirtualFile>()

        ReadAction.run<RuntimeException> {
            ALLOWED_EXTENSIONS
                .filter { it != "env" }
                .forEach { ext ->
                    FilenameIndex.getAllFilesByExt(project, ext, scope)
                        .filterTo(collected) { isEligible(it) }
                }

            FilenameIndex.getVirtualFilesByName(project, ".env", scope)
                .filterTo(collected) { isEligible(it) }

            val allTextTypes = FileTypeManager.getInstance().registeredFileTypes
            allTextTypes.forEach { fileType ->
                FileTypeIndex.processFiles(fileType, { vf: VirtualFile ->
                    if (isEligible(vf) && isAllowedByName(vf.name)) {
                        collected += vf
                    }
                    true
                }, scope)
            }
        }

        val files = collected
            .asSequence()
            .map { vf -> buildFileEntry(vf) }
            .sortedBy { it.relativePath }
            .toList()

        fileCache = CachedProjectFiles(
            files = files,
            rootModCount = rootModCount,
            psiModCount = psiModCount,
            createdAt = now,
        )

        return files
    }

    private fun isEligible(file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false
        if (!projectFileIndex.isInContent(file)) return false

        val relative = project.basePath
            ?.let { base -> file.path.removePrefix(base).trimStart('/') }
            ?.replace('\\', '/')
            .orEmpty()
            .lowercase(Locale.getDefault())

        if (EXCLUDED_PREFIXES.any { relative == it.removeSuffix("/") || relative.startsWith(it) }) {
            return false
        }

        return isAllowedByName(file.name)
    }

    private fun isAllowedByName(name: String): Boolean {
        if (name == ".env") return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return ext in ALLOWED_EXTENSIONS
    }

    private fun asksForFileListing(userInput: String): Boolean {
        return listOf("list files", "show files", "project files", "file tree", "folders", "structure").any { it in userInput }
    }

    private fun extractFileSearchQuery(userInput: String): String? {
        val markers = listOf("search files for", "find file", "search file", "filename contains")
        val marker = markers.firstOrNull { it in userInput } ?: return null
        val raw = userInput.substringAfter(marker, "").trim().trim('"', '\'', '`')
        return raw.takeIf { it.length >= 2 }
    }

    private fun extractTextSearchQuery(userInput: String): String? {
        val normalized = userInput.lowercase(Locale.getDefault())
        val markers = listOf("search text", "find text", "contains text", "grep")
        val marker = markers.firstOrNull { it in normalized } ?: return null

        val markerStart = normalized.indexOf(marker)
        if (markerStart < 0) return null
        val markerEnd = markerStart + marker.length
        val raw = userInput.substring(markerEnd).trim().trim('"', '\'', '`')
        return raw.takeIf { it.length >= 2 }
    }

    private fun extractQueryTerms(userInput: String): Set<String> {
        return userInput
            .split(Regex("[^a-z0-9_./-]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun buildFileEntry(file: VirtualFile): ProjectFileEntry {
        val relativePath = project.basePath
            ?.let { file.path.removePrefix(it).trimStart('/') }
            ?.replace('\\', '/')
            .orEmpty()
        val lowerRelative = relativePath.lowercase(Locale.getDefault())
        val ext = file.extension?.lowercase(Locale.getDefault()).orEmpty()

        val symbols = mutableSetOf<String>()
        var hasClass = false
        var hasFunction = false

        ReadAction.run<RuntimeException> {
            val psiFile = psiManager.findFile(file) ?: return@run
            when {
                ext == "java" -> psiFile.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitClass(aClass: PsiClass) {
                        hasClass = true
                        aClass.name?.let { symbols += it.lowercase(Locale.getDefault()) }
                        super.visitClass(aClass)
                    }

                    override fun visitMethod(method: PsiMethod) {
                        hasFunction = true
                        method.name.lowercase(Locale.getDefault()).let { symbols += it }
                        super.visitMethod(method)
                    }
                })

                ext == "kt" || ext == "kts" -> psiFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitClass(klass: KtClass) {
                        hasClass = true
                        klass.name?.let { symbols += it.lowercase(Locale.getDefault()) }
                        super.visitClass(klass)
                    }

                    override fun visitNamedFunction(function: KtNamedFunction) {
                        hasFunction = true
                        function.name?.lowercase(Locale.getDefault())?.let { symbols += it }
                        super.visitNamedFunction(function)
                    }
                })
            }
        }

        return ProjectFileEntry(
            virtualFile = file,
            relativePath = relativePath,
            lowerRelativePath = lowerRelative,
            extension = ext,
            symbolNames = symbols,
            hasClass = hasClass,
            hasFunction = hasFunction,
        )
    }

    private fun scoreFile(entry: ProjectFileEntry, queryTerms: Set<String>): Int {
        var score = 0

        if (entry.lowerRelativePath.contains("src/main/")) score += 40
        if (entry.extension == "kt" || entry.extension == "java") score += 25
        if (entry.hasClass) score += 10
        if (entry.hasFunction) score += 8

        if (entry.lowerRelativePath.contains("controller")) score += 12
        if (entry.lowerRelativePath.contains("service")) score += 12
        if (entry.lowerRelativePath.contains("repository")) score += 12

        if (isConfigFile(entry.relativePath)) score += 20
        if (entry.lowerRelativePath.contains("/test/") || entry.lowerRelativePath.startsWith("test/")) score -= 18
        if (entry.lowerRelativePath.contains("/resources/") || entry.lowerRelativePath.startsWith("resources/")) score -= 10
        if (entry.extension == "md") score -= 12

        if (queryTerms.isNotEmpty()) {
            score += queryTerms.count { term -> entry.lowerRelativePath.contains(term) } * 6
            score += queryTerms.count { term -> entry.symbolNames.any { it.contains(term) } } * 12
            score += queryTerms.count { term -> term in entry.symbolNames } * 18
        }

        return score
    }

    private fun isConfigFile(path: String): Boolean {
        val lower = path.lowercase(Locale.getDefault())
        return lower.endsWith("application.yml") || lower.endsWith("application.yaml") ||
            lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts") ||
            lower.endsWith("gradle.properties") || lower.endsWith(".env")
    }

    private fun selectTopRelevant(ranked: List<Pair<ProjectFileEntry, Int>>): List<ProjectFileEntry> {
        if (ranked.isEmpty()) return emptyList()
        val positive = ranked.filter { it.second > 0 }
        if (positive.isEmpty()) return emptyList()

        val best = positive.first().second
        val threshold = (best * 0.45).toInt().coerceAtLeast(10)
        val selected = mutableListOf<ProjectFileEntry>()
        for ((entry, score) in positive) {
            if (score < threshold && selected.size >= MIN_RELEVANT_FILES) break
            selected += entry
            if (selected.size >= MAX_DYNAMIC_RELEVANT_FILES) break
        }
        return selected
    }

    private fun buildFileListSection(files: List<ProjectFileEntry>): String {
        val listed = files.joinToString("\n") { "- ${it.relativePath}" }
        return "Project files (read-only listing):\n$listed"
    }

    private fun buildFileSearchSection(files: List<ProjectFileEntry>, rawQuery: String): String {
        val query = rawQuery.lowercase(Locale.getDefault())
        val matches = files
            .asSequence()
            .filter { it.lowerRelativePath.contains(query) }
            .take(MAX_FILE_SEARCH_RESULTS)
            .toList()

        if (matches.isEmpty()) {
            return "File search results for \"$rawQuery\":\n(No matching files found)"
        }

        val body = matches.joinToString("\n") { "- ${it.relativePath}" }
        return "File search results for \"$rawQuery\":\n$body"
    }

    private fun buildTextSearchSection(files: List<ProjectFileEntry>, query: String): String {
        val results = mutableListOf<String>()

        for (file in files) {
            if (file.extension !in TEXT_EXTENSIONS) continue

            val content = ReadAction.compute<String, RuntimeException> {
                runCatching { String(file.virtualFile.contentsToByteArray(), Charsets.UTF_8) }
                    .getOrDefault("")
            }
            if (content.isBlank()) continue

            val matchingLines = content
                .lineSequence()
                .mapIndexedNotNull { index, line ->
                    if (line.contains(query, ignoreCase = true)) {
                        "${index + 1}: ${line.trim().take(MAX_TEXT_LINE_CHARS)}"
                    } else {
                        null
                    }
                }
                .take(MAX_MATCHING_LINES_PER_FILE)
                .toList()

            if (matchingLines.isNotEmpty()) {
                results += "File: ${file.relativePath}\n${matchingLines.joinToString("\n")}"
            }

            if (results.size >= MAX_TEXT_SEARCH_FILES) break
        }

        if (results.isEmpty()) {
            return "Text search results for \"$query\":\n(No matching text found)"
        }

        return "Text search results for \"$query\":\n${results.joinToString("\n\n")}"
    }

    private fun buildRelevantSnippetsSection(files: List<ProjectFileEntry>, queryTerms: Set<String>): String {
        val snippets = files.joinToString("\n\n") { file ->
            val snippet = extractSnippet(file, queryTerms)
            "File: ${file.relativePath}\n$snippet"
        }
        return "Relevant files and excerpts:\n$snippets"
    }

    private fun extractSnippet(file: ProjectFileEntry, queryTerms: Set<String>): String {
        if (file.extension != "kt" && file.extension != "java" && file.extension !in TEXT_EXTENSIONS) {
            return "(Non-text file)"
        }

        val psiBased = extractPsiSnippet(file, queryTerms)
        if (!psiBased.isNullOrBlank()) {
            return "```\n${psiBased.take(MAX_SNIPPET_CHARS)}\n```"
        }

        val content = ReadAction.compute<String, RuntimeException> {
            runCatching { String(file.virtualFile.contentsToByteArray(), Charsets.UTF_8) }
                .getOrDefault("")
        }

        if (content.isBlank()) return "(No readable text content)"

        val lines = content.lines()
        val firstMatchIndex = lines.indexOfFirst { line ->
            queryTerms.any { term -> line.contains(term, ignoreCase = true) }
        }.let { if (it >= 0) it else 0 }

        val from = (firstMatchIndex - 4).coerceAtLeast(0)
        val toExclusive = (from + SNIPPET_LINES).coerceAtMost(lines.size)
        val preview = lines.subList(from, toExclusive).joinToString("\n")
        return "```\n$preview\n```"
    }

    private fun extractPsiSnippet(file: ProjectFileEntry, queryTerms: Set<String>): String? {
        if (file.extension != "kt" && file.extension != "java") return null

        return ReadAction.compute<String?, RuntimeException> {
            val psiFile = psiManager.findFile(file.virtualFile) ?: return@compute null

            var bestMatch: String? = null
            var bestScore = Int.MIN_VALUE

            if (file.extension == "java") {
                psiFile.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitClass(aClass: PsiClass) {
                        val text = aClass.text ?: return
                        val name = aClass.name?.lowercase(Locale.getDefault()).orEmpty()
                        val score = queryTerms.count { name.contains(it) || text.contains(it, ignoreCase = true) }
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = text
                        }
                        super.visitClass(aClass)
                    }

                    override fun visitMethod(method: PsiMethod) {
                        val text = method.text ?: return
                        val name = method.name.lowercase(Locale.getDefault())
                        val score = queryTerms.count { name.contains(it) || text.contains(it, ignoreCase = true) }
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = text
                        }
                        super.visitMethod(method)
                    }
                })
            } else {
                psiFile.accept(object : KtTreeVisitorVoid() {
                    override fun visitClass(klass: KtClass) {
                        val text = klass.text ?: return
                        val name = klass.name?.lowercase(Locale.getDefault()).orEmpty()
                        val score = queryTerms.count { name.contains(it) || text.contains(it, ignoreCase = true) }
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = text
                        }
                        super.visitClass(klass)
                    }

                    override fun visitNamedFunction(function: KtNamedFunction) {
                        val text = function.text ?: return
                        val name = function.name?.lowercase(Locale.getDefault()).orEmpty()
                        val score = queryTerms.count { name.contains(it) || text.contains(it, ignoreCase = true) }
                        if (score > bestScore) {
                            bestScore = score
                            bestMatch = text
                        }
                        super.visitNamedFunction(function)
                    }
                })
            }

            bestMatch
        }
    }

    private fun appendWithinBudget(builder: StringBuilder, section: String) {
        if (section.isBlank()) return
        if (builder.length + section.length + 2 <= CONTEXT_CHAR_BUDGET) {
            if (!builder.endsWith("\n\n")) builder.append("\n\n")
            builder.append(section)
            return
        }

        val remaining = (CONTEXT_CHAR_BUDGET - builder.length - 2).coerceAtLeast(0)
        if (remaining <= 0) return
        if (!builder.endsWith("\n\n")) builder.append("\n\n")
        builder.append(section.take(remaining))
        builder.append("\n... context truncated for token safety.")
    }

    data class ProjectFileEntry(
        val virtualFile: VirtualFile,
        val relativePath: String,
        val lowerRelativePath: String,
        val extension: String,
        val symbolNames: Set<String>,
        val hasClass: Boolean,
        val hasFunction: Boolean,
    )

    data class CachedProjectFiles(
        val files: List<ProjectFileEntry>,
        val rootModCount: Long,
        val psiModCount: Long,
        val createdAt: Long,
    )

    @Volatile
    private var fileCache: CachedProjectFiles? = null

    companion object {
        private const val CACHE_TTL_MS = 10_000L
        private const val CONTEXT_CHAR_BUDGET = 14_000
        private const val MIN_RELEVANT_FILES = 3
        private const val MAX_DYNAMIC_RELEVANT_FILES = 20
        private const val MAX_SNIPPET_CHARS = 2_000
        private const val SNIPPET_LINES = 24
        private const val MAX_FILE_SEARCH_RESULTS = 30
        private const val MAX_TEXT_SEARCH_FILES = 5
        private const val MAX_MATCHING_LINES_PER_FILE = 6
        private const val MAX_TEXT_LINE_CHARS = 220
        private val EXCLUDED_PREFIXES = setOf(".git/", ".idea/", "build/", "out/", ".gradle/")
        private val ALLOWED_EXTENSIONS = setOf("kt", "java", "kts", "xml", "gradle", "md", "json", "yaml", "yml", "properties", "env")
        private val TEXT_EXTENSIONS = ALLOWED_EXTENSIONS + "txt"
    }
}