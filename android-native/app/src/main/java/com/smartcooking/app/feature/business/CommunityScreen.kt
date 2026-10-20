package com.smartcooking.app.feature.business

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartcooking.app.core.AppContainer
import com.smartcooking.app.core.Time
import com.smartcooking.app.core.bool
import com.smartcooking.app.core.jsonOf
import com.smartcooking.app.core.num
import com.smartcooking.app.core.obj
import com.smartcooking.app.core.objects
import com.smartcooking.app.core.str
import com.smartcooking.app.core.strings
import com.smartcooking.app.core.userMessage
import com.smartcooking.app.data.loadUploadJpeg
import com.smartcooking.app.ui.components.Avatar
import com.smartcooking.app.ui.components.Choice
import com.smartcooking.app.ui.components.CommunityImage
import com.smartcooking.app.ui.components.CookXCard
import com.smartcooking.app.ui.components.CookXTextField
import com.smartcooking.app.ui.components.DropdownField
import com.smartcooking.app.ui.components.EmptyState
import com.smartcooking.app.ui.components.LinkButton
import com.smartcooking.app.ui.components.LoadingBlock
import com.smartcooking.app.ui.components.OutlineButton
import com.smartcooking.app.ui.components.PrimaryButton
import com.smartcooking.app.ui.components.SegmentedTabs
import com.smartcooking.app.ui.components.StatusChip
import com.smartcooking.app.ui.components.SubpageScaffold
import com.smartcooking.app.ui.components.TonalButton
import com.smartcooking.app.ui.components.Tone
import com.smartcooking.app.ui.components.overlapHero
import com.smartcooking.app.ui.components.pressable
import com.smartcooking.app.ui.nav.Navigator
import com.smartcooking.app.ui.nav.Routes
import com.smartcooking.app.ui.nav.cookxViewModel
import com.smartcooking.app.ui.theme.CookX
import com.smartcooking.app.ui.theme.CookXShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class PostDraft(val editing: JsonObject? = null, val text: String = "", val images: List<String> = emptyList(), val copyId: String = "")

class CommunityViewModel(c: AppContainer) : BusinessViewModel(c, "community") {
    val tab = MutableStateFlow("all")
    val posts = MutableStateFlow<List<JsonObject>?>(null)
    val copies = MutableStateFlow<List<JsonObject>>(emptyList())
    val rule = MutableStateFlow("")
    val draft = MutableStateFlow(PostDraft())
    val uploading = MutableStateFlow(false)
    val moderation = MutableStateFlow<JsonObject?>(null)
    val navigateCopy = MutableStateFlow<String?>(null)
    val isAdmin = c.sessions.session.value?.isAdmin == true

    init { load(); viewModelScope.launch { c.api.changed.collect { runCatching { refreshPosts() } } } }
    private suspend fun refreshPosts() {
        val result = c.business.get(if (tab.value == "hot") "/community/hot" else "/community/posts?mine=${tab.value == "mine"}&bookmarked=${tab.value == "saved"}")
        if (c.sessions.currentUserId == user) posts.value = result.objects("items")
    }
    fun bookmark(post: JsonObject) = viewModelScope.launch {
        try { c.business.call("/community/posts/${post.str("id")}/bookmark", "POST", jsonOf("bookmarked" to !post.bool("bookmarked"))); refreshPosts() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { command.setError(e.userMessage()) }
    }
    fun removeImage(id: String) = viewModelScope.launch {
        try { c.api.delete("/v3/community/media/$id"); draft.value = draft.value.copy(images = draft.value.images - id) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { command.setError(e.userMessage()) }
    }
    fun discard() = viewModelScope.launch {
        if (uploading.value || command.busy.value || command.pending.value != null) return@launch
        try { draft.value.images.forEach { c.api.delete("/v3/community/media/$it") }; draft.value = PostDraft() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { command.setError(e.userMessage()) }
    }

    fun setTab(t: String) { tab.value = t; load() }

    fun load() = launchLoad {
        posts.value = null
        coroutineScope {
            val list = async { c.business.get(if (tab.value == "hot") "/community/hot" else "/community/posts?mine=${tab.value == "mine"}&bookmarked=${tab.value == "saved"}") }
            val sources = async { c.business.get("/recipes/sources?kind=copy") }
            val result = list.await()
            rule.value = result.str("rule").orEmpty()
            posts.value = result.objects("items")
            copies.value = sources.await().objects("items")
        }
    }

    fun upload(loadBytes: suspend () -> ByteArray) = viewModelScope.launch {
        uploading.value = true
        try {
            val bytes = loadBytes()
            if (bytes.size > 5 * 1024 * 1024) throw IllegalArgumentException("图片不能超过 5 MB")
            val id = c.api.upload("/v3/community/media", "file", "dish.jpg", "image/jpeg", bytes, timeoutMs = 20_000).let { it as? JsonObject }?.str("id")
                ?: throw IllegalStateException("上传未返回图片编号")
            draft.value = draft.value.copy(images = draft.value.images + id)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { command.setError(e.userMessage()) }
        finally { uploading.value = false }
    }

    fun publish() {
        val d = draft.value
        val body = jsonOf("text" to d.text, "image_ids" to d.images, "recipe_copy_id" to d.copyId.ifBlank { null }, "confirmed" to true)
        val full = d.editing?.let { JsonObject(body + jsonOf("expected_version" to it.num("version"))) } ?: body
        send("/community/posts" + (d.editing?.let { "/${it.str("id")}" } ?: ""), if (d.editing != null) "put" else "post", full)
    }

    fun edit(post: JsonObject) {
        draft.value = PostDraft(post, post.str("text").orEmpty(), post.strings("image_ids"))
        if (post.obj("recipe") != null) command.setError("编辑时请重新选择要关联的菜谱副本；不选择会移除原关联。")
    }

    fun loadModeration() = launchLoad { moderation.value = c.business.get("/community/moderation") }

    override suspend fun onCommandSuccess(result: JsonObject) {
        result.obj("copy")?.str("id")?.let { navigateCopy.value = it; return }
        draft.value = PostDraft()
        load()
        if (moderation.value != null) moderation.value = c.business.get("/community/moderation")
    }
}

@Composable
fun CommunityScreen(navigator: Navigator) {
    val vm = cookxViewModel { CommunityViewModel(it) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val posts by vm.posts.collectAsStateWithLifecycle()
    val rule by vm.rule.collectAsStateWithLifecycle()
    val navigateCopy by vm.navigateCopy.collectAsStateWithLifecycle()
    val moderation by vm.moderation.collectAsStateWithLifecycle()
    val busy = rememberBusy(vm)
    val confirm = rememberConfirmer()
    navigateCopy?.let { id -> vm.navigateCopy.value = null; navigator.open(Routes.recipes(copy = id)) }

    SubpageScaffold("一起晒菜", navigator::back, subtitle = "作品、点赞与评论保存在这台手机内，方便交流展示。发布不会发送到线上社区。") {
        item {
            Column(Modifier.overlapHero(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CommandStatus(vm, Modifier.padding(horizontal = 16.dp))
                Composer(vm, busy, confirm)
                Box(Modifier.padding(horizontal = 16.dp)) {
                    SegmentedTabs(listOf(Choice("all", "大家的作品"), Choice("mine", "我的作品"), Choice("hot", "热门作品"), Choice("saved", "收藏作品")), tab, vm::setTab, enabled = !busy)
                }
                if (tab == "hot" && rule.isNotBlank()) Text(rule, fontSize = 12.sp, color = CookX.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        val list = posts
        when {
            list == null -> item { LoadingBlock("正在读取作品…") }
            list.isEmpty() -> item { EmptyState(Icons.Outlined.PhotoCamera, "暂无作品", "只会展示成员主动发布的内容。") }
            else -> items(list, key = { it.str("id").orEmpty() }) { post -> PostCard(vm, post, busy, confirm) }
        }
        if (vm.isAdmin) item {
            Section("内容管理", Modifier.padding(top = 12.dp), icon = Icons.Outlined.Shield) {
                OutlineButton("查看举报和隐藏内容", vm::loadModeration)
                moderation?.let { m ->
                    m.objects("items").forEach { r -> ItemBlock(r.str("reason").orEmpty(), Modifier.padding(top = 8.dp), lines = listOf("作品 ${r.str("post_id")}")) }
                    m.objects("posts").forEach { p ->
                        ItemBlock(p.str("text").orEmpty().take(60), Modifier.padding(top = 8.dp), lines = listOf(p.str("moderation_reason").orEmpty()), actions = {
                            TonalButton("恢复展示", { confirm.askText("内容管理", "记录处理原因") { reason -> vm.send("/community/posts/${p.str("id")}/moderation", "post", jsonOf("hidden" to false, "reason" to reason, "expected_version" to p.num("version"))) } }, enabled = !busy)
                        })
                    }
                }
            }
        }
    }
    confirm.Host()
}

@Composable
private fun Composer(vm: CommunityViewModel, busy: Boolean, confirm: Confirmer) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.upload { withContext(Dispatchers.IO) { loadUploadJpeg(context, uri, 1600, 85) } }
    }
    Section(if (draft.editing != null) "编辑我的作品" else "分享今天的作品", icon = Icons.Outlined.PhotoCamera) {
        CookXTextField(draft.text, { vm.draft.value = draft.copy(text = it) }, "作品文字", placeholder = "记录这次做饭的尝试与心得", singleLine = false, minLines = 3, maxLength = 3000)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.images.forEach { id ->
                Box(Modifier.weight(1f).aspectRatio(1f)) {
                    CommunityImage(id, Modifier.matchParentSize())
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color(0x99000000)).pressable({ vm.removeImage(id) }), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, "移除此图片", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (draft.images.size < 4) Box(
                Modifier.weight(1f).aspectRatio(1f).clip(CookXShapes.Tile).background(CookX.Mint)
                    .clickable(enabled = !uploading) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, tint = CookX.Primary)
                    Text(if (uploading) "上传中…" else "${draft.images.size}/4", fontSize = 11.sp, color = CookX.Primary)
                }
            }
            repeat((3 - draft.images.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        DropdownField("关联独立菜谱副本（可选）", draft.copyId, listOf(Choice("", "不关联菜谱")) + copies.map { Choice(it.str("id").orEmpty(), it.obj("recipe")?.str("dish_name").orEmpty()) }, { vm.draft.value = draft.copy(copyId = it) },
            supporting = "没有结构化菜谱的作品只作交流展示，不能作为可执行教程。")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (draft.editing != null) "确认更新作品" else "发布作品", {
                confirm.ask("确认分享", "将这些图片、文字及关联菜谱加入本机社区。确认发布？", vm::publish)
            }, Modifier.weight(1f), enabled = !busy && !uploading && draft.text.isNotBlank())
            if (draft.editing != null || draft.images.isNotEmpty() || draft.text.isNotBlank()) OutlineButton("放弃草稿", { vm.discard() }, enabled = !busy && !uploading, color = CookX.TextSecondary)
        }
    }
}

@Composable
private fun PostCard(vm: CommunityViewModel, post: JsonObject, busy: Boolean, confirm: Confirmer) {
    val hidden = post.bool("hidden")
    val id = post.str("id").orEmpty()
    CookXCard(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(null, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(post.str("author_name").orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(Time.display(post.str("created_at")), fontSize = 11.sp, color = CookX.TextTertiary)
            }
            if (hidden) StatusChip("已隐藏", Tone.Danger, icon = Icons.Outlined.VisibilityOff)
            post.obj("hot")?.let { h -> StatusChip("👍 ${h.num("likes")?.toInt() ?: 0} · 💬 ${h.num("commenters")?.toInt() ?: 0}", Tone.Warm) }
        }
        if (hidden) Text("隐藏原因：${post.str("moderation_reason").orEmpty()}", fontSize = 12.sp, color = CookX.Danger, modifier = Modifier.padding(top = 6.dp))
        Text(post.str("text").orEmpty(), style = MaterialTheme.typography.bodyLarge, color = CookX.TextBody, modifier = Modifier.padding(top = 10.dp))
        val images = post.strings("image_ids")
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            if (images.size == 1) CommunityImage(images[0], Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            else images.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { CommunityImage(it, Modifier.weight(1f).aspectRatio(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        post.obj("recipe")?.let { r ->
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(CookXShapes.Tile).background(CookX.WarningBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.str("dish_name").orEmpty(), style = MaterialTheme.typography.titleSmall)
                    Text("${r.objects("steps").size} 个结构化步骤", fontSize = 11.sp, color = CookX.TextSecondary)
                }
                if (!hidden) TonalButton("复刻", { vm.send("/recipes/copies", "post", jsonOf("source_type" to "community", "source_id" to id, "expected_source_version" to post.num("version")?.toLong()?.toString())) },
                    enabled = !busy, icon = Icons.Outlined.ContentCopy, tone = Tone.Warm)
            }
        }
        if (!hidden) { TonalButton(if (post.bool("bookmarked")) "已收藏作品" else "收藏作品", { vm.bookmark(post) }, enabled = !busy); Engagement(vm, id) }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (post.str("owner") == vm.user) {
                LinkButton("编辑", { vm.edit(post) }, icon = Icons.Outlined.Edit, color = CookX.Primary, enabled = !busy)
                LinkButton("删除", { confirm.ask("删除作品", "删除后作品将退出展示和榜单。") { vm.send("/community/posts/$id", "delete", jsonOf("expected_version" to post.num("version"))) } }, icon = Icons.Outlined.DeleteOutline, color = CookX.Danger, enabled = !busy)
            }
            if (!hidden) LinkButton("举报", { confirm.askText("举报", "说明需要处理的内容") { vm.send("/community/posts/$id/reports", "post", jsonOf("reason" to it)) } }, icon = Icons.Outlined.Flag, enabled = !busy)
            if (vm.isAdmin) LinkButton(if (hidden) "恢复展示" else "隐藏内容", {
                confirm.askText("内容管理", "记录处理原因") { vm.send("/community/posts/$id/moderation", "post", jsonOf("hidden" to !hidden, "reason" to it, "expected_version" to post.num("version"))) }
            }, icon = Icons.Outlined.Shield, enabled = !busy)
        }
    }
}

/** Likes and comments have their own command channels so they never block post actions. */
@Composable
private fun Engagement(vm: CommunityViewModel, postId: String) {
    val like = cookxViewModel(key = "like-$postId") { LikesViewModel(it, postId) }
    val value by like.value.collectAsStateWithLifecycle()
    val likeBusy = rememberBusy(like)
    val comments = cookxViewModel(key = "comments-$postId") { CommentsViewModel(it, postId) }
    val open by comments.open.collectAsStateWithLifecycle()
    val list by comments.items.collectAsStateWithLifecycle()
    val commentBusy = rememberBusy(comments)
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val liked = value?.bool("liked") == true
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TonalButton("${if (liked) "已赞" else "点赞"} · ${value?.num("count")?.toInt() ?: 0}", {
            value?.let { v -> like.send("/community/posts/$postId/likes", "put", jsonOf("liked" to !liked, "expected_version" to v.num("version"))) }
        }, icon = if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, tone = if (liked) Tone.Warm else Tone.Neutral, enabled = !likeBusy && value != null)
        TonalButton(if (open) "刷新评论" else "查看评论", { comments.load() }, icon = Icons.Outlined.ChatBubbleOutline, tone = Tone.Neutral)
    }
    CommandStatus(like, Modifier.padding(top = 6.dp))
    if (open) Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(CookXShapes.Tile).background(CookX.SurfaceMuted).padding(12.dp)) {
        CommandStatus(comments)
        list.forEach { c ->
            Row(Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.str("author_name").orEmpty(), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text(c.str("text").orEmpty(), style = MaterialTheme.typography.bodySmall, color = CookX.TextBody)
                    Text(Time.display(c.str("created_at")), fontSize = 10.5.sp, color = CookX.TextTertiary)
                }
                if (c.str("owner") == vm.user || vm.isAdmin) Icon(Icons.Outlined.DeleteOutline, "删除评论", tint = CookX.TextSecondary,
                    modifier = Modifier.size(28.dp).clip(CircleShape).pressable({ comments.send("/community/posts/$postId/comments/${c.str("id")}", "delete", jsonOf("expected_version" to c.num("version"))) }).padding(5.dp))
            }
        }
        if (list.isEmpty()) Text("还没有评论", fontSize = 12.sp, color = CookX.TextTertiary)
        CookXTextField(text, { text = it }, "写一条评论", singleLine = false, maxLength = 1000)
        PrimaryButton("发表评论", { scope.launch { comments.command.send("/community/posts/$postId/comments", "post", jsonOf("text" to text)); if (comments.command.error.value.isBlank()) text = "" } },
            Modifier.padding(top = 8.dp), enabled = !commentBusy && text.isNotBlank())
    }
}

class LikesViewModel(c: AppContainer, private val postId: String) : BusinessViewModel(c, "like-$postId") {
    val value = MutableStateFlow<JsonObject?>(null)
    init { load() }
    fun load() = launchLoad { value.value = c.business.get("/community/posts/$postId/likes") }
    override suspend fun onCommandSuccess(result: JsonObject) { value.value = c.business.get("/community/posts/$postId/likes") }
}

class CommentsViewModel(c: AppContainer, private val postId: String) : BusinessViewModel(c, "comments-$postId") {
    val open = MutableStateFlow(false)
    val items = MutableStateFlow<List<JsonObject>>(emptyList())
    fun load() = launchLoad { open.value = true; items.value = c.business.get("/community/posts/$postId/comments").objects("items") }
    override suspend fun onCommandSuccess(result: JsonObject) { items.value = c.business.get("/community/posts/$postId/comments").objects("items") }
}
