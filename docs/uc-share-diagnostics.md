# UC 分享：任务协议修复与诊断

2026-11-11：针对单视频文件分享失败、文件夹分享正常的报告，修改限定在
`UCCoudViewModel`、`UCApi` 与 UC 专用诊断工具；没有真实账号复现，不能宣称已确认现场根因。

代码确认的缺陷与修复：

- 旧轮询固定发送 `retry_index=0`，现在发送 0、1、2……；保持 15 次请求预算及 1 秒间隔，不推测性延长等待时间。
- 旧代码 `finished_at > 0 || status == 2` 会把已结束的失败任务也当成成功；现在仅 `status == 2` 返回 `share_id`，失败状态 3 立即报错，运行中即使已有 ID/结束时间也不提前成功。
- 旧轮询通过 `runCatching().getOrNull()` 吞掉 HTTP、业务及 JSON 错误，最终只显示“创建分享失败”；现在保留具体失败阶段、HTTP 状态、业务 code/message。完成却缺 ID、创建却缺 task_id、详情缺 URL 分别报错。
- 查询任务也执行现有 Cookie 响应回写逻辑；协程取消继续向上传播。

协议交叉证据：本仓库原有创建/详情路径与字段注释，以及
[UCService 原始实现](https://github.com/ctwj/urldb/blob/main/common/uc_pan.go)
的 `getShareTask`（递增 retry_index）、`waitForTask`（仅 status 2 成功）、
`getShareBtn` → `getSharePassword` 流程。该来源是第三方客户端实现者的代码，
不是 UC 官方规范，也不证明现场单文件失败一定由 retry_index 引起。
当前 UC 官网未登录页面只提供登录静态资源，未获得真实分享响应或官方协议说明。

单项文件、文件夹、批量分享继续使用相同 `fid_list` / title / url_type /
expired_type / public_search / passcode 请求模板；没有改成文件夹包装，也没有增加创建重试，
避免重复产生分享。视频下载自动创建分享同样有入口诊断。

日志通过已有 `Log` 持久写入 `<数据目录>/files/yunx-desktop.log`（沿用周轮转），
不依赖可选诊断模式。筛选 `I/UCShare`，其后是 JSON：

- `operation`：本次调用随机关联 ID，串联入口、创建、轮询、详情与最终结果；不是服务端 task_id。
- `share_start`：入口 single/selection/video_download、文件与文件夹数量、有效期、链接类型、是否设置提取码。
- `response`：stage=create/poll/info、attempt、HTTP、api_status、code、脱敏 message。
- `task_state`：attempt、task_status、finished_at、has_share_id、elapsed_ms。
- `create_failed` / `info_failed` / `share_failed`：异常类型、脱敏消息及业务 code；`task_timeout` 明确区分未完成。

不写 Cookie、请求体、文件名/FID、服务端任务/分享 ID、分享 URL、提取码；消息中的 URL、常见凭证字段脱敏并限长。
不要以“下载成功”推断分享链路通过：视频取链有分享之外的播放回退。

回归测试 `UCShareTest` 在 OkHttp 应用拦截器返回固定响应，任何请求都不连接 UC：
覆盖文件/文件夹请求与详情、pending 后成功及 retry_index、业务拒绝、失败结束状态、
HTTP/JSON 失败、缺少任务/分享 ID、空分享 URL。

本次验证：JDK 17 执行 `:desktop:test --tests com.yunx.app.data.network.UCShareTest`，
构建成功，9 个测试通过，0 失败/错误/跳过。数据目录隔离到 `/tmp/yunx-uc-share-tests`；
实际持久日志含 58 条 UCShare JSON 记录，包含 poll response 与 task_state，未写入测试 Cookie。
另覆盖带提取码请求字段和日志脱敏；`git diff --check` 对本任务文件通过。

真实账号待验收：同一文件的单项入口、多选一项、普通文件、视频、文件夹、
多个文件以及带提取码分享；失败时记录提示并按 operation 对照日志。
只有现场日志证明任务长期 pending，才考虑调整等待策略；若服务端拒绝，应依据 code/message 修复或提示，不能用延时掩盖。
