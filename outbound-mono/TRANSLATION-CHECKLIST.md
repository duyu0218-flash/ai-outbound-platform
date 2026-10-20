# 转译对照表

接口已转译 **108/108（100%）**，不适用 0；手册功能已转译 **36/36（100%）**，不适用 0。接口来源为原 OpenAPI 的每个 path+method，手册按原第 2–16、19 章叶级功能章节统计，父章节中的字段/交互要求一并保留。

“已转译”表示实现已进入目标工程并有对应开发环境验证；不代表真实线路、真机、媒体压测或生产发布完成。通用迁移单调与显式业务回退按原实现分别保留，见 MIGRATION-NOTES.md 第 14 条。

## 表 A：接口对照

| 原方法 | 原路径 | 新路径 | 状态 | 备注 |
|---|---|---|---|---|
| GET | /api/v1/product/policy | /api/v1/product/policy | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/product/policy | /api/v1/product/policy | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/product/simulate | /api/v1/product/simulate | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/product/appointments | /api/v1/product/appointments | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PATCH | /api/v1/product/appointments/{appointment_id} | /api/v1/product/appointments/{appointment_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/product/work-items | /api/v1/product/work-items | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PATCH | /api/v1/product/work-items/{item_id} | /api/v1/product/work-items/{item_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/product/incoming | /api/v1/product/incoming | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/product/outcomes | /api/v1/product/outcomes | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/product/knowledge/import | /api/v1/product/knowledge/import | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/product/knowledge/search | /api/v1/product/knowledge/search | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/product/funnel | /api/v1/product/funnel | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /health | /health | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /healthz | /healthz | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /readyz | /readyz | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls | /api/v1/calls | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls | /api/v1/calls | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id} | /api/v1/calls/{call_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/handover | /api/v1/calls/{call_id}/handover | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/hangup | /api/v1/calls/{call_id}/hangup | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/events | /api/v1/calls/{call_id}/events | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/webhook-events | /api/v1/calls/{call_id}/webhook-events | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/webhook-stats | /api/v1/calls/{call_id}/webhook-stats | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/retry | /api/v1/calls/{call_id}/retry | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言；终态未耗尽可重拨；非终态/耗尽 409；8 条回归覆盖原回退路径 |
| POST | /api/v1/campaigns | /api/v1/campaigns | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/campaigns | /api/v1/campaigns | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/campaigns/{campaign_id} | /api/v1/campaigns/{campaign_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/campaigns/{campaign_id} | /api/v1/campaigns/{campaign_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/campaigns/{campaign_id} | /api/v1/campaigns/{campaign_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/campaigns/{campaign_id}/start | /api/v1/campaigns/{campaign_id}/start | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/campaigns/{campaign_id}/pause | /api/v1/campaigns/{campaign_id}/pause | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/campaigns/{campaign_id}/resume | /api/v1/campaigns/{campaign_id}/resume | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/campaigns/{campaign_id}/stop | /api/v1/campaigns/{campaign_id}/stop | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/contacts/import | /api/v1/contacts/import | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/contacts/export | /api/v1/contacts/export | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PATCH | /api/v1/contacts/batch-dnc | /api/v1/contacts/batch-dnc | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/contacts | /api/v1/contacts | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/contacts | /api/v1/contacts | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/contacts/{contact_id} | /api/v1/contacts/{contact_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PATCH | /api/v1/contacts/{contact_id} | /api/v1/contacts/{contact_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/contacts/{contact_id} | /api/v1/contacts/{contact_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/batch | /api/v1/webhooks/telephony/batch | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/status | /api/v1/webhooks/telephony/status | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/transcript | /api/v1/webhooks/telephony/transcript | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/speech | /api/v1/webhooks/telephony/speech | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/dtmf | /api/v1/webhooks/telephony/dtmf | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/media | /api/v1/webhooks/telephony/media | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/telephony/recording | /api/v1/webhooks/telephony/recording | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/webhooks/sms/status | /api/v1/webhooks/sms/status | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/auth/login | /api/v1/auth/login | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/auth/me | /api/v1/auth/me | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/auth/presence | /api/v1/auth/presence | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/auth/logout | /api/v1/auth/logout | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/script-templates | /api/v1/script-templates | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/script-templates | /api/v1/script-templates | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/script-templates/{template_id} | /api/v1/script-templates/{template_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/script-templates/{template_id} | /api/v1/script-templates/{template_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/script-templates/{template_id} | /api/v1/script-templates/{template_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/script-templates/{template_id}/flows | /api/v1/script-templates/{template_id}/flows | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/script-templates/{template_id}/flows | /api/v1/script-templates/{template_id}/flows | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/script-templates/{template_id}/flows/{version_id} | /api/v1/script-templates/{template_id}/flows/{version_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/script-templates/{template_id}/flows/{version_id} | /api/v1/script-templates/{template_id}/flows/{version_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/script-templates/{template_id}/flows/{version_id}/publish | /api/v1/script-templates/{template_id}/flows/{version_id}/publish | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/script-templates/{template_id}/flows/{version_id}/simulate | /api/v1/script-templates/{template_id}/flows/{version_id}/simulate | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/tasks/{task_id}/retry | /api/v1/admin/tasks/{task_id}/retry | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/users | /api/v1/admin/users | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/users | /api/v1/admin/users | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/admin/users/{user_id} | /api/v1/admin/users/{user_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/admin/users/{user_id} | /api/v1/admin/users/{user_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/users/{user_id}/reset-password | /api/v1/admin/users/{user_id}/reset-password | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/users/{user_id}/unlock | /api/v1/admin/users/{user_id}/unlock | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/lines | /api/v1/admin/lines | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/lines | /api/v1/admin/lines | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/admin/lines/{line_id} | /api/v1/admin/lines/{line_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/admin/lines/{line_id} | /api/v1/admin/lines/{line_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/sms-logs | /api/v1/admin/sms-logs | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/admin/sms-logs/{sms_log_id}/retry | /api/v1/admin/sms-logs/{sms_log_id}/retry | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/settings/{section} | /api/v1/admin/settings/{section} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/admin/settings/{section} | /api/v1/admin/settings/{section} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/calls/export | /api/v1/admin/calls/export | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/audit-logs | /api/v1/admin/audit-logs | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/call-reports | /api/v1/admin/call-reports | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/contact-groups | /api/v1/admin/contact-groups | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/billing | /api/v1/admin/billing | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/system-overview | /api/v1/admin/system-overview | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/realtime | /api/v1/calls/{call_id}/realtime | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/speech-turns | /api/v1/calls/{call_id}/speech-turns | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/metrics | /api/v1/calls/{call_id}/metrics | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/recordings | /api/v1/calls/{call_id}/recordings | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/interrupt | /api/v1/calls/{call_id}/interrupt | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/analysis | /api/v1/calls/{call_id}/analysis | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/calls/{call_id}/analysis | /api/v1/calls/{call_id}/analysis | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/calls/{call_id}/handoffs | /api/v1/calls/{call_id}/handoffs | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/handoffs | /api/v1/handoffs | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/quality/reviews | /api/v1/quality/reviews | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/handoffs/{handoff_id}/accept | /api/v1/calls/{call_id}/handoffs/{handoff_id}/accept | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/calls/{call_id}/handoffs/{handoff_id}/reject | /api/v1/calls/{call_id}/handoffs/{handoff_id}/reject | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/knowledge | /api/v1/knowledge | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/knowledge | /api/v1/knowledge | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/knowledge/{item_id} | /api/v1/knowledge/{item_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| DELETE | /api/v1/knowledge/{item_id} | /api/v1/knowledge/{item_id} | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| POST | /api/v1/agent/webrtc/session | /api/v1/agent/webrtc/session | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/agent/media/status | /api/v1/agent/media/status | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| PUT | /api/v1/agent/media/status | /api/v1/agent/media/status | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/agent/events/stream | /api/v1/agent/events/stream | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/runtime | /api/v1/runtime | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/admin/dashboard | /api/v1/admin/dashboard | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |
| GET | /api/v1/agent/dashboard | /api/v1/agent/dashboard | 已转译 | 原请求/响应由 Contract 按基准字段处理；108 路由存在性由 BusinessHttpIT 断言 |

## 表 B：功能对照

| 功能点 | 原实现位置 | 新实现位置 | 状态 | 备注 |
|---|---|---|---|---|
| 2. 访问地址与角色 | frontend/src/layout.tsx; frontend/src/App.tsx | frontend/src/router.js; frontend/src/Shell.vue | 已转译 | 原手册第 19 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 3. 登录、语言和退出 | frontend/src/auth.tsx; frontend/src/i18n.ts | frontend/src/session.js; frontend/src/i18n.js; frontend/src/pages/Login.vue | 已转译 | 原手册第 35 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 4. 管理中心概览 | backend-java/src/main/java/com/duozhi/outbound/Dashboard.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/Dashboard.java; frontend/src/pages/Dashboard.vue | 已转译 | 原手册第 54 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 新增账号 | backend-java/src/main/java/com/duozhi/outbound/AdminUsers.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/AdminUsers.java; frontend/src/pages/Users.vue | 已转译 | 原手册第 77 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 编辑、停用和改密 | backend-java/src/main/java/com/duozhi/outbound/AdminUsers.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/AdminUsers.java; frontend/src/pages/Users.vue | 已转译 | 原手册第 87 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 单个客户 | backend-java/src/main/java/com/duozhi/outbound/Contacts.java / ContactFiles.java | src/main/java/com/duozhi/outbound/Contacts.java / ContactFiles.java; frontend/src/pages/Contacts.vue / ContactOperations.vue | 已转译 | 原手册第 100 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| CSV 导入、导出和批量禁打 | backend-java/src/main/java/com/duozhi/outbound/Contacts.java / ContactFiles.java | src/main/java/com/duozhi/outbound/Contacts.java / ContactFiles.java; frontend/src/pages/Contacts.vue / ContactOperations.vue | 已转译 | 原手册第 122 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 新建基础话术 | backend-java/src/main/java/com/duozhi/outbound/Templates.java / ScriptFlows.java | src/main/java/com/duozhi/outbound/Templates.java / ScriptFlows.java; frontend/src/pages/Scripts.vue; frontend/src/components/FlowDesigner.vue | 已转译 | 原手册第 142 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 配置话术画布 | backend-java/src/main/java/com/duozhi/outbound/Templates.java / ScriptFlows.java | src/main/java/com/duozhi/outbound/Templates.java / ScriptFlows.java; frontend/src/pages/Scripts.vue; frontend/src/components/FlowDesigner.vue | 已转译 | 原手册第 155 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 8. 知识库 | backend-java/src/main/java/com/duozhi/outbound/Knowledge.java | src/main/java/com/duozhi/outbound/Knowledge.java; frontend/src/pages/Knowledge.vue | 已转译 | 原手册第 176 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 9. 外呼线路 | backend-java/src/main/java/com/duozhi/outbound/AdminLines.java | src/main/java/com/duozhi/outbound/AdminLines.java; frontend/src/pages/Lines.vue | 已转译 | 原手册第 196 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 并发容量 | backend-java/src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java | src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java; frontend/src/pages/Settings.vue; frontend/src/settings-fields.js | 已转译 | 原手册第 226 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| AI 与语音 | backend-java/src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java | src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java; frontend/src/pages/Settings.vue; frontend/src/settings-fields.js | 已转译 | 原手册第 233 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 短信配置 | backend-java/src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java | src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java; frontend/src/pages/Settings.vue; frontend/src/settings-fields.js | 已转译 | 原手册第 239 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 合规策略 | backend-java/src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java | src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java; frontend/src/pages/Settings.vue; frontend/src/settings-fields.js | 已转译 | 原手册第 245 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 接口与回调 | backend-java/src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java | src/main/java/com/duozhi/outbound/Settings.java / AdminSettings.java; frontend/src/pages/Settings.vue; frontend/src/settings-fields.js | 已转译 | 原手册第 263 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 创建任务 | backend-java/src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java | src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java; frontend/src/pages/Campaigns.vue | 已转译 | 原手册第 275 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 启动前检查 | backend-java/src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java | src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java; frontend/src/pages/Campaigns.vue | 已转译 | 原手册第 292 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 启动、暂停、恢复和停止 | backend-java/src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java | src/main/java/com/duozhi/outbound/Campaigns.java / DialService.java; frontend/src/pages/Campaigns.vue | 已转译 | 原手册第 302 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 发起单路呼叫 | backend-java/src/main/java/com/duozhi/outbound/Calls.java / CallLifecycle.java | src/main/java/com/duozhi/outbound/Calls.java / CallLifecycle.java; frontend/src/pages/Calls.vue | 已转译 | 原手册第 327 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 通话状态和操作 | backend-java/src/main/java/com/duozhi/outbound/Calls.java / CallLifecycle.java | src/main/java/com/duozhi/outbound/Calls.java / CallLifecycle.java; frontend/src/pages/Calls.vue | 已转译 | 原手册第 336 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 座席状态 | backend-java/src/main/java/com/duozhi/outbound/Handoffs.java / AgentMedia.java; frontend/src/webrtc.tsx | src/main/java/com/duozhi/outbound/Handoffs.java / AgentMedia.java; frontend/src/pages/Agent.vue; frontend/src/components/Softphone.vue | 已转译 | 原手册第 350 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；真机 SIP/麦克风未验证 |
| 接管流程 | backend-java/src/main/java/com/duozhi/outbound/Handoffs.java / AgentMedia.java; frontend/src/webrtc.tsx | src/main/java/com/duozhi/outbound/Handoffs.java / AgentMedia.java; frontend/src/pages/Agent.vue; frontend/src/components/Softphone.vue | 已转译 | 原手册第 358 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；真机 SIP/麦克风未验证 |
| 14. 质检复核 | backend-java/src/main/java/com/duozhi/outbound/CallAnalysis.java / Reports.java | src/main/java/com/duozhi/outbound/CallAnalysis.java / Reports.java; frontend/src/pages/Quality.vue | 已转译 | 原手册第 380 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 运营报表 | backend-java/src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages/Reports.vue / Groups.vue / Billing.vue | 已转译 | 原手册第 396 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 分组监控 | backend-java/src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages/Reports.vue / Groups.vue / Billing.vue | 已转译 | 原手册第 402 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 账单与计费 | backend-java/src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages.tsx | src/main/java/com/duozhi/outbound/Reports.java; frontend/src/pages/Reports.vue / Groups.vue / Billing.vue | 已转译 | 原手册第 408 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 16. 监控与审计 | backend-java/src/main/java/com/duozhi/outbound/SystemOverview.java / AdminOperations.java | src/main/java/com/duozhi/outbound/SystemOverview.java / AdminOperations.java / OperationalMetrics.java; frontend/src/pages/System.vue | 已转译 | 原手册第 414 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json |
| 19.1 设置作用范围与异常处理 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 461 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.2 配置信息采集 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 470 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.3 设置转人工、服务时间与重拨 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 479 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.4 发布、刷新、试跑与版本回退 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 487 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.5 预约回拨 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 501 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.6 人工跟进 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 513 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.7 交付统计与交付结果 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 521 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |
| 19.8 知识导入与检索测试 | backend-java/src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/product-operations.tsx | src/main/java/com/duozhi/outbound/ProductOperations.java / ConversationPolicy.java; frontend/src/pages/Product.vue | 已转译 | 原手册第 529 行起；对应 HTTP/数据库/浏览器验证见 continuous-test-cases.json 与 frontend-browser-result.json；涉及重拨/状态恢复时按原显式回退保留，见 MIGRATION-NOTES 第 14 条 |

## 例外与完整证据

无 tenant_id 的 8 表原文：verification/tenant-exception-source-evidence.md。5 个纯平台表为 tenant、gatewaynode、callbackinboxpartition、callbackinbox、callbackinboxworker；campaigncontact、callevent、webhookeventingest 通过父表租户条件隔离。认证前置 B 类使用全局唯一 username、用户主键 uid 与 tenant 主键，后续身份租户只取数据库结果。

当前 49 条集中例外及每条 Mapper 语句见 verification/tenant-exceptions-current.md、verification/mapper-tenant-conditions-current.md。构建测试 TenantMapperPolicyTest 扫描全部 XML，负向删除 tenant_id 的已实测失败证据见 verification/auth-tenant-policy-negative.txt，随后已还原。

全量：133 单元 + 178 集成 = 311，失败/错误/跳过均 0。独立 Enforcer 通过；打包 jar /healthz 200，/admin/login 与资源 JS 200。原 126 个取证文件 SHA-256 无变化。

页面、入口、状态及验证边界：verification/UI-ENTRY-VERIFICATION.md。原部署环境与生产均未操作。

## 2026-10-20 存储收尾增量

108/108 接口与 36/36 功能、不适用均 0，未新增接口或功能。MySQL 当前为 34 张业务表 + 8 张语音账本 + 2 张模型配额表 = **44 张应用表**，另 1 张 Flyway 元数据表。V1 原样，V2 新增 10 张；实体/Mapper 仍仅对应原 34 张业务表，辅助账本沿用独立 JDBC 事务。单实例 PlatformLock 接口/JVM 实现，显式重拨及 7 类回退路径已恢复；本轮证据单独存于 verification/closeout-20261020/。
