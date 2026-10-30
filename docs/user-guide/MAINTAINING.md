# 手册维护与复验

[返回目录](README.md) · [本次验证记录](VALIDATION.md)

截图由 `outbound-mono/frontend/tests/manual-capture.mjs` 采集，使用现有 `FrontendBrowserIT` 的临时 MySQL、合成账号和 Mock 通话。普通浏览器回归默认不采集截图；仅设置 `MONO_CAPTURE_MANUAL=true` 时启用。

## 重新生成

准备 JDK 21、Maven 3.9.11、Node、已安装的前端依赖与 Playwright Chromium，以及可用的本地 Docker。按照 [工程说明](../../outbound-mono/README.md)完成依赖安装。

从仓库根目录进入单体工程，先构建，再单独运行浏览器集成验收：

```sh
cd outbound-mono
mvn -B -DskipTests package
MONO_CAPTURE_MANUAL=true mvn -B -Dit.test=FrontendBrowserIT failsafe:integration-test failsafe:verify
```

不要把线上地址和账号填入采集脚本。测试本身创建并清理隔离数据库，采集脚本包含用于测试的固定账号。

## 核对并更新证据

1. 确认 Maven 成功、`target/frontend-browser-result.json` 中 `passed=true`，并且检查列表完整。
2. 将该 JSON 的内容更新到 `docs/user-guide/browser-validation.json`，保留来源版本和采集说明。
3. 逐张检查截图，重点检查异步表单、详情抽屉、空状态、字段文字及是否包含真实数据。
4. 更新 `capture-manifest.json` 的日期和截图清单，检查所有图片在正文中都有说明。
5. 更新本次验证记录，区分入口展示、表单保存、真实业务和部署状态。
6. 从仓库根目录运行静态校验：

```sh
node outbound-mono/frontend/tests/manual-validate.mjs
node --check outbound-mono/frontend/tests/manual-capture.mjs
node --check outbound-mono/frontend/tests/manual-validate.mjs
git diff --check
```

静态校验检查本目录 Markdown 链接、PNG 格式与尺寸、截图引用、路由清单及浏览器结果摘要。截图清晰度、正文含义和真实业务状态仍需人工核对。

业务界面或流程变化时同步更新手册；提交截图不代表已部署。
