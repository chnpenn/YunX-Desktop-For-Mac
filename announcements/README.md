# 维护公告

客户端从本仓库 **mac-port 分支**读取 `announcements/announcements.json`：

https://raw.githubusercontent.com/chnpenn/YunX-Desktop-For-Mac/mac-port/announcements/announcements.json

提交并推送文件后，客户端下次启动读取；无需重新构建应用或发布 Release。
GitHub Raw/CDN 可能有短暂缓存，文件推送后不保证所有地区立即生效。

## 发布与修改

- `schemaVersion` 固定为整数 `1`，它是文件格式版本，不是公告版本。
- 新公告：在 `announcements` 数组里增加一项，使用唯一、稳定的 `id`，`version` 从正整数 `1` 开始。
- 修改已有公告：保留 `id`，**递增该项 `version`**（1 → 2 → 3）；不要复用旧版本号。
  已读用户下次启动会再次收到这条公告。只改正文而不递增版本号，不会重新提示。
- 公告 `version` 与 `version.txt` 的软件版本完全独立。看过更高版本后，即使文件回滚，也不重复提示旧版本。
- `title`、`content` 必填且非空；`content` 支持 Markdown，换行写作 JSON 的 `\n`。
- `summary`、`author` 可选；`publishAt` 可选，填写 UTC ISO 时间（例如 `2026-11-13T13:00:00Z`），未来时间到达后才显示。
- `isPinned: true` 表示置顶；`sortOrder` 可选整数，置顶组内数值越大越靠前，其次按发布时间倒序。
- 删除一项即从列表移除。文件可以是空数组，表示暂时没有公告。
- 公开文件不要放密钥、账号凭据或私人信息。

每次进程启动只检查一次，关闭弹窗或打开详情即确认该公告版本；没有新版本则不再弹。
网络请求失败时启动不弹错误；用户进入公告页可手动重试。未成功读取或未确认的版本不会被标为已读。
列表、正文来自同一份文件，翻页和详情共用当前快照，不计浏览量。

本地检查 JSON 语法：

```bash
python3 -m json.tool announcements/announcements.json >/dev/null
```
